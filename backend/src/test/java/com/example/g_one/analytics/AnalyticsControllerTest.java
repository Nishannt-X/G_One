package com.example.g_one.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.memory.MemoryController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
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
    @Autowired
    ConsoleBus bus;

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
    void forgettingAFarmerFromTheConsoleDeletesTheirRecords() {
        String key = com.example.g_one.fakes.FakeLeg.FARMER_KEY.value();
        store.save(new CallRecord("call-kept-key", 9000, 10_000, 1000, "hangup", Map.of("farmer", "hi-IN"), "no_terms",
                Consent.YES, Consent.NONE, key, JSON.createObjectNode().putNull("deal"), 11_000));
        assertThat(send(HttpMethod.DELETE, "/api/memory/" + key, CODE).getStatusCode().value()).isEqualTo(204);
        assertThat(store.get("call-kept-key")).isEmpty();
        assertThat(store.get("call-old")).isPresent();
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

    /**
     * A rich call nobody said yes to, with planted secrets in every text field: the list and detail JSON carry its
     * operational data and the terms-only deal card, and none of the words, whisper text, tool arguments, phone number,
     * caller key or remembered profile.
     */
    @Test
    void anUnconsentedCallIsListedWithTermsOnlyAndNoSecrets() throws Exception {
        String phone = "+15005550006";
        CallId c = CallId.of("call-rich");
        LegId f = LegId.of("call-rich-f");
        LegId b = LegId.of("call-rich-b");
        bus.publish(new ConsoleEvent.CallStarted(c, 20_000, "hi-IN"));
        bus.publish(new ConsoleEvent.LegJoined(c, 20_000, f, LegRole.FARMER, LegSource.SIMULATOR, "hi-IN"));
        bus.publish(new ConsoleEvent.MemoryLoaded(c, 20_010, FakeLeg.FARMER_KEY, true,
                List.of(new ProfileFact("name", "CANARY-remembered", Provenance.said(CallId.of("old"), 1))), List.of()));
        bus.publish(new ConsoleEvent.TurnHeard(c, 22_000, f, LegRole.FARMER, "hi-IN", "CANARY-transcript " + phone,
                21_000, 21_500));
        bus.publish(new ConsoleEvent.AssistantSaid(c, 22_500, f, LegRole.FARMER, "hi-IN", "CANARY-reply"));
        bus.publish(new ConsoleEvent.ToolCalled(c, 23_000, "save_profile_fact",
                JSON.createObjectNode().put("field", "name").put("value", "CANARY-stated"), true, "CANARY-result", 2));
        bus.publish(new ConsoleEvent.ToolCalled(c, 23_100, "get_mandi_price",
                JSON.createObjectNode().put("q", "CANARY-argument").put("phone", phone), true, "CANARY-price", 9));
        bus.publish(new ConsoleEvent.WhisperSent(c, 23_500, f, ConsoleEvent.WhisperKind.TIP, "hi-IN", "CANARY-whisper"));
        bus.publish(new ConsoleEvent.AsideAnswered(c, 23_600, f, LegRole.FARMER, "hi-IN", "CANARY-aside", "CANARY-answer",
                23_550));
        bus.publish(new ConsoleEvent.LegJoined(c, 24_000, b, LegRole.BUYER, LegSource.SIMULATOR, "ta-IN"));
        bus.publish(new ConsoleEvent.TurnHeard(c, 25_000, b, LegRole.BUYER, "ta-IN", "CANARY-subtitle", 24_200, 24_600));
        bus.publish(new ConsoleEvent.TurnTranslated(c, 25_400, b, LegRole.BUYER, LegRole.FARMER, "ta-IN", "hi-IN",
                "CANARY-translation", 24_200));
        bus.publish(new ConsoleEvent.DealUpdated(c, 26_000, ConsoleEvent.DealState.DRAFT, "onion", null, "Lasalgaon",
                "10 quintal", new BigDecimal("2100"), "quintal", "Friday", false, false));
        bus.publish(new ConsoleEvent.ConsentRecorded(c, LegRole.FARMER, "NO", 27_000));
        bus.publish(new ConsoleEvent.ConsentRecorded(c, LegRole.BUYER, "NONE", 27_000));
        bus.publish(new ConsoleEvent.CallEnded(c, 27_000, 7000, "hangup"));

        String list = send(HttpMethod.GET, "/api/analytics/calls?limit=10", CODE).getBody();
        String detail = send(HttpMethod.GET, "/api/analytics/calls/call-rich", CODE).getBody();
        JsonNode row = null;
        for (JsonNode r : JSON.readTree(list).get("calls")) {
            if (r.get("callId").asText().equals("call-rich")) row = r;
        }
        assertThat(row).as("the unconsented call is listed").isNotNull();
        assertThat(row.get("termsOnly").asBoolean()).isTrue();
        assertThat(row.get("consentScope").asText()).isEqualTo("none");
        assertThat(row.get("deal").get("label").asText()).isEqualTo("kept without consent: terms only");
        assertThat(row.get("languages").get("buyer").asText()).isEqualTo("ta-IN");
        JsonNode one = JSON.readTree(detail);
        assertThat(one.get("data").get("deal").get("commodity").asText()).isEqualTo("onion");
        assertThat(one.get("data").get("farmer").get("tools")).hasSize(2);
        for (String body : List.of(list, detail)) {
            assertThat(body).doesNotContain("CANARY").doesNotContain(phone).doesNotContain(FakeLeg.FARMER_KEY.value())
                    .doesNotContain("farmerKey").doesNotContain("\"args\"").doesNotContain("\"text\"");
        }
        JsonNode s = JSON.readTree(send(HttpMethod.GET, "/api/analytics/summary", CODE).getBody());
        assertThat(s.get("storedCalls").asInt()).isEqualTo(3);
        assertThat(s.get("callsWithoutYes").asInt()).isEqualTo(1);
        assertThat(s.get("callsWithYes").asInt()).isEqualTo(2);
    }
}
