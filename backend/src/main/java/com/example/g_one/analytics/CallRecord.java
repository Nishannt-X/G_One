package com.example.g_one.analytics;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;

/**
 * One stored call. {@code languages} holds a language code per role, only for consenting parties. {@code outcome} is
 * "agreed" (both said yes to the terms), "no_deal" (terms heard, not agreed), "info_only" (price, weather or buyer
 * lookups only, no buyer joined) or "no_terms". {@code data} holds the derived details allowed by consent (see
 * {@link CallAccumulator}); its shape depends on {@code schemaVersion}: version 1 records (before leg end times,
 * language switches, deal state times, the price reference and the buyer match were kept) still open, with those
 * fields absent. {@code farmerKey} is the farmer's caller key (already an HMAC of the number, never the number); it is
 * kept only when the farmer said yes, so a "forget me" can find the record, and it is never sent to the browser.
 */
public record CallRecord(String callId, long startedMs, long endedMs, long durationMs, String endReason,
                         Map<String, String> languages, String outcome, Consent consentFarmer, Consent consentBuyer,
                         @JsonIgnore String farmerKey, JsonNode data, long createdAt, int schemaVersion) {

    /** The shape of {@code data} written now. */
    public static final int SCHEMA_VERSION = 2;

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
        int cautions = 0;
        for (JsonNode a : data.path("farmer").path("advice")) {
            if (a.path("kind").asText().equals("caution")) cautions++;
        }
        return new Summary(callId, startedMs, endedMs, durationMs, endReason, languages, outcome, consentFarmer,
                consentBuyer, data.path("deal").isObject() ? data.get("deal") : null,
                buyerMatch.path("name").isTextual() ? buyerMatch.get("name").asText() : null, cautions,
                data.path("farmer").path("tools").size());
    }

    /**
     * A row of the calls list. {@code deal} is the stored deal terms, or null; {@code buyerName} the connected directory
     * buyer (buyer's yes only); {@code cautions} and {@code tools} are counts from the farmer's side (farmer's yes only).
     */
    public record Summary(String callId, long startedMs, long endedMs, long durationMs, String endReason,
                          Map<String, String> languages, String outcome, Consent consentFarmer, Consent consentBuyer,
                          JsonNode deal, String buyerName, int cautions, int tools) {}
}
