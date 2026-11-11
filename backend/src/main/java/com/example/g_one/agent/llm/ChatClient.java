package com.example.g_one.agent.llm;

import com.example.g_one.GOneProperties;
import com.example.g_one.llm.ModelRouter;
import com.example.g_one.speech.Http;
import com.example.g_one.speech.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OpenAI-compatible /chat/completions with function calling, for the agent brain. Endpoint, key, model and timeout come
 * from G_ONE_LLM_*. Uses the shared HTTP/1.1 client with bounded timeouts. Every failure becomes a
 * {@link ChatException}. Timeouts and server errors are not retried, because a phone turn cannot afford a second
 * timeout. The one exception is a model that answered but gave nothing usable: an empty answer after spending its
 * budget on hidden reasoning gets one more try with a larger budget, and a tool call the provider refused
 * ({@code tool_use_failed}: a malformed call, or a tool that was not offered) gets one more try without tools, so the
 * model has to answer in words. Repeating the same request would fail the same way. A cut-off answer that has content
 * is kept as it is, because a second call would spend the rate limit twice.
 *
 * <p>gpt-oss on Groq reasons at {@code medium} unless told otherwise, and its hidden reasoning spends the same
 * {@code max_tokens} as the answer, so a spoken reply of a few dozen tokens ran out of a 300-token budget. It gets
 * {@code reasoning_effort: low} when no effort is configured.
 */
@Component
public class ChatClient {
    private static final String STAGE = "agent_llm";
    /** A chat answer is a few kilobytes at most; anything far larger is not a reply we want to buffer. */
    private static final int MAX_BODY_BYTES = 512 * 1024;
    private static final int RETRY_TOKEN_FACTOR = 3;
    /** The retry budget never goes above this: a spoken reply is short, and the tokens-per-minute limit is small. */
    static final int MAX_RETRY_TOKENS = 900;
    /** What gpt-oss on Groq gets when no reasoning effort is configured. */
    static final String DEFAULT_GPT_OSS_EFFORT = "low";
    /** Only the limit's name and numbers are read from a 429 message; the rest is never logged. */
    private static final Pattern BUCKET = Pattern.compile("on ((?:tokens|requests) per (?:minute|day))[^:]*: Limit (\\d+), Used (\\d+)");
    private static final Logger log = LoggerFactory.getLogger(ChatClient.class);

    /** Groq's 400 codes for a generation that came out unusable; a second try usually succeeds. */
    private static final Set<String> UNFINISHED_CODES = Set.of("json_validate_failed", "tool_use_failed");
    /** The model named is not available on this key; another one may be. */
    private static final Set<String> MODEL_GONE_CODES = Set.of("model_not_found", "model_decommissioned", "model_terminated");

    /** The model answered but produced nothing usable. {@code code} is the provider's error code, or empty. */
    private static final class Unfinished extends ChatException {
        final String code;

        Unfinished(int status, String code, String message) {
            super(status == 0 ? Kind.BAD_RESPONSE : Kind.HTTP_ERROR, status, message);
            this.code = code;
        }

        /** The provider refused the tool call the model made; the same request would fail again. */
        boolean toolRefused() {
            return "tool_use_failed".equals(code);
        }
    }

    private final GOneProperties.Llm cfg;
    private final RestClient client;
    private final RestClient extraClient;
    private final ObjectMapper json;
    private final Scripted scripted;
    private final ModelRouter router;

    /** Answers instead of a real model when the offline demo is on. */
    @FunctionalInterface
    public interface Scripted {
        ChatReply complete(ChatRequest request);
    }

    public ChatClient(GOneProperties props, ObjectMapper json) {
        this(props, json, (Scripted) null);
    }

    @Autowired
    public ChatClient(GOneProperties props, ObjectMapper json, ObjectProvider<Scripted> scripted, ModelRouter router) {
        this(props, json, scripted.getIfUnique(), router);
    }

    /** @param scripted when not null, answers every request and no network call is made */
    public ChatClient(GOneProperties props, ObjectMapper json, Scripted scripted) {
        this(props, json, scripted, new ModelRouter(props));
    }

    public ChatClient(GOneProperties props, ObjectMapper json, Scripted scripted, ModelRouter router) {
        this.router = router;
        this.scripted = scripted;
        this.cfg = props.llm();
        this.client = cfg != null && cfg.configured() ? Http.client(cfg.baseUrl(), cfg.timeoutSeconds()) : null;
        this.extraClient = cfg != null && !cfg.extraModels().isEmpty() ? Http.client(cfg.extraBaseUrl(), cfg.timeoutSeconds()) : null;
        this.json = json;
    }

    public boolean configured() {
        return scripted != null || client != null;
    }

    public boolean isScripted() {
        return scripted != null;
    }

