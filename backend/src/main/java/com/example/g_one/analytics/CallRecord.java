package com.example.g_one.analytics;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;

/**
 * One stored call. {@code languages} holds a language code per role, only for consenting parties. {@code outcome} is
 * "agreed" (both said yes to the terms), "no_deal" (terms heard, not agreed) or "no_terms". {@code data} holds the
 * derived details allowed by consent (see {@link CallAccumulator}). {@code farmerKey} is the farmer's caller key (already
 * an HMAC of the number, never the number); it is kept only when the farmer said yes, so a "forget me" can find the
 * record, and it is never sent to the browser.
 */
public record CallRecord(String callId, long startedMs, long endedMs, long durationMs, String endReason,
                         Map<String, String> languages, String outcome, Consent consentFarmer, Consent consentBuyer,
                         @JsonIgnore String farmerKey, JsonNode data, long createdAt) {

    public CallRecord {
        Objects.requireNonNull(callId, "callId");
        languages = languages == null ? Map.of() : Map.copyOf(languages);
        endReason = endReason == null ? "" : endReason;
    }

    /** The list row: everything but the details. */
    public Summary summary() {
        return new Summary(callId, startedMs, endedMs, durationMs, endReason, languages, outcome, consentFarmer,
                consentBuyer, data.path("deal").isObject() ? data.get("deal") : null);
    }

    /** A row of the calls list. {@code deal} is the stored deal terms, or null. */
    public record Summary(String callId, long startedMs, long endedMs, long durationMs, String endReason,
                          Map<String, String> languages, String outcome, Consent consentFarmer, Consent consentBuyer,
                          JsonNode deal) {}
}
