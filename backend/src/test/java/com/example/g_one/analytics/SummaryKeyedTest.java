package com.example.g_one.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The list row says whether a caller key is stored ({@code keyed}), never the key itself, so the Calls list can mark a
 * call that is not linked to any farmer; and the summary counts calls with a yes from the recorded answers.
 */
class SummaryKeyedTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RecordSchema SCHEMA = new RecordSchema(Vocabulary.bundled(JSON), JSON);
    private static final String KEY = "d".repeat(64);
    private static final String DATA = "{\"farmer\":{\"legId\":\"f1\",\"source\":\"simulator\",\"language\":\"hi-IN\","
            + "\"joinedMs\":1,\"tools\":[],\"advice\":[]},\"notStored\":{}}";

    private static CallRecord record(String key, Consent farmer, Consent buyer) throws Exception {
        return SCHEMA.project(new CallRecord("call-keyed", 1, 9001, 9000, "hangup", Map.of("farmer", "hi-IN"), "no_terms",
                farmer, buyer, key, JSON.readTree(DATA), 9002));
    }

    private static JsonNode row(CallRecord r) {
        return JSON.valueToTree(r.summary());
    }

    @Test
    void aFarmerYesWithAStoredKeyIsKeyed() throws Exception {
        CallRecord r = record(KEY, Consent.YES, Consent.NONE);
        assertThat(r.summary().keyed()).isTrue();
        assertThat(row(r).get("keyed").isBoolean()).isTrue();
        assertThat(row(r).get("keyed").asBoolean()).isTrue();
        assertThat(JSON.writeValueAsString(r.summary())).doesNotContain(KEY);
    }

    @Test
    void aWithheldFarmerYesIsNotKeyed() throws Exception {
        CallRecord r = record(null, Consent.YES, Consent.NONE);
        assertThat(r.summary().keyed()).isFalse();
        assertThat(row(r).get("keyed").asBoolean()).isFalse();
    }

    @Test
    void aForgottenFarmerYesIsNotKeyed() throws Exception {
        CallRecord r = SCHEMA.project(record(KEY, Consent.YES, Consent.NO).withdrawn());
        assertThat(r.consentFarmer()).isEqualTo(Consent.YES);
        assertThat(r.summary().keyed()).isFalse();
        assertThat(JSON.writeValueAsString(r.summary())).doesNotContain(KEY);
    }

    @Test
    void aMinimalFallbackIsNotKeyed() {
        CallRecord m = SCHEMA.minimal("call-min", 1, 2, 1, Consent.YES, Consent.NONE, 3, 4);
        assertThat(m.summary().keyed()).isFalse();
        assertThat(row(m).get("keyed").asBoolean()).isFalse();
    }

    @Test
    void aCallWithoutAYesIsNotKeyed() throws Exception {
        assertThat(record(null, Consent.NO, Consent.NONE).summary().keyed()).isFalse();
        assertThat(record(null, Consent.NONE, Consent.NONE).summary().keyed()).isFalse();
    }

    @Test
    void callsWithAYesAreCountedFromTheRecordedAnswersEvenForAMinimalRecord() throws Exception {
        CallRecord minimalYes = SCHEMA.minimal("call-min", 1, 2, 1, Consent.YES, Consent.NONE, 3, 4);
        CallRecord minimalBuyerYes = SCHEMA.minimal("call-min-b", 1, 2, 1, Consent.NO, Consent.YES, 3, 4);
        CallRecord none = record(null, Consent.NO, Consent.NONE);
        assertThat(minimalYes.termsOnly()).isTrue();
        AnalyticsSummary s = AnalyticsSummary.of(List.of(minimalYes, minimalBuyerYes, none));
        assertThat(s.callsWithYes()).isEqualTo(2);
        assertThat(s.callsWithoutYes()).isEqualTo(1);
    }
}
