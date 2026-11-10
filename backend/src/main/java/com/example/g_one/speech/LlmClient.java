package com.example.g_one.speech;

import com.example.g_one.GOneProperties;
import com.example.g_one.agent.llm.ReasoningText;
import com.example.g_one.llm.ModelRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Minimal OpenAI-compatible /chat/completions caller that returns the model's JSON answer. */
@Component
public class LlmClient {
    /** Enough for the JSON answers this client asks for, with a reasoning model's hidden tokens on top. */
    private static final int MAX_TOKENS = 900;
    private final GOneProperties.Llm cfg;
    private final RestClient client;
    private final RestClient extraClient;
    private final ObjectMapper json;
    private final ModelRouter router;

    public LlmClient(GOneProperties props, ObjectMapper json) {
        this(props, json, new ModelRouter(props));
    }

    @Autowired
    public LlmClient(GOneProperties props, ObjectMapper json, ModelRouter router) {
        this.router = router;
        this.cfg = props.llm();
        this.client = cfg.configured() ? Http.client(cfg.baseUrl(), cfg.timeoutSeconds()) : null;
        this.extraClient = !cfg.extraModels().isEmpty() ? Http.client(cfg.extraBaseUrl(), cfg.timeoutSeconds()) : null;
        this.json = json;
    }

    public boolean configured() {
        return cfg.configured();
    }

    /** @param stage error code to raise on failure (e.g. "translation_failed") */
    public JsonNode askJson(String stage, String system, String user) {
        if (client == null) throw new UpstreamException(stage, "Translation service is not configured.");
        try {
            return router.call(ModelRouter.Task.NUMERIC, (system.length() + user.length()) / 2 + MAX_TOKENS,
                    slot -> send(slot, stage, system, user));
        } catch (ModelRouter.AllBusy e) {
            throw new UpstreamException(stage, "Translation service is busy.", 429);
        }
    }

    private JsonNode send(ModelRouter.Slot slot, String stage, String system, String user) {
        Map<String, Object> body = new HashMap<>(Map.of(
                "model", slot.model(),
                "temperature", 0,
                "max_tokens", MAX_TOKENS,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user))));
        body.putAll(cfg.reasoningFields(slot.model()));
        boolean extra = cfg.isExtra(slot.model()) && extraClient != null;
        Http.Result r = Http.call(stage, (extra ? extraClient : client).post().uri("/chat/completions")
                .header("Authorization", "Bearer " + (extra ? cfg.extraApiKey() : cfg.apiKey()))
                .headers(h -> {
                    if (extra) h.set("api-subscription-key", cfg.extraApiKey());
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body));
        if (r.status() == 429) throw new ModelRouter.RateLimited(r.headers());
        if (r.status() >= 500 || r.status() == 404) {
            throw new ModelRouter.TryNext(new UpstreamException(stage, "Translation service returned HTTP " + r.status() + ".", r.status()));
        }
        if (!r.ok()) throw new UpstreamException(stage, "Translation service returned HTTP " + r.status() + ".");
        slot.observe(r.headers());
        try {
            JsonNode root = json.readTree(r.body());
            slot.spent(root.path("usage").path("total_tokens").asInt(0));
            // Thinking left in the content would break the JSON; nothing left is an unreadable answer.
            String content = ReasoningText.strip(root.path("choices").path(0).path("message").path("content").asText(""));
            if (content.isEmpty()) throw new IOException("only reasoning");
            JsonNode answer = json.readTree(content);
            if (!answer.isObject()) throw new IOException("not an object");
            return stripDecodedReasoning(answer);
        } catch (IOException e) {
            throw new UpstreamException(stage, "Translation service returned an unreadable answer.");
        }
    }

    /** Sanitize text after JSON decoding, including strings nested in objects and arrays. */
    private static JsonNode stripDecodedReasoning(JsonNode node) {
        if (node.isTextual()) {
            String text = node.textValue();
            return ReasoningText.hasTag(text) ? TextNode.valueOf(ReasoningText.strip(text)) : node;
        }
        if (node instanceof ObjectNode object) {
            object.fields().forEachRemaining(field -> object.set(field.getKey(), stripDecodedReasoning(field.getValue())));
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) array.set(i, stripDecodedReasoning(array.get(i)));
        }
        return node;
    }
}
