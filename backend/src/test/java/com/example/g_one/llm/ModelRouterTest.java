package com.example.g_one.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.g_one.GOneProperties;
import com.example.g_one.agent.llm.ChatClient;
import com.example.g_one.agent.llm.ChatException;
import com.example.g_one.agent.llm.ChatMessage;
import com.example.g_one.agent.llm.ChatRequest;
import com.example.g_one.agent.llm.ToolSpec;
import com.example.g_one.speech.LlmClient;
import com.example.g_one.speech.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** Routing, cooldowns and the token budget with a hand-moved clock, and both callers against a fake server. No network. */
class ModelRouterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PRIMARY = "openai/gpt-oss-120b";
    private static final String SECOND = "openai/gpt-oss-20b";
    private static final String QWEN = "qwen/qwen3.8-27b";

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final List<Long> sleeps = new ArrayList<>();
    private HttpServer server;
    private final List<String> asked = new CopyOnWriteArrayList<>();
    /** Models the fake answers 429 for, with their Retry-After seconds. */
    private final Map<String, String> limited = new ConcurrentHashMap<>();

    private ModelRouter router(long tpm) {
        return new ModelRouter(new GOneProperties.Llm("http://x", "k", PRIMARY, 5, "low", List.of(SECOND, QWEN), List.of("qwen"), tpm),
                clock::get, ms -> {
                    sleeps.add(ms);
                    clock.addAndGet(ms);
                });
    }

    private static HttpHeaders retryAfter(String seconds) {
        HttpHeaders h = new HttpHeaders();
        h.set("retry-after", seconds);
        return h;
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", x -> {
            JsonNode req = JSON.readTree(x.getRequestBody().readAllBytes());
            String model = req.path("model").asText();
            asked.add(model);
            String retry = limited.get(model);
            byte[] body;
            if (retry != null) {
                x.getResponseHeaders().set("Retry-After", retry);
                body = "{\"error\":{\"code\":\"rate_limit_exceeded\"}}".getBytes(StandardCharsets.UTF_8);
                x.sendResponseHeaders(429, body.length);
            } else {
                body = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"answer\\\":\\\"" + model
                        + "\\\"}\"}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120}}")
                        .getBytes(StandardCharsets.UTF_8);
                x.sendResponseHeaders(200, body.length);
            }
            x.getResponseBody().write(body);
            x.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private GOneProperties props() {
        return new GOneProperties(List.of("a:A:v", "b:B:v"), "", null, null,
                new GOneProperties.Llm("http://127.0.0.1:" + server.getAddress().getPort(), "k", PRIMARY, 5, "low",
                        List.of(SECOND, QWEN), List.of("qwen"), 0));
    }

    private static final ChatRequest REQUEST = ChatRequest.of(List.of(ChatMessage.user("hello")), List.of());

    @Test
    void mixedBrainRoutesPlainOnlyAndSkipsItsBudget() {
        GOneProperties.Llm cfg = new GOneProperties.Llm("http://gemini", "gemini-key", "gemini", 5, "",
                List.of(), List.of(), 0, "", "", List.of(), "http://plain", "plain-key",
                List.of("fast"), 300);
        ModelRouter r = new ModelRouter(cfg, clock::get, ms -> sleeps.add(ms));
        assertThat(r.chain(ModelRouter.Task.CHAT, true)).containsExactly("fast", "gemini");
        assertThat(r.chain(ModelRouter.Task.CHAT, false)).containsExactly("gemini");
        assertThat(r.chain(ModelRouter.Task.NUMERIC, true)).containsExactly("gemini");
        assertThat(r.<String>call(ModelRouter.Task.CHAT, true, 250, s -> s.model())).isEqualTo("fast");
        assertThat(r.<String>call(ModelRouter.Task.CHAT, true, 250, s -> s.model())).isEqualTo("gemini");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void mixedEndpointsKeepKeysSeparateAndFallBackAfterFailures() throws IOException {
        HttpServer plain = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> plainAuth = new CopyOnWriteArrayList<>();
        List<String> primaryAuth = new CopyOnWriteArrayList<>();
        List<Integer> statuses = new java.util.concurrent.CopyOnWriteArrayList<>(List.of(200, 429, 500, 201, 202));
        plain.createContext("/chat/completions", x -> {
            plainAuth.add(x.getRequestHeaders().getFirst("Authorization"));
            int status = statuses.remove(0);
            if (status == 202) {
                try { Thread.sleep(1_500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                status = 200;
            }
            String value = status == 201 ? "{\"choices\":[{\"message\":{\"content\":\"\"}}]}" : status == 200 ? """
                    {"choices":[{"message":{"content":"plain"}}]}
                    """ : "{}";
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(status, bytes.length);
            try { x.getResponseBody().write(bytes); } catch (IOException ignored) { }
            x.close();
        });
        plain.start();
        try {
            server.removeContext("/chat/completions");
            server.createContext("/chat/completions", x -> {
                primaryAuth.add(x.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"gemini\"}}]}".getBytes(StandardCharsets.UTF_8);
                x.sendResponseHeaders(200, bytes.length);
                x.getResponseBody().write(bytes);
                x.close();
            });
            GOneProperties.Llm cfg = new GOneProperties.Llm("http://127.0.0.1:" + server.getAddress().getPort(),
                    "gemini-key", "gemini", 1, "", List.of(), List.of(), 0, "", "", List.of(),
                    "http://127.0.0.1:" + plain.getAddress().getPort(), "plain-key", List.of("fast"), 8000);
            GOneProperties props = new GOneProperties(List.of(), "", null, null, cfg);
            ChatClient chat = new ChatClient(props, JSON);
            assertThat(chat.complete(REQUEST).usage().model()).isEqualTo("fast");
            assertThat(chat.complete(REQUEST.numeric()).usage().model()).isEqualTo("gemini");
            assertThat(chat.complete(ChatRequest.of(REQUEST.messages(),
                    List.of(new ToolSpec("example", "synthetic", null)))).usage().model()).isEqualTo("gemini");
            assertThat(chat.complete(ChatRequest.of(List.of(ChatMessage.user("hello"),
                    ChatMessage.tool("call_1", "done")), List.of())).usage().model()).isEqualTo("gemini");
            assertThat(chat.complete(new ChatRequest(REQUEST.messages(), List.of(), 0.3, 300,
                    ModelRouter.Task.CHAT)).usage().model()).isEqualTo("gemini");
            assertThat(chat.complete(REQUEST).usage().model()).isEqualTo("gemini");
            assertThat(plainAuth).containsOnly("Bearer plain-key").hasSize(2);
            assertThat(primaryAuth).containsOnly("Bearer gemini-key").hasSize(5);
            for (int i = 0; i < 3; i++) {
                ChatClient fresh = new ChatClient(props, JSON);
                assertThat(fresh.complete(REQUEST).usage().model()).isEqualTo("gemini");
            }
            assertThat(plainAuth).containsOnly("Bearer plain-key").hasSize(5);
            assertThat(primaryAuth).containsOnly("Bearer gemini-key").hasSize(8);
        } finally {
            plain.stop(0);
        }
    }

    @Test
    void aRateLimitedModelFallsToTheNextAndIsNotAskedDuringItsCooldown() {
        ModelRouter r = router(0);
        List<String> tried = new ArrayList<>();
        ModelRouter.Attempt<String> firstRefuses = s -> {
            tried.add(s.model());
            if (s.model().equals(PRIMARY)) throw new ModelRouter.RateLimited(retryAfter("30"));
            return s.model();
        };
        assertThat(r.<String>call(ModelRouter.Task.CHAT, 100, firstRefuses)).isEqualTo(SECOND);
        tried.clear();
        assertThat(r.<String>call(ModelRouter.Task.CHAT, 100, firstRefuses)).isEqualTo(SECOND);
        assertThat(tried).as("still cooling down").containsExactly(SECOND);

        clock.addAndGet(31_000);
        assertThat(r.<String>call(ModelRouter.Task.CHAT, 100, s -> s.model())).isEqualTo(PRIMARY);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aResetThatIsNearIsWaitedForWithinTheLimit() {
        ModelRouter r = router(0);
        List<String> tried = new ArrayList<>();
        String answer = r.call(ModelRouter.Task.CHAT, 100, s -> {
            tried.add(s.model());
            if (tried.size() == 1) throw new ModelRouter.RateLimited(retryAfter("1"));
            return s.model();
        });
        assertThat(answer).isEqualTo(PRIMARY);
        assertThat(sleeps).containsExactly(1_000L);
    }

    @Test
    void theBudgetDivertsABurstBeforeTheProviderRefusesIt() {
        ModelRouter r = router(1_000);
        List<String> tried = new ArrayList<>();
        ModelRouter.Attempt<String> spend600 = s -> {
            tried.add(s.model());
            s.spent(600);
            return s.model();
        };
        assertThat(r.<String>call(ModelRouter.Task.NUMERIC, 600, spend600)).isEqualTo(PRIMARY);
        assertThat(r.<String>call(ModelRouter.Task.NUMERIC, 600, spend600)).as("primary would go over").isEqualTo(SECOND);
        assertThatThrownBy(() -> r.call(ModelRouter.Task.NUMERIC, 600, spend600))
                .isInstanceOf(ModelRouter.AllBusy.class);
        assertThat(tried).as("nothing was sent that would be refused").hasSize(2);

        clock.addAndGet(61_000);
        assertThat(r.<String>call(ModelRouter.Task.NUMERIC, 600, spend600)).isEqualTo(PRIMARY);
    }

    @Test
    void aWaitBeyondTheLimitSwitchesInsteadOfSleeping() {
        ModelRouter r = router(1_000);
        r.call(ModelRouter.Task.CHAT, 900, s -> {
            s.spent(900);
            return "";
        });
        clock.addAndGet(58_000);
        assertThat(r.<String>call(ModelRouter.Task.CHAT, 500, s -> s.model())).as("2 s away is too long").isEqualTo(SECOND);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void theProvidersRemainingTokensHoldBackAModel() {
        ModelRouter r = router(8_000);
        HttpHeaders h = new HttpHeaders();
        h.set("x-ratelimit-remaining-tokens", "50");
        r.call(ModelRouter.Task.CHAT, 100, s -> {
            s.observe(h);
            return "";
        });
        assertThat(r.<String>call(ModelRouter.Task.CHAT, 900, s -> s.model())).isEqualTo(SECOND);
    }

    @Test
    void numericWorkNeverReachesAnUnsafeModel() {
        ModelRouter r = router(0);
        assertThat(r.chain(ModelRouter.Task.NUMERIC)).containsExactly(PRIMARY, SECOND);
        assertThat(r.chain(ModelRouter.Task.CHAT)).containsExactly(PRIMARY, SECOND, QWEN);
        List<String> tried = new ArrayList<>();
        assertThatThrownBy(() -> r.call(ModelRouter.Task.NUMERIC, 100, s -> {
            tried.add(s.model());
            throw new ModelRouter.RateLimited(retryAfter("40"));
        })).isInstanceOf(ModelRouter.AllBusy.class);
        assertThat(tried).containsExactly(PRIMARY, SECOND);
    }

    @Test
    void aServerErrorMovesOnAndTheLastFailureSurfacesWhenAllFail() {
        ModelRouter r = router(0);
        assertThat(r.<String>call(ModelRouter.Task.CHAT, 100, s -> {
            if (!s.model().equals(QWEN)) throw new ModelRouter.TryNext(new IllegalStateException("boom"));
            return s.model();
        })).isEqualTo(QWEN);
        assertThatThrownBy(() -> r.call(ModelRouter.Task.NUMERIC, 100, s -> {
            throw new ModelRouter.TryNext(new IllegalStateException("boom"));
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void durationsAndRetryHeadersParse() {
        assertThat(ModelRouter.duration("7.66s")).isEqualTo(7_660);
        assertThat(ModelRouter.duration("2m59.5s")).isEqualTo(179_500);
        assertThat(ModelRouter.duration("1h3m")).isEqualTo(3_780_000);
        assertThat(ModelRouter.duration("500ms")).isEqualTo(500);
        assertThat(ModelRouter.duration("soon")).isEqualTo(-1);
        HttpHeaders h = new HttpHeaders();
        h.set("x-ratelimit-reset-tokens", "12s");
        assertThat(ModelRouter.cooldownMs(h)).isEqualTo(12_000);
        h.set("retry-after", "3");
        assertThat(ModelRouter.cooldownMs(h)).isEqualTo(3_000);
        assertThat(ModelRouter.cooldownMs(null)).isPositive();
    }

    @Test
    void bothCallersShareTheChainOnTheWire() {
        limited.put(PRIMARY, "20");
        ChatClient chat = new ChatClient(props(), JSON);
        assertThat(chat.complete(REQUEST).usage().model()).isEqualTo(SECOND);
        assertThat(chat.complete(REQUEST).usage().totalTokens()).isEqualTo(120);
        assertThat(asked).containsExactly(PRIMARY, SECOND, SECOND);

        asked.clear();
        limited.clear();
        LlmClient json = new LlmClient(props(), JSON);
        limited.put(PRIMARY, "20");
        limited.put(SECOND, "20");
        assertThatThrownBy(() -> json.askJson("gist", "system", "user")).isInstanceOf(UpstreamException.class);
        assertThat(asked).as("JSON work is numeric").containsExactly(PRIMARY, SECOND);
    }

    @Test
    void aChatRequestMarkedNumericNeverReachesQwenEvenWhenEverythingElseIsLimited() {
        limited.put(PRIMARY, "20");
        limited.put(SECOND, "20");
        ChatClient chat = new ChatClient(props(), JSON);
        assertThatThrownBy(() -> chat.complete(REQUEST.numeric()))
                .isInstanceOfSatisfying(ChatException.class, e -> assertThat(e.kind()).isEqualTo(ChatException.Kind.RATE_LIMITED));
        assertThat(asked).containsExactly(PRIMARY, SECOND);
        asked.clear();
        assertThat(chat.complete(REQUEST).usage().model()).isEqualTo(QWEN);
    }

    @Test
    void anExtraEndpointServesItsModelsLastWithItsOwnKeyAndNoReasoningField() throws Exception {
        try (com.example.g_one.fakes.FakeLlm primary = new com.example.g_one.fakes.FakeLlm();
             com.example.g_one.fakes.FakeLlm extra = new com.example.g_one.fakes.FakeLlm()) {
            primary.failures.set(9);
            extra.replyText("from the extra endpoint");
            GOneProperties props = new GOneProperties(List.of("a:A:v", "b:B:v"), "", null, null,
                    new GOneProperties.Llm(primary.baseUrl(), "p", "primary-model", 5, "low", List.of(), List.of(), 0,
                            extra.baseUrl(), "e", List.of("sarvam-105b-conversations")));

            ChatClient chat = new ChatClient(props, JSON);

            assertThat(chat.complete(REQUEST.numeric()).content()).isEqualTo("from the extra endpoint");
            assertThat(extra.requests()).singleElement().satisfies(r -> {
                assertThat(r.path("model").asText()).isEqualTo("sarvam-105b-conversations");
                assertThat(r.has("reasoning_effort")).isFalse();
            });
            // A non-Groq primary never gets reasoning fields, even with an effort configured.
            assertThat(primary.requests().get(0).has("reasoning_effort")).isFalse();
        }
    }
}
