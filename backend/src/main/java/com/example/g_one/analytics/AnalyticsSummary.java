package com.example.g_one.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Figures over the stored records, which since schema version 3 means every call. {@code medianMs} holds the measured
 * median per stage across every stored turn: {@code transcript} (end of speech to transcript), {@code translation} and
 * {@code reply} (transcript to the assistant's answer); a stage with no measurement is absent. {@code callsWithYes}
 * counts calls at least one party said yes to; {@code callsWithoutYes} the rest, kept as terms only.
 * {@code bothYesDeals} counts deals both parties confirmed on the call (not the remembering question).
 */
public record AnalyticsSummary(int storedCalls, int bothYesDeals, long averageDurationMs,
                               Map<String, Integer> languagePairs, Map<String, Long> medianMs,
                               Map<String, Integer> toolCounts, int callsWithYes, int callsWithoutYes) {

    static AnalyticsSummary of(List<CallRecord> records) {
        int agreed = 0;
        int withYes = 0;
        long total = 0;
        Map<String, Integer> pairs = new TreeMap<>();
        Map<String, Integer> tools = new TreeMap<>();
        List<Long> transcript = new ArrayList<>();
        List<Long> translation = new ArrayList<>();
        List<Long> reply = new ArrayList<>();
        for (CallRecord r : records) {
            JsonNode deal = r.data().path("deal");
            if (deal.path("farmerYes").asBoolean() && deal.path("buyerYes").asBoolean()) {
                agreed++;
            }
            if (!r.termsOnly()) withYes++;
            total += r.durationMs();
            pairs.merge(pair(r.languages()), 1, Integer::sum);
            for (String role : List.of("farmer", "buyer")) {
                for (JsonNode t : r.data().path(role).path("timings")) {
                    long heard = t.path("heardMs").asLong();
                    transcript.add(Math.max(0, heard - t.path("endedMs").asLong()));
                    if (t.path("translatedMs").isNumber()) {
                        translation.add(Math.max(0, t.get("translatedMs").asLong() - heard));
                    }
                    if (t.path("repliedMs").isNumber()) {
                        reply.add(Math.max(0, t.get("repliedMs").asLong() - heard));
                    }
                }
            }
            for (JsonNode t : r.data().path("farmer").path("tools")) {
                tools.merge(t.path("tool").asText(), 1, Integer::sum);
            }
        }
        Map<String, Long> medians = new TreeMap<>();
        putMedian(medians, "transcript", transcript);
        putMedian(medians, "translation", translation);
        putMedian(medians, "reply", reply);
        return new AnalyticsSummary(records.size(), agreed, records.isEmpty() ? 0 : total / records.size(), pairs,
                medians, tools, withYes, records.size() - withYes);
    }

    /** "hi-IN ⇄ ta-IN" when both languages are stored, else the one stored, else "unknown". */
    static String pair(Map<String, String> languages) {
        String f = languages.get("farmer");
        String b = languages.get("buyer");
        if (f != null && b != null) return f + " ⇄ " + b;
        if (f != null) return f;
        return b != null ? b : "unknown";
    }

    private static void putMedian(Map<String, Long> out, String key, List<Long> values) {
        if (values.isEmpty()) {
            return;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        out.put(key, sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2);
    }
}
