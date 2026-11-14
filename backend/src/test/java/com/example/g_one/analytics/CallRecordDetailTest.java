package com.example.g_one.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import com.example.g_one.fakes.FakeLeg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a stored record keeps for the stored-call view (schema version 3), which party's yes unlocks each part, and that
 * version 1 rows still open. Synthetic data only; "CANARY" strings must never reach a record.
 */
class CallRecordDetailTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long T0 = 1_794_132_000_000L;
    private static final String PRICE = "Agmarknet listed 2,150 rupees per quintal for onion at Lasalgaon on 2026-11-01.";

    private static final class Harness implements AutoCloseable {
        final ConsoleBus bus = new ConsoleBus();
        final CallRecordStore store;
        final CallRecordWriter writer;

        Harness(String url) {
            store = new CallRecordStore(url);
            writer = new CallRecordWriter(bus, store, JSON, Clock.fixed(Instant.ofEpochMilli(T0 + 999_000), ZoneOffset.UTC));
        }

        @Override
        public void close() {
            writer.close();
            store.close();
        }
    }

    private static List<CallGist> gists(int n) {
        List<CallGist> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new CallGist(CallId.of("old-" + i), T0 - 100_000 + i * 1000, T0 - 99_000 + i * 1000,
                    "Old call " + i + ".", List.of("Follow-up " + i)));
        }
        return out;
    }

    /** A full synthetic call with a name check (confirmed or denied), a buyer match and a deal read back and agreed. */
    private static void call(ConsoleBus bus, String id, String farmer, String buyer, boolean nameConfirmed) {
        CallId c = CallId.of(id);
        LegId f = LegId.of(id + "-f");
        LegId b = LegId.of(id + "-b");
        List<ConsoleEvent> e = new ArrayList<>();
        e.add(new ConsoleEvent.CallStarted(c, T0, "auto"));
        e.add(new ConsoleEvent.LegJoined(c, T0, f, LegRole.FARMER, LegSource.SIMULATOR, "auto"));
        e.add(new ConsoleEvent.MemoryLoaded(c, T0 + 10, FakeLeg.FARMER_KEY, true,
                List.of(new ProfileFact("name", "Suresh", Provenance.said(CallId.of("old-0"), T0 - 5000))), gists(5)));
        e.add(new ConsoleEvent.LanguageChanged(c, T0 + 900, f, LegRole.FARMER, "auto", "hi-IN", "detected"));
        e.add(new ConsoleEvent.TurnHeard(c, T0 + 2000, f, LegRole.FARMER, "hi-IN", "CANARY-words", T0 + 1000, T0 + 1500));
        e.add(new ConsoleEvent.AssistantSaid(c, T0 + 3000, f, LegRole.FARMER, "hi-IN", "CANARY-reply"));
        e.add(new ConsoleEvent.ToolCalled(c, T0 + 4000, "confirm_name",
                JSON.createObjectNode().put("confirmed", nameConfirmed), true, "CANARY-name-result", 3));
        e.add(new ConsoleEvent.ToolCalled(c, T0 + 5000, "get_mandi_price",
                JSON.createObjectNode().put("commodity", "CANARY-arg"), true, PRICE, 6));
        e.add(new ConsoleEvent.WhisperSent(c, T0 + 6000, f, ConsoleEvent.WhisperKind.PRICE_REFERENCE, "hi-IN", "CANARY-whisper"));
        e.add(new ConsoleEvent.ToolCalled(c, T0 + 7000, "connect_buyer",
                JSON.createObjectNode().put("buyerId", "mehta-agro"), true,
                "Calling Mehta Agro Traders now. Tell the farmer you are calling them.", 4));
        e.add(new ConsoleEvent.LegJoined(c, T0 + 8000, b, LegRole.BUYER, LegSource.SIMULATOR, "en-IN"));
        e.add(new ConsoleEvent.LanguageChanged(c, T0 + 9000, b, LegRole.BUYER, "en-IN", "hi-IN", "named"));
        e.add(new ConsoleEvent.TurnHeard(c, T0 + 10_000, b, LegRole.BUYER, "hi-IN", "CANARY-buyer", T0 + 9200, T0 + 9600));
        e.add(new ConsoleEvent.TurnTranslated(c, T0 + 10_500, b, LegRole.BUYER, LegRole.FARMER, "hi-IN", "hi-IN",
                "CANARY-translation", T0 + 9200));
        e.add(new ConsoleEvent.WhisperSent(c, T0 + 11_000, f, ConsoleEvent.WhisperKind.CAUTION, "hi-IN", "CANARY-caution"));
        e.add(deal(c, T0 + 12_000, ConsoleEvent.DealState.DRAFT, false, false));
        e.add(deal(c, T0 + 13_000, ConsoleEvent.DealState.READBACK, false, false));
        e.add(deal(c, T0 + 14_000, ConsoleEvent.DealState.CONFIRMING, true, false));
        e.add(deal(c, T0 + 15_000, ConsoleEvent.DealState.AGREED, true, true));
        e.add(new ConsoleEvent.LegEnded(c, T0 + 16_000, b, LegRole.BUYER, "hangup"));
        if (farmer != null) e.add(new ConsoleEvent.ConsentRecorded(c, LegRole.FARMER, farmer, T0 + 17_000));
        if (buyer != null) e.add(new ConsoleEvent.ConsentRecorded(c, LegRole.BUYER, buyer, T0 + 17_000));
        e.add(new ConsoleEvent.LegEnded(c, T0 + 18_000, f, LegRole.FARMER, "assistant_hangup"));
        e.add(new ConsoleEvent.CallEnded(c, T0 + 18_000, 18_000, "deal_agreed"));
        e.forEach(bus::publish);
    }

    private static ConsoleEvent.DealUpdated deal(CallId c, long at, ConsoleEvent.DealState s, boolean fy, boolean by) {
        return new ConsoleEvent.DealUpdated(c, at, s, "onion", "Red", "Lasalgaon", "10 quintal", new BigDecimal("2100"),
                "quintal", "Friday at the farm; payment cash on pickup", fy, by);
    }

    @Test
    void keepsEveryNonTranscriptDetailWhenBothSayYesAndReadsItBack(@TempDir Path dir) throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("g-one.db");
        try (Harness h = new Harness(url)) {
            call(h.bus, "call-full", "YES", "YES", true);
        }
        try (Harness h = new Harness(url)) {
            CallRecord r = h.store.get("call-full").orElseThrow();
            assertThat(r.schemaVersion()).isEqualTo(CallRecord.SCHEMA_VERSION);
            assertThat(r.outcome()).isEqualTo("agreed");
            assertThat(r.languages()).containsEntry("farmer", "hi-IN").containsEntry("buyer", "hi-IN");
            JsonNode d = r.data();

            JsonNode deal = d.get("deal");
            // Privacy review B1: a fixed label from the pickup and payment kinds, never the words.
            assertThat(deal.get("pickup").asText()).isEqualTo("At the farm; payment on delivery");
            assertThat(deal.get("readback").asBoolean()).isTrue();
            assertThat(deal.get("stateTimes").get("readback").asLong()).isEqualTo(T0 + 13_000);
            assertThat(deal.get("stateTimes").get("agreed").asLong()).isEqualTo(T0 + 15_000);
            assertThat(deal.get("farmerYesMs").asLong()).isEqualTo(T0 + 14_000);
            assertThat(deal.get("buyerYesMs").asLong()).isEqualTo(T0 + 15_000);
            // Privacy review B2: that the lookup ran, and when; never its result.
            assertThat(d.get("price").get("atMs").asLong()).isEqualTo(T0 + 5000);
            assertThat(JSON.writeValueAsString(r)).doesNotContain(PRICE);

            JsonNode f = d.get("farmer");
            assertThat(f.get("firstLanguage").asText()).isEqualTo("auto");
            assertThat(f.get("languageChanges").get(0).get("to").asText()).isEqualTo("hi-IN");
            assertThat(f.get("endedMs").asLong()).isEqualTo(T0 + 18_000);
            assertThat(f.get("endReason").asText()).isEqualTo("assistant_hangup");
            assertThat(f.get("identity").asText()).isEqualTo("confirmed");
            assertThat(f.get("latency").get("turns").asInt()).isEqualTo(1);
            assertThat(f.get("latency").get("transcript").asLong()).isEqualTo(500);
            assertThat(f.get("latency").get("reply").asLong()).isEqualTo(1000);
            assertThat(f.get("tools")).extracting(t -> t.get("tool").asText())
                    .containsExactly("confirm_name", "get_mandi_price", "connect_buyer");
            assertThat(f.get("advice")).extracting(a -> a.get("kind").asText()).containsExactly("price_reference", "caution");
            JsonNode memory = f.get("memory");
            assertThat(memory.get("facts").get(0).get("value").asText()).isEqualTo("Suresh");
            assertThat(memory.get("gists")).hasSize(CallAccumulator.MAX_GISTS);
            assertThat(memory.get("gists").get(0).get("summary").asText()).isEqualTo("Old call 4.");

            JsonNode b = d.get("buyer");
            assertThat(b.get("firstLanguage").asText()).isEqualTo("en-IN");
            assertThat(b.get("languageChanges").get(0).get("reason").asText()).isEqualTo("named");
            assertThat(b.get("joinedMs").asLong()).isEqualTo(T0 + 8000);
            assertThat(b.get("endedMs").asLong()).isEqualTo(T0 + 16_000);
            assertThat(b.get("latency").get("translation").asLong()).isEqualTo(500);
            assertThat(b.get("match").get("buyerId").asText()).isEqualTo("mehta-agro");
            assertThat(b.get("match").get("name").asText()).isEqualTo("Mehta Agro Traders");

            CallRecord.Summary row = r.summary();
            assertThat(row.buyerName()).isEqualTo("Mehta Agro Traders");
            assertThat(row.cautions()).isEqualTo(1);
            assertThat(row.tools()).isEqualTo(3);
            assertNoText(r);
        }
    }

    /**
     * Before ticket #19 the buyer's yes alone kept nothing of the farmer's leg. Now the farmer leg's operational data
     * (timings, tools by name, advice kinds) is kept for every call, and only the farmer's content stays out.
     */
    @Test
    void theBuyersYesAloneKeepsTheTermsAndOperationalDataButNothingRememberedOfTheFarmer() throws Exception {
        try (Harness h = new Harness("jdbc:sqlite::memory:")) {
            call(h.bus, "call-b", "NO", "YES", true);
            CallRecord r = h.store.get("call-b").orElseThrow();
            JsonNode d = r.data();
            assertThat(d.get("farmer").get("memory").isNull()).isTrue();
            assertThat(d.get("farmer").get("latency").get("turns").asInt()).isEqualTo(1);
            assertThat(d.get("notStored").get("farmer").asText()).isEqualTo("said_no");
            assertThat(r.farmerKey()).isNull();
            assertThat(r.consentScope()).isEqualTo("buyer");
            assertThat(d.get("deal").get("commodity").asText()).isEqualTo("Onion");
            assertThat(d.get("deal").get("termsOnly").asBoolean()).isFalse();
            // Privacy review B2: that the lookup ran, and when; never its result.
            assertThat(d.get("price").get("atMs").asLong()).isEqualTo(T0 + 5000);
            assertThat(JSON.writeValueAsString(r)).doesNotContain(PRICE);
            assertThat(d.get("buyer").get("match").get("name").asText()).isEqualTo("Mehta Agro Traders");
            assertThat(r.summary().cautions()).isEqualTo(1);
            assertThat(r.summary().tools()).isEqualTo(3);
            assertThat(JSON.writeValueAsString(r)).doesNotContain("Suresh").doesNotContain("Old call");
            assertNoText(r);
        }
    }

    /**
     * Before ticket #19 the farmer's yes alone kept no buyer match or buyer timings. The buyer leg's language, timings
     * and the synthetic directory match are operational now, kept for every call; nothing buyer-private is stored.
     */
    @Test
    void theFarmersYesAloneStillKeepsTheBuyerLegsOperationalData() throws Exception {
        try (Harness h = new Harness("jdbc:sqlite::memory:")) {
            call(h.bus, "call-f", "YES", "NO", true);
            CallRecord r = h.store.get("call-f").orElseThrow();
            JsonNode b = r.data().get("buyer");
            assertThat(b.get("latency").get("translation").asLong()).isEqualTo(500);
            assertThat(b.get("match").get("buyerId").asText()).isEqualTo("mehta-agro");
            assertThat(r.summary().buyerName()).isEqualTo("Mehta Agro Traders");
            assertThat(r.data().get("notStored").has("buyer")).isFalse();
            assertThat(r.consentScope()).isEqualTo("farmer");
            assertThat(r.data().get("farmer").get("memory").get("facts")).hasSize(1);
            assertNoText(r);
        }
    }

    @Test
    void aCallerWhoDeniesBeingTheRememberedFarmerGetsNoMemoryAttached() throws Exception {
        try (Harness h = new Harness("jdbc:sqlite::memory:")) {
            call(h.bus, "call-d", "YES", "YES", false);
            CallRecord r = h.store.get("call-d").orElseThrow();
            JsonNode f = r.data().get("farmer");
            assertThat(f.get("identity").asText()).isEqualTo("denied");
            assertThat(f.get("memory").isNull()).isTrue();
            assertThat(r.data().get("notStored").get("memory").asText()).isEqualTo("not_this_farmer");
            assertThat(JSON.writeValueAsString(r)).doesNotContain("Suresh").doesNotContain("Old call");
            // The caller's own key is still kept, so "forget me" on this number removes the record.
            assertThat(r.farmerKey()).isEqualTo(FakeLeg.FARMER_KEY.value());
        }
    }

    @Test
    void aLookupOnlyCallIsInformationOnly() {
        try (Harness h = new Harness("jdbc:sqlite::memory:")) {
            CallId c = CallId.of("call-i");
            LegId f = LegId.of("call-i-f");
            h.bus.publish(new ConsoleEvent.CallStarted(c, T0, "hi-IN"));
            h.bus.publish(new ConsoleEvent.LegJoined(c, T0, f, LegRole.FARMER, LegSource.SIMULATOR, "hi-IN"));
            h.bus.publish(new ConsoleEvent.MemoryLoaded(c, T0 + 10, FakeLeg.FARMER_KEY, false, List.of(), List.of()));
            h.bus.publish(new ConsoleEvent.ToolCalled(c, T0 + 1000, "get_weather", JSON.createObjectNode(), true, "x", 9));
            h.bus.publish(new ConsoleEvent.ConsentRecorded(c, LegRole.FARMER, "YES", T0 + 2000));
            h.bus.publish(new ConsoleEvent.CallEnded(c, T0 + 3000, 3000, "hangup"));
            CallRecord r = h.store.get("call-i").orElseThrow();
            assertThat(r.outcome()).isEqualTo("info_only");
            assertThat(r.data().get("price").isNull()).isTrue();
            assertThat(r.data().get("deal").isNull()).isTrue();
        }
    }

    @Test
    void aVersionOneTableIsMigratedAndItsRowsStillOpen(@TempDir Path dir) throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("g-one.db");
        try (Connection conn = DriverManager.getConnection(url); Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE call_records (
                        call_id TEXT PRIMARY KEY, started_ms INTEGER NOT NULL, ended_ms INTEGER NOT NULL,
                        duration_ms INTEGER NOT NULL, end_reason TEXT NOT NULL, languages TEXT NOT NULL,
                        outcome TEXT NOT NULL, consent_farmer TEXT NOT NULL, consent_buyer TEXT NOT NULL,
                        farmer_key_hash TEXT, data TEXT NOT NULL, created_at INTEGER NOT NULL)""");
            st.execute("""
                    INSERT INTO call_records VALUES ('call-old', 1000, 61000, 60000, 'hangup', '{"farmer":"hi-IN"}',
                    'no_terms', 'YES', 'NONE', 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    '{"deal":null,"notStored":{"buyer":"no_leg"},"farmer":{"legId":"f","source":"simulator",
                    "language":"hi-IN","joinedMs":1000,"endReason":"hangup","timings":[],"tools":[],"advice":[],
                    "memory":{"returning":false,"facts":[],"gists":[]}},"buyer":null}', 62000)""");
        }
        try (Harness h = new Harness(url)) {
            CallRecord old = h.store.get("call-old").orElseThrow();
            assertThat(old.schemaVersion()).isEqualTo(1);
            // An HMAC caller key (64 hex digits) is kept; the scrub keeps nothing else in that column.
            assertThat(old.farmerKey()).isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
            assertThat(old.data().get("farmer").get("language").asText()).isEqualTo("hi-IN");
            assertThat(old.summary().buyerName()).isNull();
            assertThat(old.summary().consentScope()).isEqualTo("farmer");
            assertThat(old.summary().termsOnly()).isFalse();
            call(h.bus, "call-new", "YES", "YES", true);
            assertThat(h.store.list(10)).extracting(CallRecord::schemaVersion).containsExactly(CallRecord.SCHEMA_VERSION, 1);
            assertThat(h.store.deleteByFarmerKey("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")).isEqualTo(1);
        }
        // Opening again does not migrate twice.
        try (Harness h = new Harness(url)) {
            assertThat(h.store.list(10)).extracting(CallRecord::callId).containsExactly("call-new");
        }
    }

    private static void assertNoText(CallRecord r) throws Exception {
        String all = JSON.writeValueAsString(r);
        assertThat(all).doesNotContain("CANARY").doesNotContain("\"text\"").doesNotContain("args")
                .doesNotContain(FakeLeg.FARMER_KEY.value());
    }
}
