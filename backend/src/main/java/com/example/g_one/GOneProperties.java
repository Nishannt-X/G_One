package com.example.g_one;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** The single place configuration is read. Values come from environment variables (see application.yml). */
@ConfigurationProperties("g-one")
public record GOneProperties(
        List<String> languages,
        String mandiSnapshotFile,
        Agmarknet agmarknet,
        Gnani gnani,
        Llm llm,
        Translate translate) {

    public GOneProperties(List<String> languages, String mandiSnapshotFile, Agmarknet agmarknet, Gnani gnani, Llm llm) {
        this(languages, mandiSnapshotFile, agmarknet, gnani, llm, null);
    }

    @ConstructorBinding
    public GOneProperties {}

    /**
     * Who translates a mediated turn.
     *
     * @param provider auto (Sarvam when a key is set, else the language model), sarvam, or llm
     * @param model {@code mayura:v1} or {@code sarvam-translate:v1}; the second only supports the formal mode
     * @param mode formal, modern-colloquial, classic-colloquial or code-mixed
     */
    public record Translate(String provider, String baseUrl, String apiKey, String model, String mode, int timeoutSeconds) {
        public boolean sarvamConfigured() {
            return apiKey != null && !apiKey.isBlank() && baseUrl != null && !baseUrl.isBlank();
        }

        /** True when turns should go to Sarvam. {@code llm} always stays on the language model. */
        public boolean usesSarvam() {
            return !"llm".equalsIgnoreCase(provider == null ? "" : provider.strip()) && sarvamConfigured();
        }
    }

    /**
     * Optional live AGMARKNET lookup for one commodity/variety/market. The six identifying fields are all required
     * together (see MandiSource); the names are checked against the response, so a wrong ID is rejected, not trusted.
     */
    public record Agmarknet(String baseUrl, String marketId, String stateId, String commodityId, String commodity,
                            String market, String variety, int timeoutSeconds, int maxAgeDays, int cacheMinutes) {}

    public record Gnani(String baseUrl, String apiKey, String sttModel, String ttsModel, int timeoutSeconds) {
        public boolean configured() {
            return !apiKey.isBlank();
        }
    }

    /**
     * @param reasoningEffort sent as {@code reasoning_effort} when not blank. Reasoning models need it, because hidden
     *     reasoning spends the same token budget as the answer; other providers reject the unknown field, so blank
     *     means it is left out. Qwen on Groq always gets {@code none}, set or not (see {@link #reasoningFields})
     * @param fallbackModels tried in order after {@code model} when it is rate limited or down
     * @param numericUnsafeModels substrings naming models that must never handle figures
     * @param tokensPerMinute each model's limit, so a call that would be refused is not sent; 0 turns the budget off
     * @param extraBaseUrl a second OpenAI-compatible endpoint, for when the primary provider is out of quota
     * @param extraApiKey its key, sent as a bearer token and as {@code api-subscription-key}
     * @param extraModels models served by that endpoint, tried last
     */
    public record Llm(String baseUrl, String apiKey, String model, int timeoutSeconds, String reasoningEffort,
                      List<String> fallbackModels, List<String> numericUnsafeModels, long tokensPerMinute,
                      String extraBaseUrl, String extraApiKey, List<String> extraModels) {
        public Llm(String baseUrl, String apiKey, String model, int timeoutSeconds, String reasoningEffort,
                   List<String> fallbackModels, List<String> numericUnsafeModels, long tokensPerMinute) {
            this(baseUrl, apiKey, model, timeoutSeconds, reasoningEffort, fallbackModels, numericUnsafeModels, tokensPerMinute,
                    "", "", List.of());
        }

        public Llm(String baseUrl, String apiKey, String model, int timeoutSeconds) {
            this(baseUrl, apiKey, model, timeoutSeconds, "");
        }

        public Llm(String baseUrl, String apiKey, String model, int timeoutSeconds, String reasoningEffort) {
            this(baseUrl, apiKey, model, timeoutSeconds, reasoningEffort, List.of(), List.of(), 0);
        }

        @ConstructorBinding
        public Llm {
            reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.strip();
            fallbackModels = fallbackModels == null ? List.of() : fallbackModels.stream().map(String::strip)
                    .filter(m -> !m.isEmpty()).toList();
            numericUnsafeModels = numericUnsafeModels == null ? List.of() : numericUnsafeModels.stream().map(String::strip)
                    .filter(m -> !m.isEmpty()).toList();
            extraBaseUrl = extraBaseUrl == null ? "" : extraBaseUrl.strip();
            extraApiKey = extraApiKey == null ? "" : extraApiKey.strip();
            extraModels = extraBaseUrl.isEmpty() || extraApiKey.isEmpty() || extraModels == null ? List.of()
                    : extraModels.stream().map(String::strip).filter(m -> !m.isEmpty()).toList();
            if (apiKey != null && apiKey.startsWith("gsk_")) {
                if (baseUrl == null || baseUrl.contains("api.x.ai") || baseUrl.isBlank()) {
                    baseUrl = "https://api.groq.com/openai/v1";
                }
                if (model == null || model.contains("grok") || model.isBlank()) {
                    model = "qwen/qwen3.8-27b";
                }
            }
        }

        public boolean configured() {
            return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
        }

        /** True when this model is served by the extra endpoint rather than the primary one. */
        public boolean isExtra(String routedModel) {
            return extraModels.contains(routedModel);
        }

        /** The effort to send for this model; Qwen on Groq rejects the OpenAI values, so it gets its own. */
        public String reasoningEffortFor(String routedModel) {
            if (reasoningEffort.isEmpty() || isExtra(routedModel)) return "";
            return isQwen(routedModel) ? "none" : reasoningEffort;
        }

        /**
         * The reasoning fields to add to a request for this model. Thinking must never reach a caller, so on Groq the
         * request asks for none: Qwen always gets {@code reasoning_effort: none} and {@code reasoning_format: hidden};
         * gpt-oss, which supports neither {@code none} nor {@code reasoning_format}, gets {@code include_reasoning:
         * false}. Other providers reject unknown fields, so off Groq only a configured {@link #reasoningEffortFor} is
         * sent. See https://console.groq.com/docs/reasoning.
         */
        public java.util.Map<String, Object> reasoningFields(String routedModel) {
            java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
            boolean extra = isExtra(routedModel);
            if (!isGroq(extra ? extraBaseUrl : baseUrl)) {
                String effort = reasoningEffortFor(routedModel);
                if (!effort.isEmpty()) fields.put("reasoning_effort", effort);
                return fields;
            }
            String name = routedModel == null ? "" : routedModel.toLowerCase(java.util.Locale.ROOT);
            if (isQwen(routedModel)) {
                fields.put("reasoning_effort", "none");
                fields.put("reasoning_format", "hidden");
            } else if (name.contains("gpt-oss")) {
                if (!reasoningEffort.isEmpty()) fields.put("reasoning_effort", reasoningEffort);
                fields.put("include_reasoning", false);
            } else if (!reasoningEffort.isEmpty()) {
                fields.put("reasoning_effort", reasoningEffort);
            }
            return fields;
        }

        /** True for Groq's endpoint, the only provider the Groq-only reasoning fields go to. */
        static boolean isGroq(String url) {
            if (url == null || url.isBlank()) return false;
            try {
                String host = java.net.URI.create(url.strip()).getHost();
                if (host == null) return false;
                host = host.toLowerCase(java.util.Locale.ROOT);
                return host.equals("groq.com") || host.endsWith(".groq.com");
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        private static boolean isQwen(String routedModel) {
            return routedModel != null && routedModel.toLowerCase(java.util.Locale.ROOT).contains("qwen");
        }
    }
}
