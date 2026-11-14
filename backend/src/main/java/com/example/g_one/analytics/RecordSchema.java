package com.example.g_one.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The one gate every stored call record passes: on save, on the one-time scrub of old rows, on replay from the spool
 * and before any read is served. It rebuilds the record from an allowlist of fields; numbers and booleans are copied
 * as such, and every string is mapped through {@link Vocabulary} to a closed set (or left out). Free text cannot pass:
 * the only strings that are not from a list are the farmer's stated name (only with a caller key, and only when it
 * passes {@link Vocabulary#statedName}) and the farmer memory snapshot (only with a caller key, as the owner's decision
 * in ticket #19 allows; entries holding a phone-like number, an email address or a link are dropped). The projection
 * is idempotent.
 */
final class RecordSchema {
    /** Server-made ids ("call-" + UUID, leg UUIDs): letters, digits, dash, underscore. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private static final Pattern CALLER_KEY = Pattern.compile("[0-9a-f]{64}");
    static final int MAX_GISTS = CallAccumulator.MAX_GISTS;
    static final int MAX_MEMORY_TEXT = 300;
    static final int MAX_FACTS = 20;
    private static final List<String> STAGES = List.of("transcript", "translation", "reply");

    private final Vocabulary vocab;
    private final ObjectMapper json;

    RecordSchema(Vocabulary vocab, ObjectMapper json) {
        this.vocab = vocab;
        this.json = json;
    }

    Vocabulary vocabulary() {
        return vocab;
    }

    /** The record rebuilt from closed values; a record that cannot be rebuilt becomes {@link #minimal}. */
    CallRecord project(CallRecord r) {
        try {
            return rebuild(r);
        } catch (RuntimeException e) {
            return minimal(r.callId(), r.startedMs(), r.endedMs(), r.durationMs(), r.consentFarmer(), r.consentBuyer(),
                    r.createdAt(), r.schemaVersion());
        }
    }

    /**
     * The smallest record a call can have: its id, times, the recorded consent answers and nothing else, marked
     * {@code minimal} and terms only. Used when a record cannot be built, read or rebuilt; it never fails.
     */
    CallRecord minimal(String callId, long startedMs, long endedMs, long durationMs, Consent farmer, Consent buyer,
                       long createdAt, int schemaVersion) {
        ObjectNode d = json.createObjectNode();
        Consent f = farmer == null ? Consent.NONE : farmer;
        Consent b = buyer == null ? Consent.NONE : buyer;
        d.put("consentScope", scope(f, b));
        d.put("termsOnly", true);
        d.put("keyed", false);
        d.put("minimal", true);
        d.putNull("deal");
        d.putObject("notStored");
        d.putNull("farmer");
        d.putNull("buyer");
        return new CallRecord(id(callId), startedMs, endedMs, Math.max(0, durationMs), Vocabulary.OTHER, Map.of(),
                "no_terms", f, b, null, d, createdAt, Math.max(1, schemaVersion));
    }

    private CallRecord rebuild(CallRecord r) {
        JsonNode in = r.data() == null ? MissingNode.getInstance() : r.data();
        String key = r.farmerKey() != null && CALLER_KEY.matcher(r.farmerKey()).matches() ? r.farmerKey() : null;
        boolean minimal = in.path("minimal").asBoolean(false);
        if (minimal) {
            return minimal(r.callId(), r.startedMs(), r.endedMs(), r.durationMs(), r.consentFarmer(), r.consentBuyer(),
                    r.createdAt(), r.schemaVersion());
        }
        boolean farmerYes = r.consentFarmer().yes();
        boolean buyerYes = r.consentBuyer().yes();
        String scope = scope(r.consentFarmer(), r.consentBuyer());
        boolean termsOnly = scope.equals("none");
        String farmerWhy = Vocabulary.closedOrNull(Vocabulary.NOT_STORED_FARMER, text(in.path("notStored"), "farmer"));
        boolean withdrew = "forgotten".equals(farmerWhy);

        ObjectNode d = json.createObjectNode();
        d.put("consentScope", scope);
        d.put("termsOnly", termsOnly);
        d.put("keyed", key != null);
        if (in.path("incomplete").asBoolean(false)) d.put("incomplete", true);
        ObjectNode joined = d.putObject("joined");
        joined.put("farmer", in.path("joined").has("farmer") ? in.path("joined").path("farmer").asBoolean()
                : in.path("farmer").path("legId").isTextual());
        joined.put("buyer", in.path("joined").has("buyer") ? in.path("joined").path("buyer").asBoolean()
                : in.path("buyer").isObject());

        String buyerId = text(in.path("buyer").path("match"), "buyerId");
        if (buyerId == null) buyerId = text(in.path("deal"), "buyerId");
        d.set("deal", in.path("deal").isObject() ? deal(in.path("deal"), buyerId, termsOnly, r.schemaVersion()) : null);
        JsonNode price = in.path("price");
        if (price.path("atMs").isNumber()) {
            ObjectNode p = d.putObject("price");
            p.put("tool", vocab.tool("get_mandi_price"));
            p.put("atMs", price.get("atMs").asLong());
            p.put("offsetMs", Math.max(0, price.get("atMs").asLong() - r.startedMs()));
        } else {
            d.putNull("price");
        }

        ObjectNode notStored = d.putObject("notStored");
        if (key == null) {
            notStored.put("farmer", farmerWhy != null ? farmerWhy : switch (r.consentFarmer()) {
                case YES -> "withheld";
                case NO -> "said_no";
                case NONE -> "no_answer";
            });
        }
        String buyerWhy = Vocabulary.closedOrNull(Vocabulary.NOT_STORED_BUYER, text(in.path("notStored"), "buyer"));
        if (buyerWhy != null) notStored.put("buyer", buyerWhy);

        JsonNode inFarmer = in.path("farmer");
        String identity = Vocabulary.closed(Vocabulary.IDENTITIES, text(inFarmer, "identity"));
        if (key != null && "denied".equals(identity)) notStored.put("memory", "not_this_farmer");
        if (inFarmer.isObject()) {
            ObjectNode f = side(inFarmer, "farmer", farmerYes && !withdrew, r.startedMs());
            f.put("identity", "other".equals(identity) ? "unchecked" : identity);
            ArrayNode tools = f.putArray("tools");
            for (JsonNode t : inFarmer.path("tools")) {
                ObjectNode n = tools.addObject();
                n.put("tool", vocab.tool(text(t, "tool")));
                n.put("ok", t.path("ok").asBoolean(false));
                n.put("durationMs", Math.max(0, t.path("durationMs").asLong(0)));
                long at = t.path("atMs").asLong(r.startedMs());
                n.put("atMs", at);
                n.put("offsetMs", Math.max(0, at - r.startedMs()));
            }
            ArrayNode advice = f.putArray("advice");
            for (JsonNode a : inFarmer.path("advice")) {
                String kind = Vocabulary.closedOrNull(Vocabulary.WHISPER_KINDS, text(a, "kind"));
                if (kind == null) continue;
                ObjectNode n = advice.addObject();
                n.put("kind", kind);
                long at = a.path("atMs").asLong(r.startedMs());
                n.put("atMs", at);
                n.put("offsetMs", Math.max(0, at - r.startedMs()));
            }
            if (key != null && !"denied".equals(identity) && inFarmer.path("memory").isObject()) {
                f.set("memory", memory(inFarmer.path("memory")));
            } else {
                f.putNull("memory");
            }
            if (key != null) {
                Vocabulary.statedName(text(inFarmer, "statedName")).ifPresent(n -> f.put("statedName", n));
            }
            d.set("farmer", f);
        } else {
            d.putNull("farmer");
        }

        JsonNode inBuyer = in.path("buyer");
        if (inBuyer.isObject()) {
            ObjectNode b = side(inBuyer, "buyer", buyerYes, r.startedMs());
            vocab.deals().buyer(buyerId).ifPresent(directory -> {
                ObjectNode m = b.putObject("match");
                m.put("buyerId", directory.id());
                m.put("name", directory.name());
            });
            d.set("buyer", b);
        } else {
            d.putNull("buyer");
        }
        if (in.path("brain").isObject()) d.set("brain", brain(in.path("brain")));

        Map<String, String> languages = new LinkedHashMap<>();
        r.languages().forEach((role, code) -> {
            if (Vocabulary.ROLES.contains(role)) languages.put(role, vocab.language(code));
        });
        return new CallRecord(id(r.callId()), r.startedMs(), r.endedMs(), Math.max(0, r.durationMs()),
                Vocabulary.endReason(r.endReason()), languages, Vocabulary.outcome(r.outcome()), r.consentFarmer(),
                r.consentBuyer(), key, d, r.createdAt(), r.schemaVersion());
    }

    /** A leg: closed values and numbers; the per-turn timeline only when {@code timeline}. */
    private ObjectNode side(JsonNode in, String role, boolean timeline, long callStartedMs) {
        ObjectNode s = json.createObjectNode();
        s.put("legId", idOr(text(in, "legId"), role));
        s.put("source", Vocabulary.closed(Vocabulary.SOURCES, text(in, "source")));
        s.put("language", vocab.language(text(in, "language")));
        s.put("firstLanguage", vocab.language(text(in, "firstLanguage") != null ? text(in, "firstLanguage")
                : text(in, "language")));
        s.put("joinedMs", in.path("joinedMs").asLong(callStartedMs));
        if (in.path("endedMs").isNumber()) s.put("endedMs", in.get("endedMs").asLong());
        if (in.has("endReason")) {
            String reason = text(in, "endReason");
            if (reason == null) s.putNull("endReason");
            else s.put("endReason", Vocabulary.endReason(reason));
        }
        ArrayNode changes = s.putArray("languageChanges");
        for (JsonNode c : in.path("languageChanges")) {
            ObjectNode n = changes.addObject();
            n.put("from", vocab.language(text(c, "from")));
            n.put("to", vocab.language(text(c, "to")));
            n.put("reason", Vocabulary.languageReason(text(c, "reason")));
            n.put("atMs", c.path("atMs").asLong(callStartedMs));
        }
        JsonNode inTimings = in.path("timings");
        List<JsonNode> kept = new ArrayList<>();
        if (inTimings.isArray()) {
            int n = 0;
            for (JsonNode t : inTimings) {
                ObjectNode o = json.createObjectNode();
                n++;
                o.put("key", role + "#" + n);
                o.put("legId", idOr(text(t, "legId"), role));
                o.put("role", role);
                o.put("startedMs", t.path("startedMs").asLong(0));
                o.put("endedMs", t.path("endedMs").asLong(0));
                o.put("heardMs", t.path("heardMs").asLong(0));
                number(o, t, "translatedMs");
                number(o, t, "repliedMs");
                kept.add(o);
            }
        }
        if (!kept.isEmpty()) {
            s.set("latency", CallAccumulator.latencyJson(json, kept));
        } else {
            s.set("latency", latency(in.path("latency")));
        }
        if (timeline && inTimings.isArray()) {
            ArrayNode timings = s.putArray("timings");
            kept.forEach(timings::add);
        }
        return s;
    }

    private ObjectNode latency(JsonNode in) {
        ObjectNode l = json.createObjectNode();
        l.put("turns", Math.max(0, in.path("turns").asInt(0)));
        for (String stage : STAGES) {
            if (in.path(stage).isNumber()) {
                l.put(stage, Math.max(0, in.get(stage).asLong()));
                if (in.path(stage + "Count").isNumber()) l.put(stage + "Count", Math.max(0, in.get(stage + "Count").asInt()));
            }
        }
        return l;
    }

    private ObjectNode brain(JsonNode in) {
        ObjectNode b = json.createObjectNode();
        b.put("turns", Math.max(0, in.path("turns").asInt(0)));
        if (in.path("latencyMs").isNumber()) b.put("latencyMs", Math.max(0, in.get("latencyMs").asLong()));
        for (String n : List.of("modelCalls", "promptTokens", "completionTokens")) {
            b.put(n, Math.max(0, in.path(n).asLong(0)));
        }
        b.set("handlers", counts(in.path("handlers"), true));
        b.set("failures", counts(in.path("failures"), false));
        return b;
    }

    private ObjectNode counts(JsonNode in, boolean handlers) {
        Map<String, Integer> out = new java.util.TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = in.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String k = handlers ? Vocabulary.handler(e.getKey()) : Vocabulary.failure(e.getKey());
            out.merge(k, Math.max(0, e.getValue().asInt(0)), Integer::sum);
        }
        return json.valueToTree(out);
    }

    private ObjectNode memory(JsonNode in) {
        ObjectNode m = json.createObjectNode();
        m.put("returning", in.path("returning").asBoolean(false));
        ArrayNode facts = m.putArray("facts");
        for (JsonNode f : in.path("facts")) {
            if (facts.size() >= MAX_FACTS) break;
            String field = text(f, "field");
            String value = text(f, "value");
            if (field == null || value == null) continue;
            if (!Vocabulary.FACT_FIELDS.contains(field) && !field.startsWith("crop:")) continue;
            if (field.startsWith("crop:") && vocab.deals().crop(field.substring(5)) == null) continue;
            if (!memoryText(value)) continue;
            ObjectNode n = facts.addObject();
            n.put("field", field.startsWith("crop:") ? "crop:" + vocab.deals().crop(field.substring(5)) : field);
            n.put("value", value);
            ObjectNode p = n.putObject("provenance");
            p.put("callId", id(text(f.path("provenance"), "callId")));
            p.put("saidOrInferred", "inferred".equals(text(f.path("provenance"), "saidOrInferred")) ? "inferred" : "said");
            p.put("atMs", f.path("provenance").path("atMs").asLong(0));
        }
        ArrayNode gists = m.putArray("gists");
        for (JsonNode g : in.path("gists")) {
            if (gists.size() >= MAX_GISTS) break;
            String summary = text(g, "summary");
            if (summary == null || !memoryText(summary)) continue;
            ObjectNode n = gists.addObject();
            n.put("callId", id(text(g, "callId")));
            n.put("startedMs", g.path("startedMs").asLong(0));
            n.put("endedMs", g.path("endedMs").asLong(0));
            n.put("summary", summary);
            ArrayNode follow = n.putArray("followUps");
            for (JsonNode u : g.path("followUps")) {
                if (u.isTextual() && memoryText(u.asText())) follow.add(u.asText());
            }
        }
        return m;
    }

    /** Remembered text (farmer's yes only): bounded, and never holding a phone-like number, email address or link. */
    private static boolean memoryText(String s) {
        return !s.isBlank() && s.length() <= MAX_MEMORY_TEXT && !DealCard.unsafe(s);
    }

    private ObjectNode deal(JsonNode in, String buyerId, boolean termsOnly, int schemaVersion) {
        ObjectNode d = json.createObjectNode();
        d.put("state", Vocabulary.closed(Vocabulary.DEAL_STATES, text(in, "state")));
        DealCard.Quantity q = null;
        if (in.path("quantityValue").isNumber()) {
            DealCard.Unit u = DealCard.unit(text(in, "quantityUnit")).orElse(null);
            BigDecimal v = in.get("quantityValue").decimalValue();
            if (u != null && v.signum() > 0 && v.compareTo(DealCard.MAX_QUANTITY) <= 0) q = new DealCard.Quantity(v, u);
        }
        if (q == null) q = DealCard.quantity(text(in, "quantity"));
        DealCard.Pickup pickup = enumOr(DealCard.Pickup.class, text(in, "pickupKind"));
        DealCard.Payment payment = enumOr(DealCard.Payment.class, text(in, "paymentKind"));
        if (schemaVersion < 4 && !in.has("pickupKind") && !in.has("paymentKind")) {
            // A row from before the closed kinds (schema 4): read its combined words once, as the console wrote them.
            // From schema 4 on, a missing kind means it was left out as unsafe, and stays out.
            DealCard.Kinds kinds = DealCard.kinds(text(in, "pickup"));
            pickup = kinds.pickup();
            payment = kinds.payment();
        }
        BigDecimal price = in.path("price").isNumber() ? in.get("price").decimalValue() : null;
        DealCard.fillClosed(d, vocab.deals(), text(in, "commodity"), text(in, "market"), q, price, text(in, "unit"),
                pickup, payment, buyerId);
        d.put("farmerYes", in.path("farmerYes").asBoolean(false));
        d.put("buyerYes", in.path("buyerYes").asBoolean(false));
        if (in.path("atMs").isNumber()) d.put("atMs", in.get("atMs").asLong());
        ObjectNode states = d.putObject("stateTimes");
        for (Iterator<Map.Entry<String, JsonNode>> it = in.path("stateTimes").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (Vocabulary.DEAL_STATES.contains(e.getKey()) && e.getValue().isNumber()) {
                states.put(e.getKey(), e.getValue().asLong());
            }
        }
        d.put("readback", in.path("readback").asBoolean(false));
        number(d, in, "farmerYesMs");
        number(d, in, "buyerYesMs");
        d.put("termsOnly", termsOnly);
        if (termsOnly) d.put("label", CallAccumulator.TERMS_ONLY_LABEL);
        return d;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String wire) {
        if (wire == null) return null;
        try {
            return Enum.valueOf(type, wire.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void number(ObjectNode out, JsonNode in, String field) {
        if (in.path(field).isNumber()) out.put(field, in.get(field).asLong());
        else if (in.has(field)) out.putNull(field);
    }

    static String scope(Consent farmer, Consent buyer) {
        boolean f = farmer.yes();
        boolean b = buyer.yes();
        return f && b ? "both" : f ? "farmer" : b ? "buyer" : "none";
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isTextual() ? v.asText() : null;
    }

    private static String idOr(String id, String fallback) {
        return id != null && ID.matcher(id).matches() ? id : fallback;
    }

    /**
     * A server-made id ({@code CallId.random()}, a UUID) as it is; anything else as a stable digest without digits,
     * so a primary key never carries text.
     */
    static String id(String id) {
        if (id != null && ID.matcher(id).matches()) return id;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(String.valueOf(id).getBytes(StandardCharsets.UTF_8));
            return "call-" + HexFormat.of().formatHex(h, 0, 12).replaceAll("[0-9]", "x");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
