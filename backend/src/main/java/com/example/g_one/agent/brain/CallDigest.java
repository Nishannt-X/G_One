package com.example.g_one.agent.brain;

import com.example.g_one.agent.AgentBrain.Line;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Earlier lines of a call folded into one short message, so the model keeps the thread without every old line being
 * resent on every turn. No model call: stated facts plus clipped earlier turns.
 */
final class CallDigest {
    private static final int CLIP_CHARS = 60;
    private static final int KEEP_FARMER = 3;
    private static final int KEEP_BUYER = 2;

    private CallDigest() {}

    /** Empty when there is nothing older than the replayed lines and nothing saved on this call. */
    static String of(List<Line> older, Map<String, String> factsThisCall) {
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
        StringJoiner out = new StringJoiner(" ");
        if (!factsThisCall.isEmpty()) {
            StringJoiner facts = new StringJoiner("; ");
            new LinkedHashMap<>(factsThisCall).forEach((k, v) -> facts.add(k + ":" + v));
            out.add("Stated on this call: " + facts + ".");
        }
        if (!farmer.isEmpty()) out.add("Earlier the farmer said: " + last(farmer, KEEP_FARMER) + ".");
        if (!buyer.isEmpty()) out.add("Earlier the buyer said: " + last(buyer, KEEP_BUYER) + ".");
        return out.toString();
    }

    private static String last(List<String> lines, int n) {
        return String.join(" | ", lines.subList(Math.max(0, lines.size() - n), lines.size()));
    }

    private static String clip(String s) {
        String flat = s.replaceAll("\\s+", " ").strip();
        if (flat.codePointCount(0, flat.length()) <= CLIP_CHARS) return flat;
        return flat.substring(0, flat.offsetByCodePoints(0, CLIP_CHARS));
    }
}
