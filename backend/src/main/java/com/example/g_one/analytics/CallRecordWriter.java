package com.example.g_one.analytics;

import com.example.g_one.agent.tools.memory.ForgetMeTool;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens to the console events of every call, keeps what a record may need while the call runs, and at
 * {@code call_ended} stores a {@link CallRecord} for it ({@link CallAccumulator}). Every ended call is recorded; the
 * parties' consent decides only the conversation-derived fields, and a call without the farmer's yes is anonymous (no
 * caller key). The synthetic sample replay ({@code replay-} call ids) is never stored.
 *
 * <p>No call is dropped. Beyond {@link #MAX_OPEN_CALLS} open calls the oldest is recorded early as an incomplete call
 * (end reason {@code evicted}). A record that fails to save stays in a bounded queue and is retried with backoff, on
 * every later call end and on a periodic tick; it is logged loudly while it stays unsaved.
 *
 * <p>A forget (the caller's own "forget me", or the console's) is applied at once: the store deletes the caller's
 * records and keeps a tombstone, and open or queued calls of that caller lose their caller key, stated name, memory
 * snapshot and farmer timeline.
 */
public final class CallRecordWriter implements AutoCloseable {
    /** Open calls kept before the oldest is recorded early as incomplete. */
    static final int MAX_OPEN_CALLS = 500;
    /** Unsaved records kept for retry; beyond this the oldest is dropped with an error. */
    static final int MAX_PENDING = 1_000;
    /** Waits before the first retries; after them, every {@link #SLOW_RETRY_MS}. */
    static final long[] BACKOFF_MS = {1_000, 5_000, 30_000};
    static final long SLOW_RETRY_MS = 60_000;
    static final String EVICTED = "evicted";

    private static final Logger log = LoggerFactory.getLogger(CallRecordWriter.class);

    private final CallRecordStore store;
    private final ObjectMapper json;
    private final Clock clock;
    private final DealVocabulary vocab;
    private final int maxOpen;
    private final Map<CallId, CallAccumulator> open = new LinkedHashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    /** Forgets the store could not take yet: caller key to forget time. */
    private final Map<String, Long> pendingForgets = new LinkedHashMap<>();
    private final ConsoleBus.Subscription subscription;
    private final ScheduledExecutorService retries;

    /** A record waiting to be saved again. */
    private static final class Pending {
        CallRecord record;
        int attempts;
        long nextAtMs;

        Pending(CallRecord record, int attempts, long nextAtMs) {
            this.record = record;
            this.attempts = attempts;
            this.nextAtMs = nextAtMs;
        }
    }

    public CallRecordWriter(ConsoleBus bus, CallRecordStore store, ObjectMapper json, Clock clock) {
        this(bus, store, json, clock, DealVocabulary.bundled(json));
    }

    public CallRecordWriter(ConsoleBus bus, CallRecordStore store, ObjectMapper json, Clock clock, DealVocabulary vocab) {
        this(bus, store, json, clock, vocab, MAX_OPEN_CALLS, true);
    }

    /** @param scheduleRetries false for tests that drive {@link #retryDue()} themselves */
    CallRecordWriter(ConsoleBus bus, CallRecordStore store, ObjectMapper json, Clock clock, DealVocabulary vocab,
                     int maxOpen, boolean scheduleRetries) {
        this.store = store;
        this.json = json;
        this.clock = clock;
        this.vocab = vocab;
        this.maxOpen = maxOpen;
        try {
            int scrubbed = store.scrubLegacy(r -> RecordScrubber.scrub(r, vocab, json));
            if (scrubbed > 0) log.info("Call records: scrubbed {} records stored before the privacy review", scrubbed);
        } catch (RuntimeException e) {
            log.error("Could not scrub call records stored before the privacy review", e);
        }
        if (scheduleRetries) {
            retries = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "call-record-retry");
                t.setDaemon(true);
                return t;
            });
            retries.scheduleWithFixedDelay(this::retryDueQuietly, 1, 1, TimeUnit.SECONDS);
        } else {
            retries = null;
        }
        this.subscription = bus.subscribe(this::accept);
    }

    synchronized void accept(ConsoleEvent event) {
        if (event.callId().value().startsWith("replay-")) {
            return;
        }
        if (event instanceof ConsoleEvent.CallStarted started) {
            open.put(started.callId(), new CallAccumulator(started));
            evictBeyondCap();
            return;
        }
        CallAccumulator call = open.get(event.callId());
        if (call == null) {
            return;
        }
        call.accept(event, json);
        if (event instanceof ConsoleEvent.ToolCalled t && t.ok() && t.tool().equals(ForgetMeTool.NAME)) {
            // The farmer asked to be forgotten: their earlier records go now, and open calls of theirs lose the key.
            call.farmerKey().ifPresent(key -> forgetQuietly(key, t.atMs()));
        }
        if (event instanceof ConsoleEvent.CallEnded) {
            open.remove(event.callId());
            // Earlier unsaved records first, so a store that has recovered takes them in order.
            retryAll();
            finish(call, null);
        }
    }

    /**
     * Forgets a caller key now (the console's forget): the store deletes its records and keeps a tombstone; open and
     * queued calls of that caller lose everything linked to it. Throws when the store could not take the forget, after
     * stripping what is held in memory and queuing the forget for retry.
     */
    public synchronized void forget(String farmerKey, long atMs) {
        stripHeld(farmerKey, atMs);
        try {
            store.forget(farmerKey, atMs);
        } catch (RuntimeException e) {
            pendingForgets.merge(farmerKey, atMs, Math::max);
            log.error("Could not store a forget for call records; it is queued for retry", e);
            throw e;
        }
    }

    /** {@link #forget} at the writer's clock time. */
    public void forgetNow(String farmerKey) {
        forget(farmerKey, clock.millis());
    }

    private void forgetQuietly(String farmerKey, long atMs) {
        try {
            forget(farmerKey, atMs);
        } catch (RuntimeException e) {
            // Logged and queued in forget().
        }
    }

    /** Open calls and queued records of this caller that began at or before the forget lose the linked fields. */
    private void stripHeld(String farmerKey, long atMs) {
        for (CallAccumulator call : open.values()) {
            if (call.farmerKey().filter(farmerKey::equals).isPresent() && call.startedMs() <= atMs) {
                call.withdrawKey();
            }
        }
        for (Pending p : pending) {
            if (farmerKey.equals(p.record.farmerKey()) && p.record.startedMs() <= atMs) {
                p.record = p.record.withdrawn();
            }
        }
    }

    private void finish(CallAccumulator call, String endReasonIfOpen) {
        CallRecord record;
        try {
            record = call.toRecord(json, vocab, clock.millis(), endReasonIfOpen);
        } catch (RuntimeException e) {
            log.error("Could not build the record of call {}", call.callId(), e);
            return;
        }
        try {
            store.save(record);
            log.info("Call {} ended: record stored (consent: {}{})", call.callId(), record.consentScope(),
                    record.termsOnly() ? ", terms only" : record.farmerKey() == null ? ", anonymous" : "");
        } catch (RuntimeException e) {
            log.warn("Could not store the record of call {}; queued for retry", call.callId(), e);
            enqueue(new Pending(record, 1, clock.millis() + BACKOFF_MS[0]));
        }
    }

    private void enqueue(Pending p) {
        if (pending.size() >= MAX_PENDING) {
            Pending dropped = pending.remove(0);
            log.error("Call record queue full: dropped the unsaved record of call {} after {} attempts",
                    dropped.record.callId(), dropped.attempts);
        }
        pending.add(p);
    }

    /** Records the oldest open calls early, as incomplete, while more than the cap are open. Never drops one. */
    private void evictBeyondCap() {
        Iterator<Map.Entry<CallId, CallAccumulator>> it = open.entrySet().iterator();
        while (open.size() > maxOpen && it.hasNext()) {
            CallAccumulator oldest = it.next().getValue();
            it.remove();
            log.warn("More than {} calls open: recording call {} early as incomplete", maxOpen, oldest.callId());
            finish(oldest, EVICTED);
        }
    }

    /** Retries the queued records and forgets whose wait is over. */
    synchronized void retryDue() {
        retry(false);
    }

    /** Retries every queued record and forget now (after a call ends). */
    synchronized void retryAll() {
        retry(true);
    }

    private void retryDueQuietly() {
        try {
            retryDue();
        } catch (RuntimeException e) {
            log.error("Call record retry failed", e);
        }
    }

    private void retry(boolean all) {
        for (Iterator<Map.Entry<String, Long>> it = pendingForgets.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> f = it.next();
            try {
                store.forget(f.getKey(), f.getValue());
                it.remove();
            } catch (RuntimeException e) {
                log.error("A queued forget for call records still cannot be stored", e);
            }
        }
        long now = clock.millis();
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (!all && now < p.nextAtMs) continue;
            try {
                store.save(p.record);
                it.remove();
                log.info("Call {}: record stored after {} failed attempts", p.record.callId(), p.attempts);
            } catch (RuntimeException e) {
                p.attempts++;
                long wait = p.attempts <= BACKOFF_MS.length ? BACKOFF_MS[p.attempts - 1] : SLOW_RETRY_MS;
                p.nextAtMs = now + wait;
                if (p.attempts >= BACKOFF_MS.length) {
                    log.error("Call {}: record still unsaved after {} attempts; kept queued, retrying", p.record.callId(),
                            p.attempts, e);
                }
            }
        }
    }

    /** Records waiting to be saved. */
    public synchronized int pendingRecords() {
        return pending.size();
    }

    synchronized int openCalls() {
        return open.size();
    }

    @Override
    public void close() {
        subscription.close();
        if (retries != null) retries.shutdownNow();
        synchronized (this) {
            // Calls still open at shutdown are recorded as they stand, rather than lost.
            for (CallAccumulator call : List.copyOf(open.values())) {
                finish(call, "shutdown");
            }
            open.clear();
            retry(true);
            for (Pending p : pending) {
                log.error("Shutting down with the record of call {} unsaved after {} attempts", p.record.callId(),
                        p.attempts);
            }
        }
    }
}