    /** @throws ChatException when there is no usable answer (not configured, network, HTTP status, unreadable) */
    public ChatReply complete(ChatRequest request) {
        if (scripted != null) return scripted.complete(request);
        if (client == null) {
            throw new ChatException(ChatException.Kind.NOT_CONFIGURED, 0,
                    "The assistant's language model is not configured.");
        }
        try {
            return send(request);
        } catch (Unfinished unfinished) {
            if (!request.retry()) throw unfinished;
            ChatRequest again = retryOf(request, unfinished);
            log.warn("Language model produced nothing usable ({}) within {} tokens; retrying once with {} tokens{}",
                    unfinished.code.isEmpty() ? "empty answer" : unfinished.code, request.maxTokens(), again.maxTokens(),
                    again.tools().size() < request.tools().size() ? " and no tools" : "");
            try {
                return send(again);
            } catch (ChatException e) {
                log.warn("Language model retry failed too: {} {}", e.kind(), e.status());
                // A rate limit on the retry says more about the turn than the first unusable answer does.
                throw e.kind() == ChatException.Kind.RATE_LIMITED ? e : unfinished;
            }
        }
    }

    /**
     * The one retry after an unusable answer: without tools when the provider refused the model's tool call (the same
     * tools would fail the same way), always with a larger budget for hidden reasoning, at most {@value #MAX_RETRY_TOKENS}.
     */
    static ChatRequest retryOf(ChatRequest request, ChatException failure) {
        ChatRequest again = request.withMaxTokens(Math.max(request.maxTokens(),
                Math.min(MAX_RETRY_TOKENS, request.maxTokens() * RETRY_TOKEN_FACTOR)));
        if (failure instanceof Unfinished u && u.toolRefused() && !request.tools().isEmpty()) again = again.withoutTools();
        return again;
    }

    /** The router's choice of model, turning "nobody can take this" into the caller's rate-limit error. */
    private ChatReply send(ChatRequest request) {
        try {
            return router.call(request.task(), estimate(request), slot -> sendTo(slot, request));
        } catch (ModelRouter.AllBusy e) {
            throw new ChatException(ChatException.Kind.RATE_LIMITED, 429, "Every language model is busy right now.");
        }
    }

    /** About four UTF-8 bytes a token, plus what the answer may use. Only used to decide whether a call would fit. */
    private int estimate(ChatRequest request) {
        int bytes = 0;
        for (ChatMessage m : request.messages()) bytes += m.content() == null ? 16 : m.content().length() * 2;
        for (ToolSpec t : request.tools()) bytes += t.description().length() + 120;
        return bytes / 4 + Math.min(request.maxTokens(), 250);
    }

