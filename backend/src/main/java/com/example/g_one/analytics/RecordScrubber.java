package com.example.g_one.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;

/**
 * Brings a record stored before the privacy review (privacy version 0, schema versions 1 to 3) to today's rules, once:
 * no tool results or mandi price text, a deal card of closed values only, a buyer only by directory id, a per-turn
 * timeline only for a party whose recorded answer was yes, and the consent scope read from the recorded answers.
 * Operational fields and the stored shape otherwise stay as they were, so the row still opens.
 */
final class RecordScrubber {
    private RecordScrubber() {}

    static CallRecord scrub(CallRecord r, DealVocabulary vocab, ObjectMapper json) {
        ObjectNode d = r.data().isObject() ? ((ObjectNode) r.data()).deepCopy() : json.createObjectNode();
        boolean termsOnly = r.termsOnly();
        d.put("consentScope", r.consentScope());
        d.put("termsOnly", termsOnly);
        d.put("keyed", r.farmerKey() != null);

        JsonNode price = d.path("price");
        if (price.isObject() && price.path("atMs").isNumber()) {
            ObjectNode p = json.createObjectNode();
            p.put("tool", "get_mandi_price");
            p.put("atMs", price.get("atMs").asLong());
            p.put("offsetMs", Math.max(0, price.get("atMs").asLong() - r.startedMs()));
            d.set("price", p);
        } else if (d.has("price")) {
            d.putNull("price");
        }

        JsonNode match = d.path("buyer").path("match");
        String buyerId = match.path("buyerId").isTextual() ? match.get("buyerId").asText()
                : d.path("deal").path("buyerId").isTextual() ? d.get("deal").get("buyerId").asText() : null;

        if (d.path("deal").isObject()) {
            d.set("deal", deal((ObjectNode) d.get("deal"), vocab, buyerId, termsOnly, json));
        }
        boolean farmerWithdrew = "forgotten".equals(d.path("notStored").path("farmer").asText());
        side(d, "farmer", r.consentFarmer().yes() && !farmerWithdrew, json);
        side(d, "buyer", r.consentBuyer().yes(), json);
        if (d.path("farmer").isObject()) {
            ObjectNode f = (ObjectNode) d.get("farmer");
            for (JsonNode t : f.path("tools")) {
                if (t.isObject()) {
                    ((ObjectNode) t).remove(List.of("result", "args"));
                    ((ObjectNode) t).put("tool", CallAccumulator.word(t.path("tool").asText(null)));
                }
            }
            if (r.farmerKey() == null) {
                f.remove("statedName");
                if (f.has("memory")) f.putNull("memory");
            }
        }
        if (d.path("buyer").isObject()) {
            ObjectNode b = (ObjectNode) d.get("buyer");
            b.remove("match");
            vocab.buyer(buyerId).ifPresent(directory -> {
                ObjectNode m = b.putObject("match");
                m.put("buyerId", directory.id());
                m.put("name", directory.name());
            });
        }
        return r.withData(d, true);
    }

    /** The per-turn timeline only with that party's yes; the medians and counts stay (worked out first if missing). */
    private static void side(ObjectNode d, String role, boolean timeline, ObjectMapper json) {
        if (!d.path(role).isObject()) return;
        ObjectNode s = (ObjectNode) d.get(role);
        if (s.path("timings").isArray() && s.get("timings").size() > 0) {
            s.set("latency", CallAccumulator.latencyJson(json, s.get("timings")));
        }
        if (!timeline) s.remove("timings");
    }

    private static ObjectNode deal(ObjectNode old, DealVocabulary vocab, String buyerId, boolean termsOnly,
                                   ObjectMapper json) {
        ObjectNode d = json.createObjectNode();
        if (old.has("state")) d.set("state", old.get("state"));
        BigDecimal price = old.path("price").isNumber() ? old.get("price").decimalValue() : null;
        DealCard.fill(d, vocab, text(old, "commodity"), text(old, "market"), text(old, "quantity"), price,
                text(old, "unit"), text(old, "pickup"), buyerId);
        for (String keep : List.of("farmerYes", "buyerYes", "atMs", "stateTimes", "readback", "farmerYesMs",
                "buyerYesMs")) {
            if (old.has(keep)) d.set(keep, old.get(keep));
        }
        d.put("termsOnly", termsOnly);
        if (termsOnly) d.put("label", CallAccumulator.TERMS_ONLY_LABEL);
        return d;
    }

    private static String text(JsonNode n, String field) {
        return n.path(field).isTextual() ? n.get(field).asText() : null;
    }
}
