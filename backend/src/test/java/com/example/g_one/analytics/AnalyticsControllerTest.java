package com.example.g_one.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.memory.MemoryController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** The analytics endpoints behind the access code, on the in-memory store. Synthetic data only. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "g-one.memory.store=in-memory",
    "g-one.memory.access-code=test-access-code-not-a-secret",
})
class AnalyticsControllerTest {
    private static final String CODE = "test-access-code-not-a-secret";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    TestRestTemplate http;
    @Autowired
    CallRecordStore store;

    @BeforeEach
    void seed() {
        store.list(1000).forEach(r -> store.delete(r.callId()));
        var data = JSON.createObjectNode();
        data.putObject("deal").put("commodity", "onion").put("farmerYes", true).put("buyerYes", true);
        data.putObject("farmer").putArray("tools").addObject().put("tool", "get_mandi_price");
        data.putNull("buyer");
        store.save(new CallRecord("call-old", 1000, 61_000, 60_000, "deal_agreed",
                Map.of("farmer", "hi-IN"), "agreed", Consent.YES, Consent.NO, "secret-key-never-shown", data, 70_000));
        store.save(new CallRecord("call-new", 5000, 125_000, 120_000, "hangup",
                Map.of("buyer", "ta-IN"), "no_terms", Consent.NONE, Consent.YES, null,
                JSON.createObjectNode().putNull("deal").putNull("farmer"), 130_000));
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String code) {
        HttpHeaders headers = new HttpHeaders();
        if (code != null) headers.set(MemoryController.HEADER, code);
        return http.exchange(path, method, new HttpEntity<>(headers), String.class);
    }

    @Test
    void needsTheAccessCode() {
        for (String path : new String[] {"/api/analytics/calls", "/api/analytics/calls/call-old", "/api/analytics/summary"}) {
            assertThat(send(HttpMethod.GET, path, null).getStatusCode().value()).isEqualTo(401);
            assertThat(send(HttpMethod.GET, path, "wrong").getStatusCode().value()).isEqualTo(401);
        }
        assertThat(send(HttpMethod.DELETE, "/api/analytics/calls/call-old", null).getStatusCode().value()).isEqualTo(401);
        assertThat(store.get("call-old")).isPresent();
    }

    @Test
    void listsNewestFirstAndShowsOneCall() throws Exception {
        ResponseEntity<String> res = send(HttpMethod.GET, "/api/analytics/calls?limit=10", CODE);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode calls = JSON.readTree(res.getBody()).get("calls");
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).get("callId").asText()).isEqualTo("call-new");
        assertThat(calls.get(1).get("deal").get("commodity").asText()).isEqualTo("onion");
        assertThat(calls.get(1).get("consentFarmer").asText()).isEqualTo("YES");
        assertThat(res.getBody()).doesNotContain("secret-key-never-shown").doesNotContain("farmerKey");

        JsonNode one = JSON.readTree(send(HttpMethod.GET, "/api/analytics/calls/call-old", CODE).getBody());
        assertThat(one.get("data").get("farmer").get("tools").get(0).get("tool").asText()).isEqualTo("get_mandi_price");
        assertThat(one.toString()).doesNotContain("secret-key-never-shown");
        assertThat(send(HttpMethod.GET, "/api/analytics/calls/nope", CODE).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void deletesOneCall() {
        assertThat(send(HttpMethod.DELETE, "/api/analytics/calls/call-old", CODE).getStatusCode().value()).isEqualTo(204);
        assertThat(store.get("call-old")).isEmpty();
        assertThat(send(HttpMethod.DELETE, "/api/analytics/calls/call-old", CODE).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void summarisesStoredCallsOnly() throws Exception {
        JsonNode s = JSON.readTree(send(HttpMethod.GET, "/api/analytics/summary", CODE).getBody());
        assertThat(s.get("storedCalls").asInt()).isEqualTo(2);
        assertThat(s.get("bothYesDeals").asInt()).isEqualTo(1);
        assertThat(s.get("averageDurationMs").asLong()).isEqualTo(90_000);
        assertThat(s.get("languagePairs").get("hi-IN").asInt()).isEqualTo(1);
        assertThat(s.get("toolCounts").get("get_mandi_price").asInt()).isEqualTo(1);
    }
}
