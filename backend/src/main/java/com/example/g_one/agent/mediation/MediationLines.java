package com.example.g_one.agent.mediation;

import com.example.g_one.agent.llm.FixedLines;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Fixed mediation lines from {@code /prompts/mediation-lines.json}. A missing line fails at startup, not on a call. */
@Component
public class MediationLines {
    public static final String RESOURCE = "/prompts/mediation-lines.json";
    private static final Set<String> KEYS = Set.of("buyerIntro", "buyerRetry", "buyerAccepted", "buyerDeclined",
            "farmerBuyerJoined", "farmerBuyerDeclined", "farmerBuyerLeft", "buyerFarmerLeft", "oneMoment", "repeat", "unsafeTranslation",
            "cautionSecret", "cautionThreat", "cautionAdvance", "cautionShareWithheld", "priceBelow", "priceFarBelow",
            "priceNear", "priceAbove", "readback", "readbackTotal", "calculatedTotal", "notStated", "confirmRetry", "confirmedWait", "declined", "expired",
            "voided", "endFarmer", "endBuyer");

    private final Map<String, Map<String, String>> byCode = new LinkedHashMap<>();

    public MediationLines(ObjectMapper json) {
        try (InputStream in = MediationLines.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Mediation lines not found: " + RESOURCE);
            json.readTree(in).path("lines").fields().forEachRemaining(e -> byCode.put(e.getKey(), read(e)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read mediation lines: " + RESOURCE, e);
        }
        if (!byCode.containsKey(FixedLines.DEFAULT_LANGUAGE)) {
            throw new IllegalStateException("Mediation lines need an " + FixedLines.DEFAULT_LANGUAGE + " entry");
        }
    }

    private static Map<String, String> read(Map.Entry<String, JsonNode> e) {
        Map<String, String> lines = new LinkedHashMap<>();
        for (String key : KEYS) {
            String v = e.getValue().path(key).asText("");
            if (v.isBlank()) throw new IllegalStateException("Mediation line " + key + " is missing for " + e.getKey());
            lines.put(key, v);
        }
        return Map.copyOf(lines);
    }

    /** The line for this language (exact, then same prefix, then English) with each {@code {name}} filled in. */
    public String get(String key, String languageCode, Map<String, String> values) {
        String code = FixedLines.bestKey(byCode.keySet(), languageCode, FixedLines.DEFAULT_LANGUAGE);
        String line = byCode.get(code).get(key);
        if (line == null) throw new IllegalArgumentException("No mediation line " + key);
        for (Map.Entry<String, String> v : values.entrySet()) {
            line = line.replace("{" + v.getKey() + "}", v.getValue());
        }
        return line;
    }

    public String get(String key, String languageCode) {
        return get(key, languageCode, Map.of());
    }

    /** True when this language has its own written lines; otherwise {@link #get} falls back to English. */
    public boolean hasOwnLines(String languageCode) {
        return FixedLines.prefix(spokenCode(languageCode)).equals(FixedLines.prefix(languageCode));
    }

    /** The resource key a line for this language comes from: the language it is really written in. */
    public String spokenCode(String languageCode) {
        return FixedLines.bestKey(byCode.keySet(), languageCode, FixedLines.DEFAULT_LANGUAGE);
    }
}
