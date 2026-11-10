package com.example.g_one.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.g_one.GOneProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ChatClient against a scripted fake /chat/completions server speaking the OpenAI wire format. No network. */
class ChatClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "test-key-not-a-secret";

    private record Scripted(int status, String body, long delayMillis) {}

    private HttpServer server;
    private final Deque<Scripted> script = new ConcurrentLinkedDeque<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> auth = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", x -> {
            requests.add(JSON.readTree(x.getRequestBody().readAllBytes()));
            auth.add(x.getRequestHeaders().getFirst("Authorization"));
            Scripted s = script.poll();
            if (s == null) s = new Scripted(500, "{\"error\":\"no scripted answer\"}", 0);
            try {
                Thread.sleep(s.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = s.body().getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            try {
                x.sendResponseHeaders(s.status(), body.length);
                x.getResponseBody().write(body);
            } catch (IOException clientGone) {
                // the client timed out first
            }
            x.close();
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ChatClient client(int timeoutSeconds) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ChatClient(new GOneProperties(List.of("a:A:v", "b:B:v"), "", null, null,
                new GOneProperties.Llm(base, KEY, "test-model", timeoutSeconds)), JSON);
    }

    private void answer(String json) {
        script.add(new Scripted(200, json, 0));
    }

    private static final JsonNode MANDI_PARAMS = parse("""
            {"type":"object","properties":{"commodity":{"type":"string"},"market":{"type":"string"}},
             "required":["commodity"]}""");

    /** The optional market may be null on the wire. */
    private static final JsonNode MANDI_PARAMS_ON_THE_WIRE = parse("""
            {"type":"object","properties":{"commodity":{"type":"string"},"market":{"type":["string","null"]}},
             "required":["commodity"]}""");

    private static JsonNode parse(String s) {
        try {
            return JSON.readTree(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void toolCallRoundTripSendsToolsThenReplaysTheCallAndItsResult() {
        answer("""
                {"choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                  "tool_calls":[{"id":"call_abc","type":"function",
                    "function":{"name":"get_mandi_price","arguments":"{\\"commodity\\":\\"pyaz\\"}"}}]}}]}""");
        answer("""
                {"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant",
                  "content":"AGMARKNET ने चार नवंबर को लासलगाँव में लाल प्याज़ का भाव चार हज़ार पाँच सौ रुपये प्रति क्विंटल बताया था।"}}]}""");
        ChatClient chat = client(5);
        List<ToolSpec> tools = List.of(new ToolSpec("get_mandi_price", "Dated mandi reference.", MANDI_PARAMS));
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("You are G-One."), ChatMessage.user("प्याज़ का भाव क्या है?")));

        ChatReply first = chat.complete(ChatRequest.of(messages, tools));
        assertThat(first.wantsTools()).isTrue();
        assertThat(first.content()).isEmpty();
        assertThat(first.finishReason()).isEqualTo("tool_calls");
        ToolCall call = first.toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_abc");
        assertThat(call.name()).isEqualTo("get_mandi_price");
        assertThat(call.arguments(JSON).path("commodity").asText()).isEqualTo("pyaz");

        messages.add(ChatMessage.assistant(first));
        messages.add(ChatMessage.tool(call.id(), "AGMARKNET listed four thousand five hundred rupees per quintal ..."));
        ChatReply second = chat.complete(ChatRequest.of(messages, tools));
        assertThat(second.wantsTools()).isFalse();
        assertThat(second.content()).startsWith("AGMARKNET ने").contains("प्याज़");

        // First request: model, auth, tools offered as functions, Hindi survives as UTF-8.
        JsonNode r1 = requests.get(0);
        assertThat(auth).containsOnly("Bearer " + KEY);
        assertThat(r1.path("model").asText()).isEqualTo("test-model");
        assertThat(r1.path("tool_choice").asText()).isEqualTo("auto");
        assertThat(r1.path("tools").get(0).path("type").asText()).isEqualTo("function");
        assertThat(r1.path("tools").get(0).path("function").path("name").asText()).isEqualTo("get_mandi_price");
        assertThat(r1.path("tools").get(0).path("function").path("parameters")).isEqualTo(MANDI_PARAMS_ON_THE_WIRE);
        assertThat(r1.path("messages").get(1).path("content").asText()).isEqualTo("प्याज़ का भाव क्या है?");

        // Second request replays the assistant's tool call and answers it by id.
        JsonNode msgs = requests.get(1).path("messages");
        assertThat(msgs).hasSize(4);
        JsonNode replayed = msgs.get(2);
        assertThat(replayed.path("role").asText()).isEqualTo("assistant");
        assertThat(replayed.path("content").isNull()).isTrue();
        assertThat(replayed.path("tool_calls").get(0).path("id").asText()).isEqualTo("call_abc");
        assertThat(replayed.path("tool_calls").get(0).path("function").path("arguments").asText())
                .isEqualTo("{\"commodity\":\"pyaz\"}");
        JsonNode toolMsg = msgs.get(3);
        assertThat(toolMsg.path("role").asText()).isEqualTo("tool");
        assertThat(toolMsg.path("tool_call_id").asText()).isEqualTo("call_abc");
        assertThat(toolMsg.path("content").asText()).startsWith("AGMARKNET listed");
    }

    @Test
    void plainChatSendsNoToolsAndStripsInlineThinking() {
        answer("""
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                  "content":"<think>The farmer wants weather.</think>\\n Namaste, aap kis district se bol rahe hain?"}}]}""");
        ChatReply reply = client(5).complete(ChatRequest.of(List.of(ChatMessage.user("hello")), List.of()));
        assertThat(reply.content()).isEqualTo("Namaste, aap kis district se bol rahe hain?");
        assertThat(requests.get(0).has("tools")).isFalse();
        assertThat(requests.get(0).has("tool_choice")).isFalse();
    }

    @Test
    void argumentsSentAsAnObjectAndMissingIdsAreTolerated() {
        answer("""
                {"choices":[{"message":{"role":"assistant","content":"",
                  "tool_calls":[{"type":"function","function":{"name":"get_weather","arguments":{"district":"Nashik"}}},
                                {"type":"function","function":{"name":"end_call"}}]}}]}""");
        ChatReply reply = client(5).complete(ChatRequest.of(List.of(ChatMessage.user("x")), List.of()));
        assertThat(reply.toolCalls()).extracting(ToolCall::id).containsExactly("call_0", "call_1");
        assertThat(reply.toolCalls().get(0).arguments(JSON).path("district").asText()).isEqualTo("Nashik");
        assertThat(reply.toolCalls().get(1).arguments(JSON).isEmpty()).isTrue();
    }

    @Test
    void malformedToolArgumentsAreAClearError() {
        assertThatThrownBy(() -> new ToolCall("c", "get_weather", "{district: Nashik").arguments(JSON))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> new ToolCall("c", "get_weather", "[1,2]").arguments(JSON))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a JSON object");
    }

    @Test
    void failuresMapToKindsWithoutLeakingTheKey() {
        ChatClient chat = client(5);
        ChatRequest req = ChatRequest.of(List.of(ChatMessage.user("x")), List.of());

        script.add(new Scripted(500, "{\"error\":\"boom\"}", 0));
        assertThatThrownBy(() -> chat.complete(req)).isInstanceOfSatisfying(ChatException.class, e -> {
            assertThat(e.kind()).isEqualTo(ChatException.Kind.HTTP_ERROR);
            assertThat(e.status()).isEqualTo(500);
            assertThat(e.getMessage()).doesNotContain(KEY);
        });

        script.add(new Scripted(429, "{\"error\":\"slow down\"}", 0));
        ChatClient limited = client(5);
        assertThatThrownBy(() -> limited.complete(req)).isInstanceOfSatisfying(ChatException.class,
                e -> assertThat(e.kind()).isEqualTo(ChatException.Kind.RATE_LIMITED));

        for (String bad : List.of("not json", "{}", "{\"choices\":[]}",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"  \"}}]}",
                "{\"choices\":[{\"message\":{\"content\":\"<think>only thinking</think>\"}}]}",
                "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{}\"}}]}}]}")) {
            answer(bad);
            assertThatThrownBy(() -> chat.complete(req)).as(bad).isInstanceOfSatisfying(ChatException.class,
                    e -> assertThat(e.kind()).isEqualTo(ChatException.Kind.BAD_RESPONSE));
        }
    }

    @Test
    void aSlowModelTimesOutAsUnreachable() {
        script.add(new Scripted(200, "{\"choices\":[{\"message\":{\"content\":\"late\"}}]}", 3500));
        long start = System.nanoTime();
        assertThatThrownBy(() -> client(1).complete(ChatRequest.of(List.of(ChatMessage.user("x")), List.of())))
                .isInstanceOfSatisfying(ChatException.class,
                        e -> assertThat(e.kind()).isEqualTo(ChatException.Kind.UNREACHABLE));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void anUnreachableServerAndMissingConfigAreClearErrors() {
        server.stop(0);
        assertThatThrownBy(() -> client(2).complete(ChatRequest.of(List.of(ChatMessage.user("x")), List.of())))
                .isInstanceOfSatisfying(ChatException.class,
                        e -> assertThat(e.kind()).isEqualTo(ChatException.Kind.UNREACHABLE));

        ChatClient off = new ChatClient(new GOneProperties(List.of("a:A:v", "b:B:v"), "", null, null,
                new GOneProperties.Llm("", "", "", 5)), JSON);
        assertThat(off.configured()).isFalse();
        assertThatThrownBy(() -> off.complete(ChatRequest.of(List.of(ChatMessage.user("x")), List.of())))
                .isInstanceOfSatisfying(ChatException.class,
                        e -> assertThat(e.kind()).isEqualTo(ChatException.Kind.NOT_CONFIGURED));
    }

    private ChatClient clientWithEffort(String effort) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ChatClient(new GOneProperties(List.of("a:A:v", "b:B:v"), "", null, null,
                new GOneProperties.Llm(base, KEY, "test-model", 5, effort)), JSON);
    }

    private static final String OK = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}";
    private static final String JSON_FAILED =
            "{\"error\":{\"message\":\"Failed to generate JSON\",\"code\":\"json_validate_failed\"}}";
    private static final ChatRequest SMALL =
            new ChatRequest(List.of(ChatMessage.user("x")), List.of(), 0.1, 120);

    @Test
    void aConfiguredReasoningEffortIsNeverSentToANonGroqEndpoint() {
        answer(OK);
        clientWithEffort("low").complete(SMALL);
        assertThat(requests.get(0).has("reasoning_effort")).isFalse();

        for (String off : new String[] {"", "  ", null}) {
            requests.clear();
            answer(OK);
            clientWithEffort(off).complete(SMALL);
            assertThat(requests.get(0).has("reasoning_effort")).isFalse();
        }
    }

    @Test
    void jsonValidateFailedIsRetriedOnceWithThreeTimesTheTokens() {
        script.add(new Scripted(400, JSON_FAILED, 0));
        answer(OK);
        assertThat(client(5).complete(SMALL).content()).isEqualTo("{}");
        assertThat(requests).extracting(r -> r.path("max_tokens").asInt()).containsExactly(120, 360);
    }

    @Test
    void optionalToolArgumentsMayBeNullButRequiredOnesMayNot() {
        answer(OK);
        client(5).complete(ChatRequest.of(List.of(ChatMessage.user("x")),
                List.of(new ToolSpec("get_mandi_price", "Dated mandi reference.", MANDI_PARAMS))));
        JsonNode props = requests.get(0).at("/tools/0/function/parameters/properties");
        assertThat(props.path("market").path("type").toString()).isEqualTo("[\"string\",\"null\"]");
        assertThat(props.path("commodity").path("type").asText()).isEqualTo("string");
    }

    @Test
    void aMalformedToolCallIsRetriedOnce() {
        script.add(new Scripted(400, "{\"error\":{\"code\":\"tool_use_failed\"}}", 0));
        answer(OK);
        assertThat(client(5).complete(SMALL).content()).isEqualTo("{}");
        assertThat(requests).hasSize(2);
    }

    @Test
    void anEmptyAnswerIsRetriedOnceAndATruncatedOneKeepsTheFirstIfTheRetryFails() {
        answer("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"\"}}]}");
        answer(OK);
        assertThat(client(5).complete(SMALL).content()).isEqualTo("{}");
        assertThat(requests).hasSize(2);

        requests.clear();
        answer("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\\\"a\\\":\"}}]}");
        script.add(new Scripted(500, "{}", 0));
        assertThat(client(5).complete(SMALL).content()).isEqualTo("{\"a\":");
        assertThat(requests).as("a cut-off answer with content is not worth a second call").hasSize(1);
        script.clear();
    }

    @Test
    void aRetryThatAlsoFailsStopsAfterTwoCallsAndOtherBadRequestsAreNotRetried() {
        script.add(new Scripted(400, JSON_FAILED, 0));
        script.add(new Scripted(400, JSON_FAILED, 0));
        script.add(new Scripted(200, OK, 0));
        assertThatThrownBy(() -> client(5).complete(SMALL)).isInstanceOfSatisfying(ChatException.class,
                e -> assertThat(e.status()).isEqualTo(400));
        assertThat(requests).hasSize(2);

        script.clear();
        requests.clear();
        script.add(new Scripted(400, "{\"error\":{\"code\":\"model_not_found\"}}", 0));
        assertThatThrownBy(() -> client(5).complete(SMALL)).isInstanceOf(ChatException.class);
        assertThat(requests).hasSize(1);
    }

    // ---- model thinking (issue #5) ----

    private static final String GROQ = "https://api.groq.com/openai/v1";

    /** The wire body for {@code model} at {@code base}; building it makes no network call. */
    private static JsonNode bodyFor(String base, String model, String effort) {
        return new ChatClient(new GOneProperties(List.of("a:A:v", "b:B:v"), "", null, null,
                new GOneProperties.Llm(base, KEY, model, 5, effort)), JSON).body(SMALL);
    }

    @Test
    void qwenOnGroqIsAlwaysAskedForNoThinkingEvenWithAnEmptyConfig() {
        for (String effort : new String[] {"", "high", null}) {
            JsonNode body = bodyFor(GROQ, "qwen/qwen3.8-27b", effort);
            assertThat(body.path("reasoning_effort").asText()).as(effort).isEqualTo("none");
            assertThat(body.path("reasoning_format").asText()).as(effort).isEqualTo("hidden");
            assertThat(body.has("include_reasoning")).isFalse();
        }
    }

    @Test
    void gptOssOnGroqExcludesReasoningWithoutReasoningFormat() {
        JsonNode body = bodyFor(GROQ, "openai/gpt-oss-20b", "");
        assertThat(body.path("include_reasoning").isBoolean()).isTrue();
        assertThat(body.path("include_reasoning").asBoolean()).isFalse();
        assertThat(body.has("reasoning_format")).isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();

        JsonNode low = bodyFor(GROQ, "openai/gpt-oss-120b", "low");
        assertThat(low.path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(low.path("include_reasoning").asBoolean(true)).isFalse();
        assertThat(low.has("reasoning_format")).isFalse();
    }

    @Test
    void aNonGroqEndpointGetsNoReasoningFieldsEvenWhenConfigured() {
        for (String base : List.of("https://api.example.com/v1", "https://api.groq.com.example.net/v1", "http://127.0.0.1:1")) {
            for (String model : List.of("qwen/qwen3.8-27b", "openai/gpt-oss-20b", "llama-3.3-70b")) {
                for (String effort : new String[] {"", "low", "high"}) {
                    JsonNode body = bodyFor(base, model, effort);
                    String as = base + " " + model + " " + effort;
                    assertThat(body.has("reasoning_format")).as(as).isFalse();
                    assertThat(body.has("include_reasoning")).as(as).isFalse();
                    assertThat(body.has("reasoning_effort")).as(as).isFalse();
                }
            }
        }
    }

    @Test
    void groqGetsAConfiguredEffortForOtherModelsAndNothingWhenBlank() {
        assertThat(bodyFor(GROQ, "llama-3.3-70b", "low").path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(bodyFor(GROQ, "llama-3.3-70b", "").has("reasoning_effort")).isFalse();
    }

    @Test
    void anExtraEndpointModelGetsNoReasoningFieldsUnlessThatEndpointIsGroq() {
        GOneProperties.Llm other = new GOneProperties.Llm(GROQ, KEY, "qwen/qwen3.8-27b", 5, "", List.of(), List.of(), 0,
                "https://extra.example.com/v1", "extra-key-not-a-secret", List.of("qwen-extra"));
        assertThat(other.reasoningFields("qwen-extra")).isEmpty();
        GOneProperties.Llm otherHigh = new GOneProperties.Llm(GROQ, KEY, "qwen/qwen3.8-27b", 5, "high", List.of(),
                List.of(), 0, "https://extra.example.com/v1", "extra-key-not-a-secret", List.of("qwen-extra", "gpt-oss-x"));
        assertThat(otherHigh.reasoningFields("qwen-extra")).isEmpty();
        assertThat(otherHigh.reasoningFields("gpt-oss-x")).isEmpty();
        assertThat(other.reasoningFields("qwen/qwen3.8-27b")).containsEntry("reasoning_effort", "none");

        GOneProperties.Llm groqExtra = new GOneProperties.Llm("https://api.example.com/v1", KEY, "m", 5, "", List.of(),
                List.of(), 0, GROQ, "extra-key-not-a-secret", List.of("qwen/qwen3.8-27b"));
        assertThat(groqExtra.reasoningFields("qwen/qwen3.8-27b"))
                .containsEntry("reasoning_effort", "none").containsEntry("reasoning_format", "hidden");
    }

    private ChatReply answerWith(String content) {
        script.clear();
        answer("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":"
                + JSON.valueToTree(content) + "}}]}");
        return client(5).complete(SMALL);
    }

    @Test
    void recordedStyleThinkingNeverReachesTheReply() {
        assertThat(answerWith("<think>\nFarmer asks wheat price.\n</think>\n\nGehun ka bhav 2400 hai.").content())
                .isEqualTo("Gehun ka bhav 2400 hai.");
        assertThat(answerWith("Okay, the farmer wants rain news.\n</think>\nKal baarish ho sakti hai.").content())
                .isEqualTo("Kal baarish ho sakti hai.");
        assertThat(answerWith("<thinking>check memory</thinking>Ramesh ji, namaste.").content())
                .isEqualTo("Ramesh ji, namaste.");
        assertThat(answerWith("Haan ji. <think>Now I should also mention").content()).isEqualTo("Haan ji.");
    }

    @Test
    void reasoningInItsOwnFieldIsIgnored() {
        answer("""
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                  "reasoning":"The farmer wants weather, I will answer briefly.","content":"Kal dhoop rahegi."}}]}""");
        ChatReply reply = client(5).complete(SMALL);
        assertThat(reply.content()).isEqualTo("Kal dhoop rahegi.");
    }

    @Test
    void anAnswerThatIsOnlyThinkingIsRetriedOnceThenABadResponse() {
        String cutOff = "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"<think>The farmer asked about\"}}]}";
        answer(cutOff);
        answer(OK);
        assertThat(client(5).complete(SMALL).content()).isEqualTo("{}");
        assertThat(requests).hasSize(2);

        requests.clear();
        answer(cutOff);
        answer("{\"choices\":[{\"message\":{\"content\":\"plan only</think>\"}}]}");
        assertThatThrownBy(() -> client(5).complete(SMALL)).isInstanceOfSatisfying(ChatException.class,
                e -> assertThat(e.kind()).isEqualTo(ChatException.Kind.BAD_RESPONSE));
        assertThat(requests).hasSize(2);
    }
}
