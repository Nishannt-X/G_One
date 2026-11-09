package com.example.g_one.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Fixed spoken lines for when the model or a tool fails, so the farmer never hears silence. Read once from
 * {@code /prompts/fallback-lines.json}, keyed by Gnani locale code; unknown languages get the English line.
 */
@Component
public class FallbackLines {
    public static final String RESOURCE = "/prompts/fallback-lines.json";
    public static final String DEFAULT_LANGUAGE = "en-IN";

    private final Map<String, String> apology;
    private final Map<String, List<String>> busy;
    private final Map<String, List<String>> busyAgain;

    public FallbackLines(ObjectMapper json) {
        try (InputStream in = FallbackLines.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Fallback lines not found: " + RESOURCE);
            JsonNode root = json.readTree(in);
            JsonNode node = root.path("apology");
            Map<String, String> lines = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> {
                if (e.getValue().isTextual() && !e.getValue().asText().isBlank()) lines.put(e.getKey(), e.getValue().asText());
            });
            if (!lines.containsKey(DEFAULT_LANGUAGE)) {
                throw new IllegalStateException("Fallback lines need an " + DEFAULT_LANGUAGE + " apology");
            }
            this.apology = Map.copyOf(lines);
            this.busy = readLists(root.path("busy"));
            this.busyAgain = readLists(root.path("busyAgain"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read fallback lines: " + RESOURCE, e);
        }
    }

    /** The short spoken apology in the farmer's language (Gnani code such as "hi-IN"), English when not available. */
    public String apology(String languageCode) {
        String line = languageCode == null ? null : apology.get(languageCode);
        return line != null ? line : apology.get(DEFAULT_LANGUAGE);
    }

    /** Honest one-liners with a concrete next step, for a first failure. At least two, so a repeat can be avoided. */
    public List<String> busy(String languageCode) {
        return pick(busy, languageCode);
    }

    /** For a second failure in a row: says it is busy and offers to continue in a few seconds. */
    public List<String> busyAgain(String languageCode) {
        return pick(busyAgain, languageCode);
    }

    private static List<String> pick(Map<String, List<String>> lines, String code) {
        return lines.getOrDefault(FixedLines.bestKey(lines.keySet(), code, DEFAULT_LANGUAGE), List.of());
    }

    /** The key {@link #busy} reads for a language code, for voicing the line. */
    public String busyKey(String languageCode) {
        return FixedLines.bestKey(busy.keySet(), languageCode, DEFAULT_LANGUAGE);
    }

    private static Map<String, List<String>> readLists(JsonNode node) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            List<String> lines = new ArrayList<>();
            e.getValue().forEach(n -> lines.add(n.asText()));
            if (lines.size() < 2) throw new IllegalStateException("Fallback lines need two or more for " + e.getKey());
            out.put(e.getKey(), List.copyOf(lines));
        });
        if (!out.containsKey(DEFAULT_LANGUAGE)) throw new IllegalStateException("Fallback busy lines need " + DEFAULT_LANGUAGE);
        return Map.copyOf(out);
    }

    public Map<String, String> apologies() {
        return apology;
    }
}
