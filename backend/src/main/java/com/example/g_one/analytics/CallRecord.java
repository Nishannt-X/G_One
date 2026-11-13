package com.example.g_one.analytics;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Objects;

/**
 * One stored call; every ended call has one. {@code languages} holds a language code per leg that joined (version 3;
 * before it, only for consenting parties). {@code outcome} is "agreed" (both said yes to the terms), "no_deal" (terms
 * heard, not agreed), "info_only" (price, weather or buyer lookups only, no buyer joined) or "no_terms" (no deal
 * discussed). {@code data} holds the derived details (see {@link CallAccumulator}); its shape depends on
 * {@code schemaVersion}: version 1 records (before leg end times, language switches, deal state times, the price
 * reference and the buyer match were kept), version 2 records (stored only with a yes, each party's side only with
 * that party's yes) and version 3 records (every call, before the privacy review) still open, with the newer fields
 * absent; rows written before version 4 are scrubbed once by {@link RecordScrubber}. {@code farmerKey} is the farmer's caller key (already an
 * HMAC of the number, never the number); it is kept only when the farmer said yes, so a "forget me" can find the
 * record, and it is never sent to the browser. A record without it is anonymous.
 */
public record CallRecord(String callId, long startedMs, long endedMs, long durationMs, String endReason,
                         Map<String, String> languages, String outcome, Consent consentFarmer, Consent consentBuyer,
                         @JsonIgnore String farmerKey, JsonNode data, long createdAt, int schemaVersion) {

    /** The shape of {@code data} written now. */
    public static final int SCHEMA_VERSION = 4;

    public CallRecord {
        Objects.requireNonNull(callId, "callId");
        languages = languages == null ? Map.of() : Map.copyOf(languages);
        endReason = endReason == null ? "" : endReason;
        schemaVersion = Math.max(1, schemaVersion);
    }

    /** A record in the current shape. */
    public CallRecord(String callId, long startedMs, long endedMs, long durationMs, String endReason,
                      Map<String, String> languages, String outcome, Consent consentFarmer, Consent consentBuyer,
                      String farmerKey, JsonNode data, long createdAt) {
        this(callId, startedMs, endedMs, durationMs, endReason, languages, outcome, consentFarmer, consentBuyer,
                farmerKey, data, createdAt, SCHEMA_VERSION);
    }

    /**
     * This record with everything linked to the caller removed: no caller key, stated name, memory snapshot or farmer
     * timeline, and {@code notStored.farmer = "forgotten"}. The recorded consent answers are unchanged. Used when the
     * caller was forgotten after the call began.
     */
    public CallRecord withdrawn() {
        ObjectNode d = data.isObject() ? ((ObjectNode) data).deepCopy() : JsonNodeFactory.instance.objectNode();
        d.put("keyed", false);
        if (d.path("notStored").isObject()) {
            ObjectNode notStored = (ObjectNode) d.get("notStored");
            notStored.put("farmer", "forgotten");
            notStored.remove("memory");
        } else {
            d.putObject("notStored").put("farmer", "forgotten");
        }
        if (d.path("farmer").isObject()) {
            ObjectNode f = (ObjectNode) d.get("farmer");
            f.putNull("memory");
            f.remove("statedName");
            f.remove("timings");
        }
        return new CallRecord(callId, startedMs, endedMs, durationMs, endReason, languages, outcome, consentFarmer,
                consentBuyer, null, d, createdAt, schemaVersion);
    }

    /** The same record with other data (and the caller key cleared when {@code keepKey} is false). */
    CallRecord withData(JsonNode newData, boolean keepKey) {
        return new CallRecord(callId, startedMs, endedMs, durationMs, endReason, languages, outcome, consentFarmer,
                consentBuyer, keepKey ? farmerKey : null, newData, createdAt, schemaVersion);
    }

    /** The list row: everything but the details. */
    public Summary summary() {
        JsonNode buyerMatch = data.path("buyer").path("match");
        String buyerName = buyerMatch.path("name").isTextual() ? buyerMatch.get("name").asText()
                : data.path("deal").path("buyerName").isTextual() ? data.path("deal").get("buyerName").asText() : null;
        int cautions = 0;
        for (JsonNode a : data.path("farmer").path("advice")) {
            if (a.path("kind").asText().equals("caution")) cautions++;
        }
        return new Summary(callId, startedMs, endedMs, durationMs, endReason, languages, outcome, consentFarmer,
                consentBuyer, data.path("deal").isObject() ? data.get("deal") : null, buyerName, cautions,
                data.path("farmer").path("tools").size(), consentScope(), termsOnly(), schemaVersion);
    }

    /**
     * "both", "farmer", "buyer" or "none": whose recorded answer to the remembering question was yes. Always read from
     * the stored answers, so every schema version agrees (a later forget withdraws the caller key, not the answer).
     */
    public String consentScope() {
        boolean f = consentFarmer.yes();
        boolean b = consentBuyer.yes();
        return f && b ? "both" : f ? "farmer" : b ? "buyer" : "none";
    }

    /** True when no party's recorded answer was yes: only operational data and the deal terms were kept. */
    public boolean termsOnly() {
        return consentScope().equals("none");
    }

    /**
     * A row of the calls list. {@code deal} is the stored deal card, or null; {@code buyerName} the connected
     * directory buyer (synthetic); {@code cautions} and {@code tools} are counts from the farmer's leg.
     * {@code consentScope} and {@code termsOnly} (version 3) say whose yes covers the record; {@code termsOnly} is true
     * when nobody said yes.
     */
    public record Summary(String callId, long startedMs, long endedMs, long durationMs, String endReason,
                          Map<String, String> languages, String outcome, Consent consentFarmer, Consent consentBuyer,
                          JsonNode deal, String buyerName, int cautions, int tools, String consentScope,
                          boolean termsOnly, int schemaVersion) {}
}