    private ChatReply sendTo(ModelRouter.Slot slot, ChatRequest request) {
        long started = System.nanoTime();
        Http.Result r;
        try {
            boolean extra = cfg.isExtra(slot.model()) && extraClient != null;
            r = Http.call(STAGE, (extra ? extraClient : client).post().uri("/chat/completions")
                    .header("Authorization", "Bearer " + (extra ? cfg.extraApiKey() : cfg.apiKey()))
                    .headers(h -> {
                        if (extra) h.set("api-subscription-key", cfg.extraApiKey());
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(utf8(body(request, slot.model()))), MAX_BODY_BYTES);
        } catch (UpstreamException e) {
            if (e.status() == 429) throw new ModelRouter.RateLimited(null);
            if (e.status() > 0) {
                throw new ModelRouter.TryNext(new ChatException(ChatException.Kind.HTTP_ERROR, e.status(),
                        "The language model returned HTTP " + e.status() + "."));
            }
            throw new ModelRouter.TryNext(new ChatException(ChatException.Kind.UNREACHABLE, 0,
                    "The language model could not be reached (timeout or network error)."));
        }
        if (!r.ok()) {
            if (r.status() == 429) {
                log.warn("llm_rate_limit model={} bucket={}", slot.model(), bucket(r.body()));
                throw new ModelRouter.RateLimited(r.headers());
            }
            String code = errorCode(r.body());
            if (r.status() == 400 && UNFINISHED_CODES.contains(code)) {
                throw new Unfinished(400, code, "The language model could not finish its answer (HTTP 400 " + code + ").");
            }
            log.warn("Language model refused the request: HTTP {} {} model={}", r.status(), code, slot.model());
            ChatException refused = new ChatException(ChatException.Kind.HTTP_ERROR, r.status(),
                    "The language model returned HTTP " + r.status() + ".");
            if (r.status() >= 500 || r.status() == 404 || MODEL_GONE_CODES.contains(code)) throw new ModelRouter.TryNext(refused);
            throw refused;
        }
        if (r.body().length > MAX_BODY_BYTES) throw bad("The language model's answer was too large.");
        ChatReply reply = parse(r.body(), slot.model(), (System.nanoTime() - started) / 1_000_000);
        slot.spent(reply.usage().totalTokens());
        slot.observe(r.headers());
        log.info("llm_call model={} finish={} prompt_tokens={} completion_tokens={} latency_ms={}", reply.usage().model(),
                reply.finishReason(), reply.usage().promptTokens(), reply.usage().completionTokens(), reply.usage().latencyMs());
        return reply;
    }

    /** The JSON request body. Package-private so tests can check the wire format without a server. */
    ObjectNode body(ChatRequest request) {
        return body(request, router.primary());
    }

    private ObjectNode body(ChatRequest request, String model) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        cfg.reasoningFields(model).forEach((k, v) -> body.set(k, json.valueToTree(v)));
        if (body.has("include_reasoning") && !body.has("reasoning_effort") && isGptOss(model)) {
            // Groq gpt-oss defaults to medium effort, and its reasoning spends the answer's budget.
            body.put("reasoning_effort", DEFAULT_GPT_OSS_EFFORT);
        }
        ArrayNode messages = body.putArray("messages");
        for (ChatMessage m : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", m.role());
            if (m.content() == null) node.putNull("content");
            else node.put("content", m.content());
            if (!m.toolCalls().isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCall c : m.toolCalls()) {
                    ObjectNode call = calls.addObject();
                    call.put("id", c.id());
                    call.put("type", "function");
                    String args = c.argumentsJson() == null || c.argumentsJson().isBlank() ? "{}" : c.argumentsJson();
                    call.putObject("function").put("name", c.name()).put("arguments", args);
                    if (c.extraContentJson() != null && !c.extraContentJson().isBlank()) {
                        try {
                            call.set("extra_content", json.readTree(c.extraContentJson()));
                        } catch (IOException ignored) {
                            // Provider data we cannot read back is simply not echoed.
                        }
                    }
                }
            }
            if (m.toolCallId() != null) node.put("tool_call_id", m.toolCallId());
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec t : request.tools()) {
                ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
                fn.put("name", t.name());
                fn.put("description", t.description());
                fn.set("parameters", t.parameters() == null ? json.createObjectNode().put("type", "object") : nullableOptionals(t.parameters()));
            }
            body.put("tool_choice", "auto");
        }
        return body;
    }

    /**
     * Models often send null for an optional argument they don't have, and Groq rejects the whole answer when the schema
     * forbids it. Tools already read null as absent, so optional properties may be null on the wire.
     */
    private JsonNode nullableOptionals(JsonNode parameters) {
        if (!parameters.path("properties").isObject()) return parameters;
        ObjectNode copy = parameters.deepCopy();
        Set<String> required = new HashSet<>();
        copy.path("required").forEach(r -> required.add(r.asText()));
        copy.path("properties").properties().forEach(p -> {
            if (!required.contains(p.getKey()) && p.getValue() instanceof ObjectNode prop && prop.path("type").isTextual()) {
                String type = prop.path("type").asText();
                prop.putArray("type").add(type).add("null");
            }
        });
        return copy;
    }

    private ChatReply parse(byte[] raw, String model, long latencyMs) {
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (IOException e) {
            throw bad("The language model returned an unreadable answer.");
        }
        JsonNode choice = root == null ? null : root.path("choices").path(0);
        if (choice == null || !choice.isObject() || !choice.path("message").isObject()) {
            throw bad("The language model's answer had no message.");
        }
        JsonNode message = choice.path("message");
        // Thinking inline in the content is stripped; a separate message.reasoning field is never read.
        String content = message.path("content").isTextual()
                ? ReasoningText.stripContent(message.path("content").asText(), json) : "";
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode c : message.path("tool_calls")) {
            JsonNode fn = c.path("function");
            String name = fn.path("name").asText("");
            if (name.isBlank()) throw bad("The language model asked for a tool without a name.");
            JsonNode args = fn.path("arguments");
            // The spec says a JSON string; a few compatible servers send the object itself.
            String argsJson = args.isTextual() ? args.asText() : args.isMissingNode() || args.isNull() ? "" : args.toString();
            String id = c.path("id").asText("");
            JsonNode extra = c.path("extra_content");
            calls.add(new ToolCall(id.isBlank() ? "call_" + calls.size() : id, name, argsJson,
                    extra.isMissingNode() || extra.isNull() ? null : extra.toString()));
        }
        if (content.isEmpty() && calls.isEmpty()) throw new Unfinished(0, "", "The language model returned an empty answer.");
        JsonNode usage = root.path("usage");
        return new ChatReply(content, calls, choice.path("finish_reason").asText(""), new ChatReply.Usage(model,
                usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0), latencyMs));
    }

    /** Which limit a 429 hit and its numbers, for example "tokens per day limit=200000 used=199000". */
    private String bucket(byte[] raw) {
        try {
            Matcher m = BUCKET.matcher(json.readTree(raw).path("error").path("message").asText(""));
            return m.find() ? m.group(1) + " limit=" + m.group(2) + " used=" + m.group(3) : "unknown";
        } catch (IOException e) {
            return "unknown";
        }
    }

    /** The provider's short error code. The rest of an error body can echo the prompt, so it is not read. */
    private String errorCode(byte[] raw) {
        try {
            return json.readTree(raw).path("error").path("code").asText("");
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean isGptOss(String model) {
        return model != null && model.toLowerCase(java.util.Locale.ROOT).contains("gpt-oss");
    }

    /** Bytes, not a String: a String body would go through a converter whose default charset is not UTF-8. */
    private static byte[] utf8(ObjectNode body) {
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static ChatException bad(String message) {
        return new ChatException(ChatException.Kind.BAD_RESPONSE, 0, message);
    }
}
