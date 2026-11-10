package com.example.g_one.agent.closing;

import com.example.g_one.agent.llm.FixedLines;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixed lines of the end-of-call check, word for word in each language: "would you like to end the call?", "may we
 * remember the main points?", "let's continue" and the goodbye. Read once from {@code /prompts/fixed-lines.json}; a code
 * with no entry uses the same language prefix, then English ({@link FixedLines#bestKey}).
 */
public final class ClosingLines {
    public static final List<String> KEYS = List.of("askEnd", "askSave", "carryOn", "goodbye");
    private static final Map<String, Map<String, String>> BY_CODE = load();

    private ClosingLines() {}

    public static String ask(CallClosing.Question question, String code) {
        return line(question == CallClosing.Question.END ? "askEnd" : "askSave", code);
    }

    public static String carryOn(String code) {
        return line("carryOn", code);
    }

    public static String goodbye(String code) {
        return line("goodbye", code);
    }

    /** The resource key whose lines {@code code} gets. */
    public static String key(String code) {
        return FixedLines.bestKey(BY_CODE.keySet(), code, FixedLines.DEFAULT_LANGUAGE);
    }

    static String line(String name, String code) {
        return BY_CODE.get(key(code)).get(name);
    }

    private static Map<String, Map<String, String>> load() {
        try (InputStream in = ClosingLines.class.getResourceAsStream(FixedLines.RESOURCE)) {
            if (in == null) throw new IllegalStateException("Fixed lines not found: " + FixedLines.RESOURCE);
            Map<String, Map<String, String>> out = new LinkedHashMap<>();
            new ObjectMapper().readTree(in).path("lines").fields().forEachRemaining(e -> {
                Map<String, String> lines = new LinkedHashMap<>();
                for (String k : KEYS) {
                    JsonNode v = e.getValue().path(k);
                    if (!v.isTextual() || v.asText().isBlank()) {
                        throw new IllegalStateException("Fixed line " + k + " is missing for " + e.getKey());
                    }
                    lines.put(k, v.asText());
                }
                out.put(e.getKey(), Map.copyOf(lines));
            });
            return Map.copyOf(out);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read fixed lines: " + FixedLines.RESOURCE, e);
        }
    }
}
