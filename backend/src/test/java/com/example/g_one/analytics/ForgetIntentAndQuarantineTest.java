package com.example.g_one.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.memory.MemoryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Privacy review round 3. Item 3: a forget's memory step is durable: the call records commit a forget intent in the
 * same transaction as their own deletion, and a startup step or tick completes it, so a crash between the two never
 * leaves memory behind. Item 4: the one-time scrub never deletes a call: a row it can neither rebuild nor minimise is
 * kept, quarantined (never served) and retried. Synthetic data only.
 */
class ForgetIntentAndQuarantineTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long T0 = 1_794_132_000_000L;
    private static final CallerKey KEY = FakeLeg.FARMER_KEY;
    private static final Vocabulary VOCAB = Vocabulary.bundled(JSON);
    private static final MemoryProperties PROPS = new MemoryProperties("sqlite", "./data", "", "", "");

    private static final class ManualClock extends Clock {
        long now = T0 + 100_000;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }
    }

    /** The memory store's forget, failing while {@code down}, counting calls. */
    private static final class Memory implements MemoryForget {
        final InMemoryMemoryStore store;
        final AtomicBoolean down = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();

        Memory(InMemoryMemoryStore store) {
            this.store = store;
        }

        @Override
        public void forget(String callerKey, String callId, long atMs) {
            calls.incrementAndGet();
            if (down.get()) throw new IllegalStateException("synthetic memory failure");
            store.forget(CallerKey.of(callerKey), CallId.of(callId), atMs);
        }
    }

    private static InMemoryMemoryStore rememberedFarmer() {
        InMemoryMemoryStore m = new InMemoryMemoryStore();
        m.saveGist(KEY, new CallGist(CallId.of("old"), T0 - 10_000, T0 - 9_000, "Asked about onion.", List.of()));
        return m;
    }

    private static CallRecordWriter writer(CallRecordStore store, Memory memory, Clock clock) {
        return new CallRecordWriter(new ConsoleBus(), store, JSON, clock, VOCAB, new MemoryRecordSpool(), memory, 500,
                false);
    }

    private static CallRecord keyed(String id) {
        return new CallRecord(id, T0, T0 + 1000, 1000, "hangup", Map.of("farmer", "hi-IN"), "no_terms", Consent.YES,
                Consent.NONE, KEY.value(), JSON.createObjectNode().putNull("deal"), T0 + 2000);
    }

    @Test
    void aCrashBetweenTheRecordsCommitAndTheMemoryForgetIsCompletedAtTheNextStartup(@TempDir Path dir) {
        String url = "jdbc:sqlite:" + dir.resolve("g-one.db");
        InMemoryMemoryStore durableMemory = rememberedFarmer();
        Memory failing = new Memory(durableMemory);
        failing.down.set(true);
        CallRecordStore first = new CallRecordStore(url);
        first.save(keyed("call-keyed"));
        CallRecordWriter crashed = writer(first, failing, new ManualClock());
        crashed.forgetNow(KEY.value()); // the records commit; memory fails; then the process "dies"
        assertThat(first.get("call-keyed")).isEmpty();
        assertThat(durableMemory.load(KEY)).isPresent();
        assertThat(first.pendingIntents()).hasSize(1);
        first.close();

        // Restart: no request is repeated. The startup step completes the intent before anything is served.
        Memory working = new Memory(durableMemory);
        try (CallRecordStore store = new CallRecordStore(url);
             CallRecordWriter restarted = writer(store, working, new ManualClock())) {
            assertThat(durableMemory.load(KEY)).isEmpty();
            assertThat(store.pendingIntents()).isEmpty();
            assertThat(working.calls.get()).isEqualTo(1);
            // Completing again is idempotent: nothing pending, nothing called.
            restarted.completeIntents();
            restarted.tick();
            assertThat(working.calls.get()).isEqualTo(1);
        }
        // And a further restart does nothing more.
        Memory again = new Memory(durableMemory);
        try (CallRecordStore store = new CallRecordStore(url);
             CallRecordWriter third = writer(store, again, new ManualClock())) {
            assertThat(again.calls.get()).isZero();
        }
        crashed.close();
    }

    @Test
    void aFailedMemoryForgetIsCompletedByALaterTickWithoutARepeatedRequest() {
        InMemoryMemoryStore memoryStore = rememberedFarmer();
        Memory memory = new Memory(memoryStore);
        memory.down.set(true);
        try (CallRecordStore store = new CallRecordStore("jdbc:sqlite::memory:");
             CallRecordWriter w = writer(store, memory, new ManualClock())) {
            w.forgetNow(KEY.value());
            w.tick();
            assertThat(memoryStore.load(KEY)).isPresent();
            memory.down.set(false);
            w.tick();
            assertThat(memoryStore.load(KEY)).isEmpty();
            assertThat(store.pendingIntents()).isEmpty();
        }
    }

    @Test
    void anIntentForAnUnknownKeyCompletesHarmlessly() {
        Memory memory = new Memory(new InMemoryMemoryStore());
        String unknown = "d".repeat(64);
        try (CallRecordStore store = new CallRecordStore("jdbc:sqlite::memory:")) {
            store.forgetWithIntent(unknown, CallRecordWriter.FORGET_INTENT_CALL, T0);
            try (CallRecordWriter w = writer(store, memory, new ManualClock())) {
                assertThat(store.pendingIntents()).isEmpty();
                assertThat(memory.calls.get()).isEqualTo(1);
            }
        }
    }

    @Test
    void aForgetAskedAgainWhileOneIsPendingIsCompletedOnceForTheLatestTime() {
        InMemoryMemoryStore memoryStore = rememberedFarmer();
        Memory memory = new Memory(memoryStore);
        memory.down.set(true);
        ManualClock clock = new ManualClock();
        try (CallRecordStore store = new CallRecordStore("jdbc:sqlite::memory:");
             CallRecordWriter w = writer(store, memory, clock)) {
            w.forgetNow(KEY.value());
            CallRecordStore.ForgetIntent earlier = store.pendingIntents().get(0);
            clock.now += 5_000;
            w.forgetNow(KEY.value()); // asked again while the first is pending
            assertThat(store.pendingIntents()).singleElement()
                    .satisfies(i -> assertThat(i.atMs()).isEqualTo(clock.now));
            // Completing the older request does not close the newer one.
            store.intentDone(earlier);
            assertThat(store.pendingIntents()).hasSize(1);
            memory.down.set(false);
            w.tick();
            assertThat(store.pendingIntents()).isEmpty();
            assertThat(memoryStore.load(KEY)).isEmpty();
        }
    }

    // ---- item 4: the scrub never deletes ----------------------------------------------------------------------

    /** A store whose scrub writes fail for one row: neither the rebuilt nor the minimal row can be written. */
    private static final class StubbornRowStore extends CallRecordStore {
        final AtomicBoolean broken = new AtomicBoolean(true);

        StubbornRowStore(String url) {
            super(url);
        }

        @Override
        void writeScrubbed(String oldId, CallRecord r) {
            if (broken.get() && oldId.equals("call-bad")) throw new IllegalStateException("synthetic write failure");
            super.writeScrubbed(oldId, r);
        }
    }

    @Test
    void anUnfixableRowIsKeptQuarantinedNeverServedAndRetried(@TempDir Path dir) throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("g-one.db");
        try (CallRecordStore s = new CallRecordStore(url)) {
            assertThat(s.count()).isZero();
        }
        try (Connection conn = DriverManager.getConnection(url); Statement st = conn.createStatement()) {
            for (String id : List.of("call-good", "call-bad")) {
                st.execute("""
                        INSERT INTO call_records (call_id, started_ms, ended_ms, duration_ms, end_reason, languages,
                        outcome, consent_farmer, consent_buyer, farmer_key_hash, data, created_at, schema_version,
                        privacy_version) VALUES ('%s', 1000, 61000, 60000, 'hangup', '{}', 'no_terms', 'NO', 'NONE',
                        NULL, '{"deal":null,"price":{"reference":"ring 9876543210","atMs":2000},"farmer":null,
                        "buyer":null}', 62000, 3, 0)""".formatted(id));
            }
        }
        ManualClock clock = new ManualClock();
        try (StubbornRowStore store = new StubbornRowStore(url);
             CallRecordWriter w = writer(store, null, clock)) {
            AnalyticsController controller = new AnalyticsController(store, PROPS, w);
            // The rest of analytics serves; only the bad row is hidden.
            assertThat(w.readiness()).isEqualTo(CallRecordWriter.Readiness.READY);
            assertThat(store.quarantined()).isEqualTo(1);
            String list = JSON.writeValueAsString(controller.calls(10, null).getBody());
            assertThat(list).contains("call-good").doesNotContain("call-bad").doesNotContain("9876543210");
            assertThat(controller.call("call-bad", null).getStatusCode().value()).isEqualTo(404);
            assertThat(JSON.writeValueAsString(controller.summary(null).getBody())).contains("\"storedCalls\":1");
            // Kept, not deleted.
            assertThat(raw(url, "SELECT quarantined FROM call_records WHERE call_id = 'call-bad'")).isEqualTo(1);
            assertThat(raw(url, "SELECT privacy_version FROM call_records WHERE call_id = 'call-good'")).isEqualTo(1);

            // Retried later: once the write works again, the row is scrubbed and served.
            store.broken.set(false);
            w.tick(); // too soon: retried at most once a minute
            assertThat(store.quarantined()).isEqualTo(1);
            clock.now += CallRecordWriter.QUARANTINE_RETRY_MS;
            w.tick();
            assertThat(store.quarantined()).isZero();
            assertThat(controller.call("call-bad", null).getStatusCode().value()).isEqualTo(200);
            assertThat(JSON.writeValueAsString(controller.call("call-bad", null).getBody()))
                    .doesNotContain("9876543210");
        }
    }

    private static int raw(String url, String sql) throws Exception {
        try (Connection conn = DriverManager.getConnection(url); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
