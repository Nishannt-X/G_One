package com.example.g_one.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Privacy review round 2, B1: the projection every stored, scrubbed, replayed and served record passes. A raw record
 * with planted text in every string field it could hold comes out with closed values only. Synthetic data only.
 */
class RecordSchemaTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RecordSchema SCHEMA = new RecordSchema(Vocabulary.bundled(JSON), JSON);
    private static final String P = "Ramesh 9876543210 ramesh@example.org";
    private static final String KEY = "c".repeat(64);

    /** Planted text in every string field of every object the record shape has. */
    private static CallRecord planted(String key, String farmerConsent) throws Exception {
        String data = """
                {"consentScope":"P","termsOnly":"P","keyed":"P","P":"P",
                 "joined":{"farmer":true,"buyer":true,"P":"P"},
                 "deal":{"state":"P","commodity":"P","variety":"P","market":"P","quantity":"P","price":2100,
                  "unit":"P","basis":"P","currency":"P","priceBasis":"P","total":"P","pickup":"P","payment":"P",
                  "pickupKind":"P","paymentKind":"P","buyerId":"P","buyerName":"P","label":"P","farmerYes":true,
                  "stateTimes":{"P":1,"agreed":2},"P":"P"},
                 "price":{"reference":"P","atMs":3000,"tool":"P"},
                 "notStored":{"farmer":"P","buyer":"P","memory":"P"},
                 "farmer":{"legId":"P","source":"P","language":"P","firstLanguage":"P","endReason":"P",
                  "languageChanges":[{"from":"P","to":"P","reason":"P","atMs":1}],
                  "timings":[{"key":"P","legId":"P","role":"P","startedMs":1,"endedMs":2,"heardMs":3,"P":"P"}],
                  "latency":{"turns":1,"P":"P"},"identity":"P",
                  "tools":[{"tool":"P","ok":true,"durationMs":1,"atMs":2,"result":"P","args":"P"}],
                  "advice":[{"kind":"P","atMs":1},{"kind":"caution","atMs":2,"text":"P"}],
                  "memory":{"returning":true,"facts":[{"field":"P","value":"P"},{"field":"village","value":"P"},
                   {"field":"name","value":"Suresh","provenance":{"callId":"P","saidOrInferred":"P","atMs":1}}],
                   "gists":[{"callId":"P","summary":"P","followUps":["P"]},{"callId":"c1","summary":"Asked about onion.",
                   "followUps":["P","Call back"]}]},
                  "statedName":"P","P":"P"},
                 "buyer":{"legId":"P","match":{"buyerId":"P","name":"P"},"P":"P"},
                 "brain":{"turns":1,"handlers":{"P":1,"model":2},"failures":{"P":1},"model":"P","P":"P"}}
                """.replace("\"P\"", JSON.writeValueAsString(P));
        return new CallRecord(P, 1000, 2000, 1000, P, Map.of("farmer", P, "buyer", P, P, P), P,
                Consent.parse(farmerConsent), Consent.NONE, key, JSON.readTree(data), 3000, 3);
    }

    @Test
    void nothingPlantedSurvivesTheProjection() throws Exception {
        for (String key : new String[] {null, KEY, P}) {
            for (String consent : List.of("YES", "NO", "NONE")) {
                CallRecord out = SCHEMA.project(planted(key, consent));
                String all = out.toString() + JSON.writeValueAsString(out) + JSON.writeValueAsString(out.summary());
                for (String token : List.of("Ramesh", "9876543210", "ramesh@example.org", "\"P\"", "reference",
                        "result", "args", "\"text\"", "\"model\":\"")) {
                    assertThat(all).as(key + " " + consent + " " + token).doesNotContain(token);
                }
                assertThat(out.callId()).startsWith("call-").doesNotContainPattern("[0-9]");
                assertThat(out.endReason()).isEqualTo("other");
                assertThat(out.outcome()).isEqualTo("no_terms");
                assertThat(out.languages()).containsOnlyKeys("farmer", "buyer").containsValues("other");
                JsonNode f = out.data().get("farmer");
                assertThat(f.get("tools").get(0).get("tool").asText()).isEqualTo("other");
                assertThat(f.get("advice")).hasSize(1);
                assertThat(f.get("identity").asText()).isEqualTo("unchecked");
                assertThat(out.data().get("brain").get("handlers").fieldNames()).toIterable()
                        .containsExactlyInAnyOrder("other", "model");
                if (KEY.equals(key)) {
                    assertThat(out.farmerKey()).isEqualTo(KEY);
                    assertThat(f.get("memory").get("facts")).extracting(n -> n.get("value").asText())
                            .containsExactly("Suresh");
                    assertThat(f.get("memory").get("gists")).extracting(n -> n.get("summary").asText())
                            .containsExactly("Asked about onion.");
                } else {
                    assertThat(out.farmerKey()).isNull();
                    assertThat(f.get("memory").isNull()).isTrue();
                }
            }
        }
    }

    @Test
    void theProjectionIsIdempotent() throws Exception {
        for (String consent : List.of("YES", "NO")) {
            CallRecord once = SCHEMA.project(planted(KEY, consent));
            CallRecord twice = SCHEMA.project(once);
            assertThat(JSON.writeValueAsString(twice)).isEqualTo(JSON.writeValueAsString(once));
            assertThat(twice.farmerKey()).isEqualTo(once.farmerKey());
        }
    }

    @Test
    void aMinimalRecordNeverFailsAndHoldsOnlyTimesAndAnswers() throws Exception {
        CallRecord m = SCHEMA.minimal(null, 1, 2, -5, null, Consent.YES, 3, 0);
        assertThat(m.consentFarmer()).isEqualTo(Consent.NONE);
        assertThat(m.consentBuyer()).isEqualTo(Consent.YES);
        assertThat(m.durationMs()).isZero();
        assertThat(m.termsOnly()).isTrue();
        assertThat(m.consentScope()).isEqualTo("buyer");
        assertThat(((ObjectNode) m.data()).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("consentScope", "termsOnly", "keyed", "minimal", "deal", "notStored",
                        "farmer", "buyer");
        assertThat(SCHEMA.project(m).data()).isEqualTo(m.data());
    }

    @Test
    void theSummaryLabelsItsMedianAsApproximateAcrossCalls() throws Exception {
        CallRecord a = SCHEMA.project(planted(KEY, "YES"));
        CallRecord b = SCHEMA.project(new CallRecord("call-b", 1, 2, 1, "hangup", Map.of(), "no_terms", Consent.NO,
                Consent.NO, null, JSON.readTree("{\"farmer\":{\"latency\":{\"turns\":2,\"reply\":700,\"replyCount\":2}}}"),
                3));
        assertThat(AnalyticsSummary.of(List.of(a)).approximate()).isFalse();
        AnalyticsSummary two = AnalyticsSummary.of(List.of(a, b));
        assertThat(two.approximate()).isTrue();
        assertThat(JSON.valueToTree(two).get("approximate").asBoolean()).isTrue();
    }
}
