package com.example.g_one.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.memory.MemoryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * No record lost, forgets durable and fail-closed, reads closed until the privacy cleanup ran (privacy review rounds 1
 * and 2): the writer with a failing store fake, the durable spool on a temporary directory, restarts simulated by
 * opening a new writer on the same files. Synthetic data only.
 */
class CallRecordResilienceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long T0 = 1_794_132_000_000L;
    private static final Vocabulary VOCAB = Vocabulary.bundled(JSON);
    private static final MemoryProperties PROPS = new MemoryProperties("sqlite", "./data", "", "", "");

    /** A store whose saves fail while {@code failures} is above zero (forever when it is negative). */
    private static class FlakyStore extends CallRecordStore {
        final AtomicInteger failures;
        final AtomicInteger attempts = new AtomicInteger();

        FlakyStore(String url, int failures) {
            super(url);
            this.failures = new AtomicInteger(failures);
        }

        FlakyStore(int failures) {
            this("jdbc:sqlite::memory:", failures);
        }

        @Override
        public synchronized void save(CallRecord record) {
            attempts.incrementAndGet();
            if (failures.get() != 0) {
                failures.decrementAndGet();
                throw new IllegalStateException("synthetic save failure");
            }
            super.save(record);
        }
    }

    /** A clock the test moves by hand. */
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

    private static CallRecordWriter writer(ConsoleBus bus, CallRecordStore store, RecordSpool spool, int maxOpen) {
        return new CallRecordWriter(bus, store, JSON, new ManualClock(), VOCAB, spool, maxOpen, false);
    }

    private static void endedCall(ConsoleBus bus, String id, long at) {
        bus.publish(new ConsoleEvent.CallStarted(CallId.of(id), at, "hi-IN"));
        bus.publish(new ConsoleEvent.CallEnded(CallId.of(id), at + 500, 500, "hangup"));
    }

    @Test
    void aSaveThatFailsTwiceWaitsInTheSpoolAndIsThenStored(@TempDir Path dir) {
        ConsoleBus bus = new ConsoleBus();
        FileRecordSpool spool = new FileRecordSpool(dir.resolve("spool"), JSON);
        try (FlakyStore store = new FlakyStore(2);
             CallRecordWriter writer = writer(bus, store, spool, 500)) {
            endedCall(bus, "call-1", T0);
            assertThat(store.count()).isZero();
            assertThat(spool.size()).isEqualTo(1);
            assertThat(writer.pendingRecords()).isEqualTo(1);
            writer.tick(); // second failure
            assertThat(spool.size()).isEqualTo(1);
            writer.tick(); // stored
            assertThat(spool.size()).isZero();
            assertThat(store.get("call-1")).isPresent();
            assertThat(store.attempts.get()).isEqualTo(3);
        }
    }

    @Test
    void aRecordThatCannotBeSavedStaysSpooledIsReportedAndIsStoredWhenTheStoreRecovers() {
        ConsoleBus bus = new ConsoleBus();
        try (FlakyStore store = new FlakyStore(-1);
             CallRecordWriter writer = writer(bus, store, new MemoryRecordSpool(), 500)) {
            endedCall(bus, "call-1", T0);
            for (int i = 0; i < 5; i++) writer.tick();
            assertThat(writer.pendingRecords()).isEqualTo(1);
            assertThat(AnalyticsSummary.of(store.list(10), writer.pendingRecords()).unsavedCalls()).isEqualTo(1);
            store.failures.set(0);
            endedCall(bus, "call-2", T0 + 10_000);
            assertThat(writer.pendingRecords()).isZero();
            assertThat(store.list(10)).extracting(CallRecord::callId).containsExactly("call-2", "call-1");
        }
    }

    /** B3: the process dies with records spooled; the next start replays them once, and replaying again adds none. */
    @Test
    void aRestartReplaysTheSpoolOnceWithoutDuplicates(@TempDir Path dir) {
        String url = "jdbc:sqlite:" + dir.resolve("g-one.db");
        Path spoolDir = dir.resolve("spool");
        ConsoleBus bus = new ConsoleBus();
        // The store is down; three calls end; the process is killed (the writer is never closed).
        FlakyStore down = new FlakyStore(url, -1);
        CallRecordWriter crashed = writer(bus, down, new FileRecordSpool(spoolDir, JSON), 500);
        for (int i = 1; i <= 3; i++) endedCall(bus, "call-" + i, T0 + i * 1000L);
        assertThat(down.count()).isZero();
        // A crash between an insert and its spool removal: the record is in both.
        CallRecord both = new FileRecordSpool(spoolDir, JSON).all().get(0);
        try (CallRecordStore direct = new CallRecordStore(url)) {
            direct.save(both);
        }
        down.close();

        try (CallRecordStore store = new CallRecordStore(url);
             CallRecordWriter restarted = writer(new ConsoleBus(), store, new FileRecordSpool(spoolDir, JSON), 500)) {
            assertThat(store.count()).isEqualTo(3);
            assertThat(restarted.pendingRecords()).isZero();
        }
        try (CallRecordStore store = new CallRecordStore(url);
             CallRecordWriter again = writer(new ConsoleBus(), store, new FileRecordSpool(spoolDir, JSON), 500)) {
            assertThat(store.count()).isEqualTo(3);
        }
        crashed.close();
    }

    /** B3(a): a record that cannot be built is stored as a minimal record, not dropped. */
    @Test
    void aRecordThatCannotBeBuiltIsStoredMinimal() {
        ConsoleBus bus = new ConsoleBus();
        try (CallRecordStore store = new CallRecordStore("jdbc:sqlite::memory:");
             CallRecordWriter writer = writer(bus, store, new MemoryRecordSpool(), 500)) {
            writer.buildWith((call, reason) -> {
                throw new IllegalStateException("synthetic build failure");
            });
            CallId c = CallId.of("call-broken");
            bus.publish(new ConsoleEvent.CallStarted(c, T0, "hi-IN"));
            bus.publish(new ConsoleEvent.ConsentRecorded(c, LegRole.FARMER, "YES", T0 + 1000));
            bus.publish(new ConsoleEvent.CallEnded(c, T0 + 2000, 2000, "hangup"));
            CallRecord r = store.get("call-broken").orElseThrow();
            assertThat(r.consentFarmer()).isEqualTo(Consent.YES);
            assertThat(r.startedMs()).isEqualTo(T0);
            assertThat(r.durationMs()).isEqualTo(2000);
            assertThat(r.endReason()).isEqualTo("other");
            assertThat(r.data().get("minimal").asBoolean()).isTrue();
            assertThat(r.termsOnly()).isTrue();
            assertThat(r.farmerKey()).isNull();
        }
    }

    @Test
    void callsBeyondTheOpenCapAreRecordedEarlyAsIncompleteNotDropped() {
        ConsoleBus bus = new ConsoleBus();
        try (CallRecordStore store = new CallRecordStore("jdbc:sqlite::memory:");
             CallRecordWriter writer = writer(bus, store, new MemoryRecordSpool(), 3)) {
            for (int i = 1; i <= 5; i++) {
                bus.publish(new ConsoleEvent.CallStarted(CallId.of("call-" + i), T0 + i, "hi-IN"));
            }
            assertThat(writer.openCalls()).isEqualTo(3);
            CallRecord first = store.get("call-1").orElseThrow();
            assertThat(first.endReason()).isEqualTo("evicted");
            assertThat(first.data().get("incomplete").asBoolean()).isTrue();
            assertThat(first.termsOnly()).isTrue();
            assertThat(store.get("call-2").orElseThrow().endReason()).isEqualTo("evicted");
            for (int i = 3; i <= 5; i++) {
                bus.publish(new ConsoleEvent.CallEnded(CallId.of("call-" + i), T0 + 1000 + i, 1000, "hangup"));
            }
            assertThat(store.count()).isEqualTo(5);
        }
    }

    @Test
    void callsStillOpenAtShutdownAreRecorded() {
        ConsoleBus bus = new ConsoleBus();
        try (CallRecordStore store = new CallRecordStore("jdbc:sqlite::memory:")) {
            CallRecordWriter writer = writer(bus, store, new MemoryRecordSpool(), 500);
            bus.publish(new ConsoleEvent.CallStarted(CallId.of("call-open"), T0, "hi-IN"));
            writer.close();
            assertThat(store.get("call-open").orElseThrow().endReason()).isEqualTo("shutdown");
        }
    }

    @Test
    void theForgetTombstoneSurvivesARestart(@TempDir Path dir) {
        String url = "jdbc:sqlite:" + dir.resolve("g-one.db");
        String key = FakeLeg.FARMER_KEY.value();
        try (CallRecordStore store = new CallRecordStore(url)) {
            store.save(record("call-before", T0, key));
            assertThat(store.forget(key, T0 + 5_000)).isEqualTo(1);
        }
        try (CallRecordStore store = new CallRecordStore(url)) {
            store.save(record("call-late", T0 + 1_000, key));
            CallRecord late = store.get("call-late").orElseThrow();
            assertThat(late.farmerKey()).isNull();
            assertThat(late.data().get("farmer").get("memory").isNull()).isTrue();
            assertThat(late.data().get("farmer").has("statedName")).isFalse();
            assertThat(late.data().get("farmer").has("timings")).isFalse();
            assertThat(late.data().get("notStored").get("farmer").asText()).isEqualTo("forgotten");
            assertThat(late.consentFarmer()).isEqualTo(Consent.YES);
            store.save(record("call-after", T0 + 9_000, key));
            assertThat(store.get("call-after").orElseThrow().farmerKey()).isEqualTo(key);
        }
    }

    /** B2: a keyed record left behind by a crash (the forget's deletion never ran) is deleted at startup. */
    @Test
    void startupDeletesKeyedRecordsATombstoneCovers(@TempDir Path dir) throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("g-one.db");
        String key = FakeLeg.FARMER_KEY.value();
        try (CallRecordStore store = new CallRecordStore(url)) {
            store.save(record("call-left-behind", T0, key));
        }
        try (var conn = java.sql.DriverManager.getConnection(url); var st = conn.createStatement()) {
            st.execute("INSERT INTO call_record_forgets (caller_key, at_ms) VALUES ('" + key + "', " + (T0 + 5_000) + ")");
        }
        try (CallRecordStore store = new CallRecordStore(url);
             CallRecordWriter writer = writer(new ConsoleBus(), store, new MemoryRecordSpool(), 500)) {
            assertThat(store.get("call-left-behind")).isEmpty();
            assertThat(writer.readiness()).isEqualTo(CallRecordWriter.Readiness.READY);
        }
    }

    /** B2: a forget reaches spooled and open records of the caller, and the store, with no open accumulator needed. */
    @Test
    void aForgetStripsOpenAndSpooledCallsOfThatCaller(@TempDir Path dir) {
        ConsoleBus bus = new ConsoleBus();
        String key = FakeLeg.FARMER_KEY.value();
        FileRecordSpool spool = new FileRecordSpool(dir.resolve("spool"), JSON);
        try (FlakyStore store = new FlakyStore(1);
             CallRecordWriter writer = writer(bus, store, spool, 500)) {
            keyedCall(bus, "call-spooled", T0, true);
            assertThat(spool.all().get(0).farmerKey()).isEqualTo(key);
            keyedCall(bus, "call-open", T0 + 1_000, false);

            writer.forget(key, T0 + 50_000);
            assertThat(spool.all().get(0).farmerKey()).isNull();
            writer.tick();
            bus.publish(new ConsoleEvent.ConsentRecorded(CallId.of("call-open"), LegRole.FARMER, "YES", T0 + 60_000));
            bus.publish(new ConsoleEvent.CallEnded(CallId.of("call-open"), T0 + 61_000, 60_000, "hangup"));

            for (String id : List.of("call-spooled", "call-open")) {
                CallRecord r = store.get(id).orElseThrow();
                assertThat(r.farmerKey()).as(id).isNull();
                assertThat(r.toString()).as(id).doesNotContain("Suresh").doesNotContain("Rameshwar");
                assertThat(r.data().get("notStored").get("farmer").asText()).as(id).isEqualTo("forgotten");
            }
            assertThat(store.forgotten(key, T0)).isTrue();
        }
    }

    /** B2: a forget during a call the writer no longer holds (evicted) still deletes and tombstones. */
    @Test
    void aForgetDuringAnEvictedCallStillTakesEffect() {
        ConsoleBus bus = new ConsoleBus();
        String key = FakeLeg.FARMER_KEY.value();
        try (CallRecordStore store = new CallRecordStore("jdbc:sqlite::memory:");
             CallRecordWriter writer = writer(bus, store, new MemoryRecordSpool(), 1)) {
            store.save(record("call-old", T0 - 100_000, key));
            keyedCall(bus, "call-evicted", T0, false);
            bus.publish(new ConsoleEvent.CallStarted(CallId.of("call-other"), T0 + 1, "hi-IN")); // evicts call-evicted
            assertThat(store.get("call-evicted")).isPresent();
            writer.forget(key, T0 + 5_000);
            assertThat(store.get("call-old")).isEmpty();
            assertThat(store.forgotten(key, T0)).isTrue();
            assertThat(store.list(10)).allSatisfy(r -> assertThat(r.farmerKey()).isNull());
        }
    }

    /** B2: a forget the store cannot take is refused loudly (fail closed), never queued in memory. */
    @Test
    void aForgetTheStoreCannotTakeIsRefused() {
        String key = FakeLeg.FARMER_KEY.value();
        try (CallRecordStore store = new CallRecordStore("jdbc:sqlite::memory:") {
                @Override
                public synchronized int forget(String farmerKey, long atMs) {
                    throw new IllegalStateException("synthetic");
                }
            };
             CallRecordWriter writer = writer(new ConsoleBus(), store, new MemoryRecordSpool(), 500)) {
            store.save(record("call-old", T0, key));
            assertThatThrownBy(() -> writer.forget(key, T0 + 5_000)).isInstanceOf(IllegalStateException.class);
        }
    }

    /** B4: reads stay closed while the privacy cleanup has not succeeded, and open once a tick completes it. */
    @Test
    void readsStayClosedUntilThePrivacyCleanupSucceeds() {
        AtomicInteger scrubFailures = new AtomicInteger(1);
        try (CallRecordStore store = new CallRecordStore("jdbc:sqlite::memory:") {
                @Override
                public synchronized ScrubReport scrubLegacy(RecordSchema schema) {
                    if (scrubFailures.getAndDecrement() > 0) throw new IllegalStateException("synthetic");
                    return super.scrubLegacy(schema);
                }
            };
             CallRecordWriter writer = writer(new ConsoleBus(), store, new MemoryRecordSpool(), 500)) {
            AnalyticsController controller = new AnalyticsController(store, PROPS, writer);
            assertThat(writer.readiness()).isEqualTo(CallRecordWriter.Readiness.FAILED);
            for (var res : List.of(controller.calls(10, null), controller.call("x", null), controller.summary(null))) {
                assertThat(res.getStatusCode().value()).isEqualTo(503);
                assertThat(res.getBody().toString()).contains("analytics_privacy_cleanup_failed");
            }
            writer.tick();
            assertThat(writer.readiness()).isEqualTo(CallRecordWriter.Readiness.READY);
            assertThat(controller.calls(10, null).getStatusCode().value()).isEqualTo(200);
        }
    }

    @Test
    void theFileSpoolKeepsNothingButTheProjectedRecordAndCleansTemporaryFiles(@TempDir Path dir) throws Exception {
        Path spoolDir = dir.resolve("spool");
        Files.createDirectories(spoolDir);
        Files.writeString(spoolDir.resolve("half-written.tmp"), "{");
        FileRecordSpool spool = new FileRecordSpool(spoolDir, JSON);
        assertThat(Files.exists(spoolDir.resolve("half-written.tmp"))).isFalse();
        spool.put(record("call-1", T0, null));
        try (var files = Files.list(spoolDir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .singleElement().satisfies(n -> assertThat(n).doesNotContain("call-1").endsWith(".json"));
        }
        spool.put(record("call-1", T0, null));
        assertThat(spool.size()).isEqualTo(1);
        spool.remove("call-1");
        assertThat(spool.size()).isZero();
    }

    private static void keyedCall(ConsoleBus bus, String id, long at, boolean end) {
        CallId c = CallId.of(id);
        LegId f = LegId.of(id + "-f");
        bus.publish(new ConsoleEvent.CallStarted(c, at, "hi-IN"));
        bus.publish(new ConsoleEvent.LegJoined(c, at, f, LegRole.FARMER, LegSource.TWILIO, "hi-IN"));
        bus.publish(new ConsoleEvent.MemoryLoaded(c, at + 10, FakeLeg.FARMER_KEY, true,
                List.of(new ProfileFact("name", "Suresh", Provenance.said(CallId.of("old"), at - 5000))), List.of()));
        bus.publish(new ConsoleEvent.ToolCalled(c, at + 100, "save_profile_fact",
                JSON.createObjectNode().put("field", "name").put("value", "Rameshwar"), true, "ok", 2));
        bus.publish(new ConsoleEvent.TurnHeard(c, at + 2000, f, LegRole.FARMER, "hi-IN", "w", at + 1000, at + 1500));
        if (end) {
            bus.publish(new ConsoleEvent.ConsentRecorded(c, LegRole.FARMER, "YES", at + 3000));
            bus.publish(new ConsoleEvent.CallEnded(c, at + 3000, 3000, "hangup"));
        }
    }

    /** A keyed record with everything a farmer's yes keeps. */
    private static CallRecord record(String id, long startedMs, String key) {
        var data = JSON.createObjectNode();
        data.put("keyed", key != null);
        data.putObject("notStored");
        var farmer = data.putObject("farmer");
        farmer.put("statedName", "Rameshwar");
        farmer.putArray("timings").addObject().put("heardMs", startedMs + 10);
        farmer.putObject("memory").putArray("facts").addObject().put("field", "name").put("value", "Suresh");
        return new CallRecord(id, startedMs, startedMs + 1000, 1000, "hangup", Map.of("farmer", "hi-IN"), "no_terms",
                Consent.YES, Consent.NONE, key, data, startedMs + 2000);
    }
}
