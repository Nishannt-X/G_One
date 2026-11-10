package com.example.g_one.speech;

import com.example.g_one.GOneProperties;
import com.example.g_one.agent.llm.ReasoningText;
import com.example.g_one.llm.ModelRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            JsonNode answer;
            try {
                // Parse valid JSON untouched: stripping its serialized form can join separate fields.
                answer = json.readTree(content);
            } catch (IOException invalidJson) {
                // A reasoning preface outside the JSON is the only case that needs raw text stripping.
                String prefix = content.substring(0, Math.max(0, content.indexOf('{')));
                if (!ReasoningText.hasTag(prefix)) throw invalidJson;
                String clean = ReasoningText.strip(content);
                if (clean.isEmpty()) throw new IOException("only reasoning");
                answer = json.readTree(clean);
            }
            if (answer == null || !answer.isObject()) throw new IOException("not an object");
            return ReasoningText.stripJsonValues(answer);
        } catch (IOException e) {
            throw new UpstreamException(stage, "Translation service returned an unreadable answer.");
        }
    }
}
