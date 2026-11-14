package com.example.g_one.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.analytics.CallRecord;
import com.example.g_one.analytics.CallRecordStore;
import com.example.g_one.analytics.CallRecordWriter;
import com.example.g_one.analytics.Consent;
import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.fakes.FakeLeg;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

/**
 * Privacy review round 2, B2: the console's forget reaches analytics first and durably, and fails closed. A crash in
 * the memory step after analytics committed leaves the analytics tombstone and deletion in place across a restart; an
 * analytics failure stops the forget before memory is touched and reports it. Synthetic data only.
 */
class ForgetAnalyticsFirstTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long T0 = 1_794_132_000_000L;
    private static final CallerKey KEY = FakeLeg.FARMER_KEY;
    private static final MemoryProperties PROPS = new MemoryProperties("sqlite", "./data", "", "", "");

    /** A memory store whose forget fails (the process "crashes" in the memory step). */
    private static final class CrashingMemory extends InMemoryMemoryStore {
        @Override
        public boolean forget(CallerKey key, CallId duringCall, long atMs) {
            throw new IllegalStateException("synthetic crash in the memory forget");
        }
    }

    private static CallRecord keyed(String id) {
        return new CallRecord(id, T0, T0 + 1000, 1000, "hangup", Map.of("farmer", "hi-IN"), "no_terms", Consent.YES,
                Consent.NONE, KEY.value(), JSON.createObjectNode().putNull("deal"), T0 + 2000);
    }

    @Test
    void aCrashInTheMemoryStepLeavesTheAnalyticsForgetDurable(@TempDir Path dir) {
        String url = "jdbc:sqlite:" + dir.resolve("g-one.db");
        try (CallRecordStore store = new CallRecordStore(url);
             CallRecordWriter writer = new CallRecordWriter(new ConsoleBus(), store, JSON, Clock.systemUTC())) {
            store.save(keyed("call-keyed"));
            ForgetListener analytics = k -> writer.forgetNow(k.value());
            MemoryController controller = new MemoryController(new CrashingMemory(), PROPS, List.of(analytics));
            try {
                controller.forget(KEY.value(), null);
            } catch (RuntimeException crash) {
                // The memory step failed; what matters is what analytics kept.
            }
        }
        // Restart: the keyed record is gone and the tombstone is still there.
        try (CallRecordStore store = new CallRecordStore(url)) {
            assertThat(store.get("call-keyed")).isEmpty();
            assertThat(store.forgotten(KEY.value(), T0)).isTrue();
        }
    }

    @Test
    void anAnalyticsFailureStopsTheForgetBeforeMemoryIsTouched() {
        InMemoryMemoryStore memory = new InMemoryMemoryStore();
        memory.saveGist(KEY, new CallGist(CallId.of("old"), T0, T0 + 1000, "Asked about onion.", List.of()));
        ForgetListener failing = k -> {
            throw new IllegalStateException("synthetic analytics failure");
        };
        MemoryController controller = new MemoryController(memory, PROPS, List.of(failing));
        ResponseEntity<?> res;
        try {
            res = controller.forget(KEY.value(), null);
        } catch (RuntimeException e) {
            res = null;
        }
        assertThat(res).as("a failed forget is reported, not thrown").isNotNull();
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        // Fail closed: nothing claims success, and memory is still there to forget on a retry.
        assertThat(memory.load(KEY)).isPresent();
    }

    @Test
    void aSuccessfulConsoleForgetClearsBothAndLeavesTheTombstone(@TempDir Path dir) {
        String url = "jdbc:sqlite:" + dir.resolve("g-one.db");
        InMemoryMemoryStore memory = new InMemoryMemoryStore();
        memory.saveGist(KEY, new CallGist(CallId.of("old"), T0, T0 + 1000, "Asked about onion.", List.of()));
        try (CallRecordStore store = new CallRecordStore(url);
             CallRecordWriter writer = new CallRecordWriter(new ConsoleBus(), store, JSON, Clock.systemUTC())) {
            store.save(keyed("call-keyed"));
            MemoryController controller = new MemoryController(memory, PROPS,
                    List.of(k -> writer.forgetNow(k.value())));
            assertThat(controller.forget(KEY.value(), null).getStatusCode().value()).isEqualTo(204);
            assertThat(memory.load(KEY)).isEmpty();
            assertThat(store.get("call-keyed")).isEmpty();
            assertThat(store.forgotten(KEY.value(), T0)).isTrue();
        }
    }
}
