package com.example.g_one.agent.brain;

import com.example.g_one.agent.llm.FixedLines;
import com.example.g_one.agent.tools.mandi.MandiAnswer;
import com.example.g_one.agent.tools.mandi.MandiWording;
import com.example.g_one.agent.tools.memory.MemoryGate;
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
 * Lines the assistant can say without a language model, from {@code /prompts/local-replies.json}: questions with one
 * truthful answer (who are you, how do you know my name) and common turns when no model is available.
 */
@Component
public class LocalReplies {
    public static final String RESOURCE = "/prompts/local-replies.json";
    private static final String DEFAULT_LANGUAGE = "en-IN";
    private static final String NAME = "{name}";

    private final Map<String, JsonNode> byCode;

    public LocalReplies(ObjectMapper json) {
        try (InputStream in = LocalReplies.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Local replies not found: " + RESOURCE);
            Map<String, JsonNode> lines = new LinkedHashMap<>();
            json.readTree(in).path("replies").fields().forEachRemaining(e -> lines.put(e.getKey(), e.getValue()));
            if (!lines.containsKey(DEFAULT_LANGUAGE)) throw new IllegalStateException("Local replies need " + DEFAULT_LANGUAGE);
            this.byCode = Map.copyOf(lines);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read local replies: " + RESOURCE, e);
        }
    }

    /** The resource key these lines come from for a Gnani language code. */
    String key(String code) {
        return FixedLines.bestKey(byCode.keySet(), code, DEFAULT_LANGUAGE);
    }

    String line(String code, String which) {
        String v = byCode.get(key(code)).path(which).asText("");
        if (v.isBlank()) throw new IllegalStateException("Local reply " + which + " is missing for " + key(code));
        return v;
    }

    String line(String code, String which, String name) {
        return line(code, which).replace(NAME, name);
    }

    List<String> lines(String code, String which) {
        List<String> out = new ArrayList<>();
        byCode.get(key(code)).path(which).forEach(n -> out.add(n.asText()));
        return out;
    }

    String identity(String code) {
        return line(code, "identity");
    }

    /**
     * The truthful answer to "how do you know my name?" for this call's memory state.
     *
     * @param remembered the name from an earlier call, or null
     * @param thisCall the name the farmer gave on this call, or null
     */
    String nameSource(String code, MemoryGate.State state, String remembered, String thisCall) {
        return switch (state) {
            case NAME_CHECK -> line(code, "nameEarlierCall", remembered) + " " + line(code, "askNameAgain", remembered);
            case CONFIRMED -> thisCall != null && remembered == null
                    ? line(code, "nameThisCall", thisCall) + " " + line(code, "followUp")
                    : remembered != null ? line(code, "nameEarlierCall", remembered) + " " + line(code, "followUp")
                    : line(code, "nameUnknown");
            case NEW, UNNAMED -> thisCall != null ? line(code, "nameThisCall", thisCall) + " " + line(code, "followUp")
                    : line(code, "nameUnknown");
            case WITHHELD -> thisCall != null ? line(code, "nameThisCall", thisCall) : line(code, "nameWithheld");
            case OTHER_SPEAKER -> thisCall != null ? line(code, "nameThisCall", thisCall) : line(code, "nameOtherSpeaker");
            case FORGOTTEN -> line(code, "nameForgotten");
        };
    }

    String greeting(String code, String name) {
        return name == null ? line(code, "greeting") : line(code, "greetingNamed", name);
    }

    String mandi(MandiAnswer answer, String code) {
        return MandiWording.spokenIn(answer, key(code));
    }
}
