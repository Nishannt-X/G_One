package com.example.g_one.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.memory.MemoryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Privacy review round 2, B3 and B4, through the public writer and controller: a long store outage loses no record,
 * and a malformed old row neither aborts the one-time scrub nor stays readable. Synthetic data only.
 */
class CallRecordDurabilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long T0 = 1_794_132_000_000L;

    /** B3: the store is down for 1,100 finished calls, then recovers; every one of them ends up stored. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void elevenHundredCallsDuringAnOutageAreAllStoredOnceTheStoreRecovers() {
        AtomicBoolean down = new AtomicBoolean(true);
        ConsoleBus bus = new ConsoleBus();
        try (CallRecordStore store = new CallRecordStore("jdbc:sqlite::memory:", 5_000) {
                @Override
                public synchronized void save(CallRecord record) {
                    if (down.get()) throw new IllegalStateException("synthetic outage");
                    super.save(record);
                }
            };
             CallRecordWriter writer = new CallRecordWriter(bus, store, JSON, Clock.systemUTC())) {
            for (int i = 0; i < 1_100; i++) {
                bus.publish(new ConsoleEvent.CallStarted(CallId.of("call-" + i), T0 + i, "hi-IN"));
                bus.publish(new ConsoleEvent.CallEnded(CallId.of("call-" + i), T0 + i + 500, 500, "hangup"));
            }
            assertThat(store.count()).isZero();
            down.set(false);
            bus.publish(new ConsoleEvent.CallStarted(CallId.of("call-after"), T0 + 10_000, "hi-IN"));
            bus.publish(new ConsoleEvent.CallEnded(CallId.of("call-after"), T0 + 10_500, 500, "hangup"));
            assertThat(store.count()).isEqualTo(1_101);
            for (int i = 0; i < 1_100; i++) {
                assertThat(store.get("call-" + i)).as("call-" + i).isPresent();
            }
        }
    }

    /**
     * B4: a malformed old row among good ones. The scrub does not abort: the good rows are scrubbed, the bad one is
     * replaced by a minimal row (times, consent answers, terms only), and the endpoints serve all of them.
     */
    @Test
    void aMalformedOldRowIsMinimisedAndTheOthersAreScrubbed(@TempDir Path dir) throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("g-one.db");
        try (CallRecordStore s = new CallRecordStore(url)) {
            assertThat(s.count()).isZero();
        }
        try (Connection conn = DriverManager.getConnection(url); Statement st = conn.createStatement()) {
            st.execute("""
                    INSERT INTO call_records (call_id, started_ms, ended_ms, duration_ms, end_reason, languages, outcome,
                    consent_farmer, consent_buyer, farmer_key_hash, data, created_at, schema_version, privacy_version)
                    VALUES ('call-good', 1000, 61000, 60000, 'hangup', '{"farmer":"hi-IN"}', 'no_terms', 'NO', 'NONE',
                    NULL, '{"deal":null,"price":{"reference":"ring 9876543210","atMs":2000},
                    "farmer":{"legId":"f","language":"hi-IN","tools":[{"tool":"RameshPrivateAddress","ok":true,
                    "durationMs":3,"atMs":2000,"result":"ring 9876543210"}],"advice":[],"memory":null},"buyer":null}',
                    62000, 3, 0)""");
            st.execute("""
                    INSERT INTO call_records (call_id, started_ms, ended_ms, duration_ms, end_reason, languages, outcome,
                    consent_farmer, consent_buyer, farmer_key_hash, data, created_at, schema_version, privacy_version)
                    VALUES ('call-bad', 2000, 62000, 60000, 'hangup', '{"farmer":"hi-IN"}', 'no_terms', 'YES', 'NO',
                    NULL, '{"deal": "ring 9876543210 then {not json', 63000, 3, 0)""");
        }
        try (CallRecordStore store = new CallRecordStore(url);
             CallRecordWriter writer = new CallRecordWriter(new ConsoleBus(), store, JSON, Clock.systemUTC())) {
            AnalyticsController controller = new AnalyticsController(store,
                    new MemoryProperties("sqlite", "./data", "", "", ""), writer);
            assertThat(controller.calls(10, null).getStatusCode().value()).isEqualTo(200);
            String body = JSON.writeValueAsString(controller.calls(10, null).getBody())
                    + JSON.writeValueAsString(controller.call("call-good", null).getBody())
                    + JSON.writeValueAsString(controller.call("call-bad", null).getBody())
                    + JSON.writeValueAsString(controller.summary(null).getBody());
            assertThat(body).doesNotContain("9876543210").doesNotContain("RameshPrivateAddress")
                    .doesNotContain("not json");
            JsonNode bad = JSON.valueToTree(controller.call("call-bad", null).getBody());
            assertThat(bad.get("consentFarmer").asText()).isEqualTo("YES");
            assertThat(bad.get("startedMs").asLong()).isEqualTo(2000);
            assertThat(bad.get("data").get("minimal").asBoolean()).isTrue();
            assertThat(bad.get("data").get("termsOnly").asBoolean()).isTrue();
            JsonNode good = JSON.valueToTree(controller.call("call-good", null).getBody());
            assertThat(good.get("data").get("farmer").get("tools").get(0).get("tool").asText()).isEqualTo("other");
        }
    }
}
