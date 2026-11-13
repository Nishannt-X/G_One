package com.example.g_one.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Figures over the stored records, which since schema version 3 means every call. {@code medianMs} holds, per stage,
 * the median over every call's measured turns: {@code transcript} (end of speech to transcript), {@code translation}
 * and {@code reply} (transcript to the assistant's answer); a stage with no measurement is absent. Each call keeps only
 * its per-stage medians and counts, so the figure is the median of those medians weighted by their counts (exact for
 * one call; a close estimate across calls). {@code callsWithYes} counts calls at least one party said yes to;
 * {@code callsWithoutYes} the rest, kept as terms only. {@code bothYesDeals} counts deals both parties confirmed on the
 * call (not the remembering question). {@code unsavedCalls} counts finished calls whose record is still queued for a
 * retry after a failed save.
 */
public record AnalyticsSummary(int storedCalls, int bothYesDeals, long averageDurationMs,
                               Map<String, Integer> languagePairs, Map<String, Long> medianMs,
                               Map<String, Integer> toolCounts, int callsWithYes, int callsWithoutYes,
                               int unsavedCalls) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> STAGES = List.of("transcript", "translation", "reply");

    static AnalyticsSummary of(List<CallRecord> records) {
        return of(records, 0);
    }

    static AnalyticsSummary of(List<CallRecord> records, int unsaved) {
        int agreed = 0;
        int withYes = 0;
        long total = 0;
        Map<String, Integer> pairs = new TreeMap<>();
        Map<String, Integer> tools = new TreeMap<>();
        Map<String, List<long[]>> weighted = new TreeMap<>();
        STAGES.forEach(s -> weighted.put(s, new ArrayList<>()));
        for (CallRecord r : records) {
            JsonNode deal = r.data().path("deal");
            if (deal.path("farmerYes").asBoolean() && deal.path("buyerYes").asBoolean()) {
                agreed++;
            }
            if (!r.termsOnly()) withYes++;
            total += r.durationMs();
            pairs.merge(pair(r.languages()), 1, Integer::sum);
            for (String role : List.of("farmer", "buyer")) {
                JsonNode side = r.data().path(role);
                JsonNode latency = side.path("latency");
                if (side.path("timings").isArray() && side.get("timings").size() > 0) {
                    latency = CallAccumulator.latencyJson(JSON, side.get("timings"));
                }
                for (String stage : STAGES) {
                    if (!latency.path(stage).isNumber()) continue;
                    long count = latency.path(stage + "Count").isNumber() ? latency.get(stage + "Count").asLong()
                            : Math.max(1, latency.path("turns").asLong(1));
                    weighted.get(stage).add(new long[] {latency.get(stage).asLong(), Math.max(1, count)});
                }
            }
            for (JsonNode t : r.data().path("farmer").path("tools")) {
                tools.merge(t.path("tool").asText(), 1, Integer::sum);
            }
        }
        Map<String, Long> medians = new TreeMap<>();
        weighted.forEach((stage, values) -> {
            if (!values.isEmpty()) medians.put(stage, weightedMedian(values));
        });
        return new AnalyticsSummary(records.size(), agreed, records.isEmpty() ? 0 : total / records.size(), pairs,
                medians, tools, withYes, records.size() - withYes, unsaved);
    }

    /** "hi-IN ⇄ ta-IN" when both languages are stored, else the one stored, else "unknown". */
    static String pair(Map<String, String> languages) {
        String f = languages.get("farmer");
        String b = languages.get("buyer");
        if (f != null && b != null) return f + " ⇄ " + b;
        if (f != null) return f;
        return b != null ? b : "unknown";
    }

    /** The median of values each repeated {@code count} times ({value, count} pairs), like {@link CallAccumulator}'s. */
    static long weightedMedian(List<long[]> values) {
        List<long[]> sorted = values.stream().sorted((a, b) -> Long.compare(a[0], b[0])).toList();
        long n = sorted.stream().mapToLong(v -> v[1]).sum();
        long lo = (n - 1) / 2;
        long hi = n / 2;
        return (valueAt(sorted, lo) + valueAt(sorted, hi)) / 2;
    }

    private static long valueAt(List<long[]> sorted, long index) {
        long seen = 0;
        for (long[] v : sorted) {
            seen += v[1];
            if (index < seen) return v[0];
        }
        return sorted.get(sorted.size() - 1)[0];
    }
}
