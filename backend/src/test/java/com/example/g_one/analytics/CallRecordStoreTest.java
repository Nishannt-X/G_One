package com.example.g_one.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The call records table on a temporary SQLite file. Synthetic data only. */
class CallRecordStoreTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static CallRecord record(String id, long startedMs, String key) {
        return new CallRecord(id, startedMs, startedMs + 1000, 1000, "hangup", Map.of("farmer", "hi-IN"), "no_terms",
                Consent.YES, Consent.NONE, key, JSON.createObjectNode().putNull("deal"), startedMs + 2000);
    }

    @Test
    void savesListsNewestFirstAndSurvivesReopening(@TempDir Path dir) {
        String url = "jdbc:sqlite:" + dir.resolve("g-one.db");
        try (CallRecordStore store = new CallRecordStore(url)) {
            store.save(record("a", 1, "k1"));
            store.save(record("b", 3, null));
            store.save(record("c", 2, "k1"));
        }
        try (CallRecordStore store = new CallRecordStore(url)) {
            assertThat(store.list(10)).extracting(CallRecord::callId).containsExactly("b", "c", "a");
            assertThat(store.list(1)).extracting(CallRecord::callId).containsExactly("b");
            assertThat(store.get("a").orElseThrow().farmerKey()).isEqualTo("k1");
            assertThat(store.get("a").orElseThrow().consentFarmer()).isEqualTo(Consent.YES);
            assertThat(store.delete("b")).isTrue();
            assertThat(store.delete("b")).isFalse();
            assertThat(store.deleteByFarmerKey("k1")).isEqualTo(2);
            assertThat(store.count()).isZero();
        }
    }

    @Test
    void keepsOnlyTheNewestRecordsUpToTheCap() {
        try (CallRecordStore store = new CallRecordStore("jdbc:sqlite::memory:", 3)) {
            for (int i = 1; i <= 5; i++) {
                store.save(record("call-" + i, i, null));
            }
            assertThat(store.count()).isEqualTo(3);
            assertThat(store.list(10)).extracting(CallRecord::callId).containsExactly("call-5", "call-4", "call-3");
        }
        assertThat(CallRecordStore.MAX_RECORDS).isEqualTo(500);
    }
}
