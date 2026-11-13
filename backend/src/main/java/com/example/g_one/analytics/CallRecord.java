package com.example.g_one.analytics;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;

/**
 * One stored call; every ended call has one. {@code languages} holds a language code per leg that joined (version 3;
 * before it, only for consenting parties). {@code outcome} is "agreed" (both said yes to the terms), "no_deal" (terms
 * heard, not agreed), "info_only" (price, weather or buyer lookups only, no buyer joined) or "no_terms" (no deal
 * discussed). {@code data} holds the derived details (see {@link CallAccumulator}); its shape depends on
 * {@code schemaVersion}: version 1 records (before leg end times, language switches, deal state times, the price
 * reference and the buyer match were kept) and version 2 records (stored only with a yes, each party's side only with
 * that party's yes) still open, with the newer fields absent. {@code farmerKey} is the farmer's caller key (already an
 * HMAC of the number, never the number); it is kept only when the farmer said yes, so a "forget me" can find the
 * record, and it is never sent to the browser. A record without it is anonymous.
 */
public record CallRecord(String callId, long startedMs, long endedMs, long durationMs, String endReason,
                         Map<String, String> languages, String outcome, Consent consentFarmer, Consent consentBuyer,
                         @JsonIgnore String farmerKey, JsonNode data, long createdAt, int schemaVersion) {

    /** The shape of {@code data} written now. */
    public static final int SCHEMA_VERSION = 3;

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
     * "both", "farmer", "buyer" or "none": whose yes covers this record. Records before version 3 carry no scope and
     * were stored only with a yes, so it is read from the stored answers.
     */
    public String consentScope() {
        if (data.path("consentScope").isTextual()) {
            return data.get("consentScope").asText();
        }
        boolean f = consentFarmer.yes();
        boolean b = consentBuyer.yes();
        return f && b ? "both" : f ? "farmer" : b ? "buyer" : "none";
    }

    /** True when no party said yes: only operational data and the deal terms were kept. */
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
