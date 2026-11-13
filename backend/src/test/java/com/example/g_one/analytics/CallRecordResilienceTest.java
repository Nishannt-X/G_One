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
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Privacy review B4 and B5 at the writer and store: failed saves are retried and never silently lost, calls beyond the
 * open-call cap are recorded early instead of dropped, and a forget tombstone is durable and is applied to open and
 * queued calls. Synthetic data only; the store fakes fail on purpose and never touch anything outside memory or a
 * temporary directory.
 */
class CallRecordResilienceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long T0 = 1_794_132_000_000L;
    private static final DealVocabulary VOCAB = DealVocabulary.bundled(JSON);

    /** A store whose saves fail while {@code failures} is above zero (forever when it is negative). */
    private static final class FlakyStore extends CallRecordStore {
        final AtomicInteger failures;
        final AtomicInteger attempts = new AtomicInteger();

        FlakyStore(int failures) {
            super("jdbc:sqlite::memory:");
            this.failures = new AtomicInteger(failures);
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

    private static void endedCall(ConsoleBus bus, String id, long at) {
        bus.publish(new ConsoleEvent.CallStarted(CallId.of(id), at, "hi-IN"));
        bus.publish(new ConsoleEvent.CallEnded(CallId.of(id), at + 500, 500, "hangup"));
    }

    @Test
    void aSaveThatFailsTwiceIsRetriedWithBackoffAndThenStored() {
        ConsoleBus bus = new ConsoleBus();
        ManualClock clock = new ManualClock();
        try (FlakyStore store = new FlakyStore(2);
             CallRecordWriter writer = new CallRecordWriter(bus, store, JSON, clock, VOCAB, 500, false)) {
            endedCall(bus, "call-1", T0);
            assertThat(store.count()).isZero();
            assertThat(writer.pendingRecords()).isEqualTo(1);

            writer.retryDue(); // not yet due: the first wait is one second
            assertThat(store.attempts.get()).isEqualTo(1);
            clock.now += CallRecordWriter.BACKOFF_MS[0];
            writer.retryDue(); // second failure
            assertThat(writer.pendingRecords()).isEqualTo(1);
            clock.now += CallRecordWriter.BACKOFF_MS[1];
            writer.retryDue(); // stored
            assertThat(writer.pendingRecords()).isZero();
            assertThat(store.get("call-1")).isPresent();
            assertThat(store.attempts.get()).isEqualTo(3);
        }
    }

    @Test
    void aRecordThatCannotBeSavedStaysQueuedRetriesOnTheNextCallEndAndIsReported() {
        ConsoleBus bus = new ConsoleBus();
        ManualClock clock = new ManualClock();
        try (FlakyStore store = new FlakyStore(-1);
             CallRecordWriter writer = new CallRecordWriter(bus, store, JSON, clock, VOCAB, 500, false)) {
            endedCall(bus, "call-1", T0);
            for (int i = 0; i < 5; i++) {
                clock.now += CallRecordWriter.SLOW_RETRY_MS;
                writer.retryDue();
            }
            assertThat(writer.pendingRecords()).isEqualTo(1);
            assertThat(AnalyticsSummary.of(store.list(10), writer.pendingRecords()).unsavedCalls()).isEqualTo(1);

            // The store recovers: the next call end saves both, without waiting for the tick.
            store.failures.set(0);
            endedCall(bus, "call-2", T0 + 10_000);
            assertThat(writer.pendingRecords()).isZero();
            assertThat(store.list(10)).extracting(CallRecord::callId).containsExactly("call-2", "call-1");
        }
    }

    @Test
    void callsBeyondTheOpenCapAreRecordedEarlyAsIncompleteNotDropped() {
        ConsoleBus bus = new ConsoleBus();
        try (CallRecordStore store = new CallRecordStore("jdbc:sqlite::memory:");
             CallRecordWriter writer = new CallRecordWriter(bus, store, JSON, new ManualClock(), VOCAB, 3, false)) {
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
            CallRecordWriter writer = new CallRecordWriter(bus, store, JSON, new ManualClock(), VOCAB, 500, false);
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
            // A late record of a call that began before the forget is saved without the caller.
            store.save(record("call-late", T0 + 1_000, key));
            CallRecord late = store.get("call-late").orElseThrow();
            assertThat(late.farmerKey()).isNull();
            assertThat(late.data().get("farmer").get("memory").isNull()).isTrue();
            assertThat(late.data().get("farmer").has("statedName")).isFalse();
            assertThat(late.data().get("farmer").has("timings")).isFalse();
            assertThat(late.data().get("notStored").get("farmer").asText()).isEqualTo("forgotten");
            assertThat(late.consentFarmer()).isEqualTo(Consent.YES);
            // A call that began after the forget may link again.
            store.save(record("call-after", T0 + 9_000, key));
            assertThat(store.get("call-after").orElseThrow().farmerKey()).isEqualTo(key);
        }
    }

    @Test
    void theConsoleForgetStripsOpenAndQueuedCallsOfThatCaller() {
        ConsoleBus bus = new ConsoleBus();
        ManualClock clock = new ManualClock();
        String key = FakeLeg.FARMER_KEY.value();
        try (FlakyStore store = new FlakyStore(1);
             CallRecordWriter writer = new CallRecordWriter(bus, store, JSON, clock, VOCAB, 500, false)) {
            // A finished call whose save failed, queued with the caller key.
            keyedCall(bus, "call-queued", T0, true);
            assertThat(writer.pendingRecords()).isEqualTo(1);
            // An open call of the same caller.
            keyedCall(bus, "call-open", T0 + 1_000, false);

            writer.forget(key, T0 + 50_000);
            writer.retryAll();
            bus.publish(new ConsoleEvent.ConsentRecorded(CallId.of("call-open"), LegRole.FARMER, "YES", T0 + 60_000));
            bus.publish(new ConsoleEvent.CallEnded(CallId.of("call-open"), T0 + 61_000, 60_000, "hangup"));

            for (String id : List.of("call-queued", "call-open")) {
                CallRecord r = store.get(id).orElseThrow();
                assertThat(r.farmerKey()).as(id).isNull();
                assertThat(r.toString()).as(id).doesNotContain("Suresh").doesNotContain("Rameshwar");
                assertThat(r.data().get("notStored").get("farmer").asText()).as(id).isEqualTo("forgotten");
            }
            assertThat(store.forgotten(key, T0)).isTrue();
        }
    }

    @Test
    void aForgetTheStoreCannotTakeIsQueuedAndRetried() {
        ConsoleBus bus = new ConsoleBus();
        AtomicInteger forgetFailures = new AtomicInteger(1);
        String key = FakeLeg.FARMER_KEY.value();
        try (CallRecordStore store = new CallRecordStore("jdbc:sqlite::memory:") {
                @Override
                public synchronized int forget(String farmerKey, long atMs) {
                    if (forgetFailures.getAndDecrement() > 0) throw new IllegalStateException("synthetic");
                    return super.forget(farmerKey, atMs);
                }
            };
             CallRecordWriter writer = new CallRecordWriter(bus, store, JSON, new ManualClock(), VOCAB, 500, false)) {
            store.save(record("call-old", T0, key));
            assertThatThrownBy(() -> writer.forget(key, T0 + 5_000)).isInstanceOf(IllegalStateException.class);
            assertThat(store.get("call-old")).isPresent();
            writer.retryDue();
            assertThat(store.get("call-old")).isEmpty();
            assertThat(store.forgotten(key, T0)).isTrue();
        }
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
        data.put("keyed", true);
        data.putObject("notStored");
        var farmer = data.putObject("farmer");
        farmer.put("statedName", "Rameshwar");
        farmer.putArray("timings").addObject().put("heardMs", startedMs + 10);
        farmer.putObject("memory").putArray("facts").addObject().put("field", "name").put("value", "Suresh");
        return new CallRecord(id, startedMs, startedMs + 1000, 1000, "hangup", Map.of("farmer", "hi-IN"), "no_terms",
                Consent.YES, Consent.NONE, key, data, startedMs + 2000);
    }
}
