package com.example.g_one.llm;

import com.example.g_one.GOneProperties;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Picks the language model for each call, for both callers (agent chat and JSON tasks). The chain is the configured
 * primary then the fallbacks. Each model has its own cooldown (from Retry-After or the rate-limit headers after a 429) and
 * a sliding 60-second token budget that declines a call the provider would refuse. A model that clears within
 * {@value #MAX_WAIT_MS} ms is waited for; otherwise the next one is tried. {@link Task#NUMERIC} never uses a model the
 * config marks as unsafe with numbers.
 */
@Component
public class ModelRouter {
    /** What the call is for, which limits the models allowed to answer it. */
    public enum Task {
        /** Conversation with no figures in play. Any model in the chain. */
        CHAT,
        /** Translation, deal extraction or anything quoting figures. Never a numeric-unsafe model. */
        NUMERIC
    }

    /** The longest a phone turn may wait for a model to clear. */
    public static final long MAX_WAIT_MS = 1_500;
    private static final long WINDOW_MS = 60_000;
    private static final long DEFAULT_COOLDOWN_MS = 5_000;
    /** No further model is tried once a call has already taken this long. */
    private static final long SWITCH_DEADLINE_MS = 8_000;
    private static final Pattern DURATION = Pattern.compile("([0-9.]+)(ms|h|m|s)");
    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    /** The provider refused with HTTP 429. {@code retryAfterMs} is 0 when it did not say. */
    public static final class RateLimited extends RuntimeException {
        final transient HttpHeaders headers;

        public RateLimited(HttpHeaders headers) {
            super("rate limited", null, false, false);
            this.headers = headers;
        }
    }

    /** This model failed in a way the next model may not (server error, timeout, model gone). */
    public static final class TryNext extends RuntimeException {
        private final long cooldownMs;

        public TryNext(RuntimeException cause) {
            this(cause, DEFAULT_COOLDOWN_MS);
        }

        public TryNext(RuntimeException cause, long cooldownMs) {
            super("try next model", cause, false, false);
            this.cooldownMs = cooldownMs;
        }

        RuntimeException failure() {
            return (RuntimeException) getCause();
        }

        long cooldownMs() {
            return cooldownMs;
        }
    }

    /** No allowed model can take this call now. */
    public static final class AllBusy extends RuntimeException {
        private final long retryInMs;

        AllBusy(long retryInMs) {
            super("All language models are busy.", null, false, false);
            this.retryInMs = retryInMs;
        }

        /** When the soonest model should clear; 0 when unknown. */
        public long retryInMs() {
            return retryInMs;
        }
    }

    /** One try on one model. Report spend through {@link #spent} and {@link #observe}. */
    public final class Slot {
        private final String model;
        private final Budget budget;
        private final long[] reservation;
        private boolean settled;

        private Slot(String model, Budget budget, long[] reservation) {
            this.model = model;
            this.budget = budget;
            this.reservation = reservation;
        }

        public String model() {
            return model;
        }

        /** What the call really cost, replacing the estimate. Zero keeps the estimate. */
        public void spent(int totalTokens) {
            settled = true;
            if (totalTokens > 0) budget.settle(reservation, totalTokens);
        }

        /** Reads the provider's remaining-token headers from a successful response. */
        public void observe(HttpHeaders headers) {
            long remaining = number(headers.getFirst("x-ratelimit-remaining-tokens"));
            long limit = number(headers.getFirst("x-ratelimit-limit-tokens"));
            if (remaining >= 0) budget.server(remaining, limit, now.getAsLong());
        }

        private void release() {
            if (!settled) budget.release(reservation);
        }
    }

    @FunctionalInterface
    public interface Attempt<T> {
        T call(Slot slot);
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Per-model limits. All guarded by the instance lock. */
    private static final class Budget {
        private final Deque<long[]> window = new ArrayDeque<>();
        private long cooldownUntil;
        private long serverRemaining = -1;
        private long serverAt;
        private long limit;

        Budget(long limit) {
            this.limit = limit;
        }

        synchronized long[] reserve(long tokens, long nowMs) {
            long[] entry = {nowMs, tokens};
            window.addLast(entry);
            return entry;
        }

        synchronized void settle(long[] entry, long tokens) {
            entry[1] = tokens;
        }

        synchronized void release(long[] entry) {
            window.remove(entry);
        }

        synchronized void server(long remaining, long reportedLimit, long nowMs) {
            serverRemaining = remaining;
            serverAt = nowMs;
            if (limit <= 0 && reportedLimit > 0) limit = reportedLimit;
        }

        synchronized void cool(long untilMs) {
            cooldownUntil = Math.max(cooldownUntil, untilMs);
        }

        /** Milliseconds until a call of this size should be accepted; 0 when it can go now. */
        synchronized long waitFor(long tokens, long nowMs) {
            while (!window.isEmpty() && window.peekFirst()[0] <= nowMs - WINDOW_MS) window.removeFirst();
            long wait = Math.max(0, cooldownUntil - nowMs);
            if (limit <= 0) return wait;
            long used = window.stream().mapToLong(e -> e[1]).sum();
            if (used + tokens > limit) {
                long free = limit - used;
                long slide = WINDOW_MS;
                for (long[] e : window) {
                    free += e[1];
                    if (free >= tokens) {
                        slide = e[0] + WINDOW_MS - nowMs;
                        break;
                    }
                }
                wait = Math.max(wait, slide);
            }
            if (serverRemaining >= 0) {
                double refilled = serverRemaining + (nowMs - serverAt) * (double) limit / WINDOW_MS;
                if (refilled < tokens) wait = Math.max(wait, (long) ((tokens - refilled) * WINDOW_MS / limit));
            }
            return wait;
        }
    }

    private final List<String> chain;
    private final List<String> numericChain;
    private final List<String> plainChain;
    private final List<String> plainModels;
    private final long plainTokensPerMinute;
    private final long tokensPerMinute;
    private final Map<String, Budget> budgets = new ConcurrentHashMap<>();
    private final LongSupplier now;
    private final Sleeper sleeper;

    @Autowired
    public ModelRouter(GOneProperties props) {
        this(props.llm(), System::currentTimeMillis, Thread::sleep);
    }

    public ModelRouter(GOneProperties.Llm cfg, LongSupplier now, Sleeper sleeper) {
        this.now = now;
        this.sleeper = sleeper;
        List<String> models = new ArrayList<>();
        if (cfg != null && !cfg.model().isBlank()) models.add(cfg.model());
        if (cfg != null) cfg.fallbackModels().stream().filter(m -> !models.contains(m)).forEach(models::add);
        if (cfg != null) cfg.extraModels().stream().filter(m -> !models.contains(m)).forEach(models::add);
        this.chain = List.copyOf(models);
        if (cfg != null) cfg.plainModels().stream().filter(models::contains)
                .forEach(m -> log.warn("llm_route model={} plain overlap ignored; remove it from primary/fallback/extra or plain config", m));
        this.plainModels = cfg == null ? List.of() : cfg.plainModels().stream()
                .filter(m -> !models.contains(m)).toList();
        List<String> plainFirst = new ArrayList<>(plainModels);
        plainFirst.addAll(models);
        this.plainChain = List.copyOf(plainFirst);
        this.plainTokensPerMinute = cfg == null ? 0 : cfg.plainTokensPerMinute();
        List<String> unsafe = cfg == null ? List.of() : cfg.numericUnsafeModels();
        this.numericChain = models.stream().filter(m -> unsafe.stream().noneMatch(u -> matches(m, u))).toList();
        this.tokensPerMinute = cfg == null ? 0 : cfg.tokensPerMinute();
    }

    private static boolean matches(String model, String pattern) {
        return model.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
    }

    /** The models allowed for this task, best first. */
    public List<String> chain(Task task) {
        return chain(task, false);
    }

    public List<String> chain(Task task, boolean plain) {
        return task == Task.NUMERIC ? numericChain : plain ? plainChain : chain;
    }

    /** The primary model, used for wire formats that need a name before any call is made. */
    public String primary() {
        return chain.isEmpty() ? "" : chain.get(0);
    }

    /**
     * Runs {@code attempt} on the first model that can take it.
     *
     * @param estimatedTokens the call's expected prompt plus completion tokens
     * @throws AllBusy when no allowed model can take the call within the wait limit
     */
    public <T> T call(Task task, int estimatedTokens, Attempt<T> attempt) {
        return call(task, false, estimatedTokens, attempt);
    }

    public <T> T call(Task task, boolean plain, int estimatedTokens, Attempt<T> attempt) {
        long started = now.getAsLong();
        long waitedTotal = 0;
        long soonest = Long.MAX_VALUE;
        RuntimeException lastFailure = null;
        List<String> models = chain(task, plain);
        for (int i = 0; i < models.size(); i++) {
            String model = models.get(i);
            Budget budget = budget(model);
            boolean retried = false;
            while (true) {
                long wait = budget.waitFor(estimatedTokens, now.getAsLong());
                if (wait > 0 && (plainModels.contains(model) || wait > MAX_WAIT_MS - waitedTotal)) {
                    soonest = Math.min(soonest, wait);
                    log.info("llm_route model={} skipped wait_ms={}", model, wait);
                    break;
                }
                if (wait > 0) {
                    sleep(wait);
                    waitedTotal += wait;
                }
                Slot slot = new Slot(model, budget, budget.reserve(estimatedTokens, now.getAsLong()));
                try {
                    return attempt.call(slot);
                } catch (RateLimited e) {
                    slot.release();
                    long cool = cooldownMs(e.headers);
                    budget.cool(now.getAsLong() + cool);
                    soonest = Math.min(soonest, cool);
                    log.warn("llm_route model={} rate_limited cooldown_ms={}", model, cool);
                    if (retried || cool > MAX_WAIT_MS - waitedTotal) break;
                    retried = true;
                } catch (TryNext e) {
                    slot.release();
                    lastFailure = e.failure();
                    if (plainModels.contains(model)) budget.cool(now.getAsLong() + e.cooldownMs());
                    log.warn("llm_route model={} failed; trying the next", model);
                    if (now.getAsLong() - started > SWITCH_DEADLINE_MS) throw lastFailure;
                    break;
                } catch (RuntimeException e) {
                    slot.release();
                    throw e;
                }
            }
        }
        if (lastFailure != null && soonest == Long.MAX_VALUE) throw lastFailure;
        throw new AllBusy(soonest == Long.MAX_VALUE ? 0 : soonest);
    }

    private void sleep(long ms) {
        try {
            sleeper.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Budget budget(String model) {
        return budgets.computeIfAbsent(model, m -> new Budget(plainModels.contains(m) ? plainTokensPerMinute : tokensPerMinute));
    }

    /** Tokens used by this model in the last minute, for tests and logs. */
    long cooldownRemaining(String model) {
        return budget(model).waitFor(0, now.getAsLong());
    }

    /** Retry-After in seconds, else the sooner of the reset headers, else a short default. */
    static long cooldownMs(HttpHeaders headers) {
        if (headers == null) return DEFAULT_COOLDOWN_MS;
        String retry = headers.getFirst("retry-after");
        if (retry != null) {
            try {
                return Math.max(0, Math.round(Double.parseDouble(retry.strip()) * 1000));
            } catch (NumberFormatException e) {
                // a date form; fall through to the reset headers
            }
        }
        long tokens = duration(headers.getFirst("x-ratelimit-reset-tokens"));
        long requests = duration(headers.getFirst("x-ratelimit-reset-requests"));
        long best = tokens >= 0 && requests >= 0 ? Math.min(tokens, requests) : Math.max(tokens, requests);
        return best >= 0 ? best : DEFAULT_COOLDOWN_MS;
    }

    /** "7.66s", "2m59.5s", "1h3m", "500ms" as milliseconds; -1 when absent or unreadable. */
    static long duration(String s) {
        if (s == null || s.isBlank()) return -1;
        Matcher m = DURATION.matcher(s.strip());
        Duration total = Duration.ZERO;
        boolean any = false;
        while (m.find()) {
            any = true;
            double v = Double.parseDouble(m.group(1));
            total = total.plusMillis(Math.round(v * switch (m.group(2)) {
                case "ms" -> 1;
                case "s" -> 1_000;
                case "m" -> 60_000;
                default -> 3_600_000;
            }));
        }
        return any ? total.toMillis() : -1;
    }

    private static long number(String s) {
        if (s == null || s.isBlank()) return -1;
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
