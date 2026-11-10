package com.example.g_one.agent.brain;

import com.example.g_one.agent.AgentBrain.Line;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Earlier lines of a call folded into one short JSON object, so the model keeps the thread without every old line being
 * resent on every turn. No model call: stated facts plus clipped earlier turns. It goes into the data message
 * ({@link com.example.g_one.agent.tools.memory.MemoryBlock#dataMessage}), never the system prompt: it holds what callers
 * said, which is data, not instructions.
 */
final class CallDigest {
    private static final int CLIP_CHARS = 60;
    private static final int KEEP_FARMER = 3;
    private static final int KEEP_BUYER = 2;

    private CallDigest() {}

    /** Empty when there is nothing older than the replayed lines and nothing saved on this call. */
    static ObjectNode of(List<Line> older, Map<String, String> factsThisCall) {
        List<String> farmer = new ArrayList<>();
        List<String> buyer = new ArrayList<>();
        for (Line l : older) {
            if (l == null || l.text().isBlank()) continue;
            switch (l.speaker()) {
                case FARMER -> farmer.add(clip(l.text()));
                case BUYER -> buyer.add(clip(l.text()));
                default -> { }
            }
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (!factsThisCall.isEmpty()) {
            ObjectNode facts = out.putObject("statedThisCall");
            new LinkedHashMap<>(factsThisCall).forEach((k, v) -> facts.put(clip(k), clip(v)));
        }
        if (!farmer.isEmpty()) last(out.putArray("farmerSaidEarlier"), farmer, KEEP_FARMER);
        if (!buyer.isEmpty()) last(out.putArray("buyerSaidEarlier"), buyer, KEEP_BUYER);
        return out;
    }

    private static void last(ArrayNode into, List<String> lines, int n) {
        lines.subList(Math.max(0, lines.size() - n), lines.size()).forEach(into::add);
    }

    private static String clip(String s) {
        String flat = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        if (flat.codePointCount(0, flat.length()) <= CLIP_CHARS) return flat;
        return flat.substring(0, flat.offsetByCodePoints(0, CLIP_CHARS));
    }
}
