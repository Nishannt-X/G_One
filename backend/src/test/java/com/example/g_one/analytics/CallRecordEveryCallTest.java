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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The owner's field table (ticket #19): every ended call is recorded with its operational data and deal card; only the
 * farmer's yes unlocks the caller key, the memory snapshot and the stated name; the deal card is labelled "kept without
 * consent: terms only" exactly when no party said yes. Assertions read the serialized JSON, the same shape the
 * Analytics endpoints send. Synthetic data only; every "CANARY" string must never reach a record.
 */
class CallRecordEveryCallTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long T0 = 1_794_132_000_000L;
    private static final String PHONE = "+15005550006";
    private static final String REMEMBERED_NAME = "Suresh";
    private static final String STATED_NAME = "Rameshwar";
    private static final String GIST = "Asked about onion prices last week.";
    private static final List<String> CANARIES = List.of("CANARY-farmer-words", "CANARY-buyer-words",
            "CANARY-translation", "CANARY-reply", "CANARY-whisper", "CANARY-tool-arg", "CANARY-aside-question",
            "CANARY-aside-answer", "CANARY-memory-tool-result", "CANARY-name-result", PHONE, "CANARY");

    private final ConsoleBus bus = new ConsoleBus();
    private final CallRecordStore store = new CallRecordStore("jdbc:sqlite::memory:");
    private final CallRecordWriter writer = new CallRecordWriter(bus, store, JSON,
            Clock.fixed(Instant.ofEpochMilli(T0 + 999_000), ZoneOffset.UTC));

    @AfterEach
    void close() {
        writer.close();
        store.close();
    }

    /** One synthetic call; a null consent publishes no consent event for that party (hung up before the question). */
    private void call(String id, String farmer, String buyer, boolean buyerJoined, boolean withheld, boolean denied) {
        CallId c = CallId.of(id);
        LegId f = LegId.of(id + "-f");
        LegId b = LegId.of(id + "-b");
        List<ConsoleEvent> e = new ArrayList<>();
        e.add(new ConsoleEvent.CallStarted(c, T0, "auto"));
        e.add(new ConsoleEvent.LegJoined(c, T0, f, LegRole.FARMER, LegSource.TWILIO, "auto"));
        if (!withheld) {
            e.add(new ConsoleEvent.MemoryLoaded(c, T0 + 10, FakeLeg.FARMER_KEY, true,
                    List.of(new ProfileFact("name", REMEMBERED_NAME, Provenance.said(CallId.of("old"), T0 - 5000))),
                    List.of(new CallGist(CallId.of("old"), T0 - 9000, T0 - 8000, GIST, List.of("Call back with a buyer")))));
        }
        e.add(new ConsoleEvent.LanguageChanged(c, T0 + 900, f, LegRole.FARMER, "auto", "hi-IN", "detected"));
        e.add(new ConsoleEvent.TurnHeard(c, T0 + 2000, f, LegRole.FARMER, "hi-IN", "CANARY-farmer-words " + PHONE,
                T0 + 1000, T0 + 1500));
        e.add(new ConsoleEvent.AssistantSaid(c, T0 + 2600, f, LegRole.FARMER, "hi-IN", "CANARY-reply"));
        e.add(new ConsoleEvent.TurnMeasured(c, T0 + 2600, "model", "synthetic-model", 120, 30, 1, 600, ""));
        e.add(new ConsoleEvent.ToolCalled(c, T0 + 3000, "confirm_name",
                JSON.createObjectNode().put("confirmed", !denied), true, "CANARY-name-result", 3));
        e.add(new ConsoleEvent.ToolCalled(c, T0 + 3100, "save_profile_fact",
                JSON.createObjectNode().put("field", "name").put("value", STATED_NAME), true, "CANARY-memory-tool-result", 4));
        e.add(new ConsoleEvent.ToolCalled(c, T0 + 3200, "get_mandi_price",
                JSON.createObjectNode().put("commodity", "CANARY-tool-arg").put("phone", PHONE), true,
                "Onion at Lasalgaon: 2,150 rupees per quintal", 120));
        e.add(new ConsoleEvent.WhisperSent(c, T0 + 3300, f, ConsoleEvent.WhisperKind.CAUTION, "hi-IN", "CANARY-whisper"));
        e.add(new ConsoleEvent.AsideAnswered(c, T0 + 3400, f, LegRole.FARMER, "hi-IN", "CANARY-aside-question",
                "CANARY-aside-answer", T0 + 3350));
        if (buyerJoined) {
            e.add(new ConsoleEvent.ToolCalled(c, T0 + 4000, "connect_buyer",
                    JSON.createObjectNode().put("buyerId", "buyer-synthetic-7"), true,
                    "Calling Sharma Traders now. Tell the farmer you are calling them.", 5));
            e.add(new ConsoleEvent.LegJoined(c, T0 + 5000, b, LegRole.BUYER, LegSource.SIMULATOR, "en-IN"));
            e.add(new ConsoleEvent.LanguageChanged(c, T0 + 5500, b, LegRole.BUYER, "en-IN", "ta-IN", "named"));
            e.add(new ConsoleEvent.TurnHeard(c, T0 + 6000, b, LegRole.BUYER, "ta-IN", "CANARY-buyer-words", T0 + 5600,
                    T0 + 5800));
            e.add(new ConsoleEvent.TurnTranslated(c, T0 + 6400, b, LegRole.BUYER, LegRole.FARMER, "ta-IN", "hi-IN",
                    "CANARY-translation", T0 + 5600));
            e.add(new ConsoleEvent.DealUpdated(c, T0 + 7000, ConsoleEvent.DealState.AGREED, "onion", "Red", "Lasalgaon",
                    "10 quintal", new BigDecimal("2100"), "quintal", "Friday at the farm; payment cash on pickup", true,
                    true));
            e.add(new ConsoleEvent.LegEnded(c, T0 + 8000, b, LegRole.BUYER, "hangup"));
        }
        if (farmer != null) e.add(new ConsoleEvent.ConsentRecorded(c, LegRole.FARMER, farmer, T0 + 8500));
        if (buyer != null) e.add(new ConsoleEvent.ConsentRecorded(c, LegRole.BUYER, buyer, T0 + 8500));
        e.add(new ConsoleEvent.LegEnded(c, T0 + 9000, f, LegRole.FARMER, "hangup"));
        e.add(new ConsoleEvent.CallEnded(c, T0 + 9000, 9000, "hangup"));
        e.forEach(bus::publish);
    }

    static Stream<Arguments> matrix() {
        List<Arguments> out = new ArrayList<>();
        for (String farmer : new String[] {"YES", "NO", "NONE"}) {
            for (String buyer : new String[] {"YES", "NO", "NONE"}) {
                for (boolean joined : new boolean[] {true, false}) {
                    for (boolean withheld : new boolean[] {false, true}) {
                        for (boolean denied : new boolean[] {false, true}) {
                            out.add(Arguments.of(farmer, buyer, joined, withheld, denied));
                        }
                    }
                }
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "farmer={0} buyer={1} buyerJoined={2} withheld={3} denied={4}")
    @MethodSource("matrix")
    void everyCallIsRecordedAndOnlyTheFarmersYesUnlocksTheGatedFields(String farmer, String buyer, boolean joined,
                                                                        boolean withheld, boolean denied) throws Exception {
        call("call-m", farmer, buyer, joined, withheld, denied);
        CallRecord r = store.get("call-m").orElseThrow(() -> new AssertionError("every ended call has a record"));
        JsonNode rec = JSON.valueToTree(r);
        JsonNode row = JSON.valueToTree(r.summary());
        JsonNode d = rec.get("data");

        boolean farmerYes = farmer.equals("YES");
        boolean anyYes = farmerYes || (buyer.equals("YES") && joined);
        boolean keyed = farmerYes && !withheld;

        // Operational: always.
        assertThat(rec.get("startedMs").asLong()).isEqualTo(T0);
        assertThat(rec.get("endedMs").asLong()).isEqualTo(T0 + 9000);
        assertThat(rec.get("durationMs").asLong()).isEqualTo(9000);
        assertThat(rec.get("endReason").asText()).isEqualTo("hangup");
        assertThat(rec.get("consentFarmer").asText()).isEqualTo(farmer);
        assertThat(rec.get("consentBuyer").asText()).isEqualTo(buyer);
        assertThat(rec.get("schemaVersion").asInt()).isEqualTo(3);
        assertThat(rec.get("outcome").asText()).isEqualTo(joined ? "agreed" : "info_only");
        assertThat(d.get("joined").get("farmer").asBoolean()).isTrue();
        assertThat(d.get("joined").get("buyer").asBoolean()).isEqualTo(joined);
        assertThat(rec.get("languages").get("farmer").asText()).isEqualTo("hi-IN");
        assertThat(rec.get("languages").has("buyer")).isEqualTo(joined);
        JsonNode f = d.get("farmer");
        assertThat(f.get("firstLanguage").asText()).isEqualTo("auto");
        assertThat(f.get("languageChanges").get(0).get("to").asText()).isEqualTo("hi-IN");
        assertThat(f.get("latency").get("turns").asInt()).isEqualTo(1);
        assertThat(f.get("latency").get("transcript").asLong()).isEqualTo(500);
        assertThat(f.get("identity").asText()).isEqualTo(denied ? "denied" : "confirmed");
        assertThat(f.get("tools")).extracting(t -> t.get("tool").asText()).startsWith("confirm_name",
                "save_profile_fact", "get_mandi_price");
        JsonNode price = f.get("tools").get(2);
        assertThat(price.get("durationMs").asLong()).isEqualTo(120);
        assertThat(price.get("offsetMs").asLong()).isEqualTo(3200);
        assertThat(price.has("result")).isFalse();
        assertThat(price.has("args")).isFalse();
        assertThat(f.get("advice")).hasSize(1);
        assertThat(f.get("advice").get(0).get("kind").asText()).isEqualTo("caution");
        assertThat(f.get("advice").get(0).get("offsetMs").asLong()).isEqualTo(3300);
        assertThat(d.get("brain").get("turns").asInt()).isEqualTo(1);
        assertThat(d.get("brain").get("latencyMs").asLong()).isEqualTo(600);
        assertThat(row.get("tools").asInt()).isEqualTo(joined ? 4 : 3);
        assertThat(row.get("cautions").asInt()).isEqualTo(1);
        if (joined) {
            JsonNode bSide = d.get("buyer");
            assertThat(bSide.get("language").asText()).isEqualTo("ta-IN");
            assertThat(bSide.get("languageChanges").get(0).get("reason").asText()).isEqualTo("named");
            assertThat(bSide.get("latency").get("translation").asLong()).isEqualTo(400);
            assertThat(bSide.get("match").get("buyerId").asText()).isEqualTo("buyer-synthetic-7");
            assertThat(bSide.get("match").get("name").asText()).isEqualTo("Sharma Traders");
            assertThat(row.get("buyerName").asText()).isEqualTo("Sharma Traders");
            JsonNode deal = d.get("deal");
            assertThat(deal.get("commodity").asText()).isEqualTo("onion");
            assertThat(deal.get("quantity").asText()).isEqualTo("10 quintal");
            assertThat(deal.get("price").decimalValue()).isEqualByComparingTo("2100");
            assertThat(deal.get("unit").asText()).isEqualTo("quintal");
            assertThat(deal.get("basis").asText()).isEqualTo("per quintal");
            assertThat(deal.get("total").decimalValue()).isEqualByComparingTo("21000");
            assertThat(deal.get("market").asText()).isEqualTo("Lasalgaon");
            assertThat(deal.get("pickup").asText()).isEqualTo("Friday at the farm; payment cash on pickup");
            assertThat(deal.get("payment").asText()).isEqualTo("cash on pickup");
            assertThat(deal.get("farmerYes").asBoolean()).isTrue();
            assertThat(deal.get("buyerYes").asBoolean()).isTrue();
            assertThat(deal.get("buyerId").asText()).isEqualTo("buyer-synthetic-7");
            assertThat(deal.get("buyerName").asText()).isEqualTo("Sharma Traders");
            assertThat(deal.get("termsOnly").asBoolean()).isEqualTo(!anyYes);
            assertThat(deal.path("label").asText(null))
                    .isEqualTo(anyYes ? null : "kept without consent: terms only");
            assertThat(row.get("deal").get("termsOnly").asBoolean()).isEqualTo(!anyYes);
        } else {
            assertThat(d.get("buyer").isNull()).isTrue();
            assertThat(d.get("deal").isNull()).isTrue();
        }

        // The terms-only marker and the consent scope, on the record and the list row.
        assertThat(d.get("termsOnly").asBoolean()).isEqualTo(!anyYes);
        assertThat(row.get("termsOnly").asBoolean()).isEqualTo(!anyYes);
        assertThat(row.get("consentScope").asText()).isEqualTo(d.get("consentScope").asText());
        String scope = farmerYes && buyer.equals("YES") && joined ? "both" : farmerYes ? "farmer"
                : buyer.equals("YES") && joined ? "buyer" : "none";
        assertThat(d.get("consentScope").asText()).isEqualTo(scope);

        // Gated: only with the farmer's yes (and a caller ID); the memory snapshot also needs the name check not denied.
        assertThat(r.farmerKey()).isEqualTo(keyed ? FakeLeg.FARMER_KEY.value() : null);
        boolean memory = keyed && !denied;
        assertThat(f.path("memory").isObject()).isEqualTo(memory);
        String all = JSON.writeValueAsString(r) + JSON.writeValueAsString(r.summary());
        assertThat(all.contains(REMEMBERED_NAME)).as("remembered name").isEqualTo(memory);
        assertThat(all.contains(GIST)).as("remembered gist").isEqualTo(memory);
        assertThat(f.path("statedName").asText(null)).isEqualTo(keyed ? STATED_NAME : null);
        assertThat(all.contains(STATED_NAME)).as("stated name").isEqualTo(keyed);
        if (!keyed) {
            assertThat(d.get("notStored").get("farmer").asText()).isEqualTo(withheld ? "withheld"
                    : farmer.equals("NO") ? "said_no" : "no_answer");
        }
        // Never stored: words, whisper text, tool arguments or results, phone numbers, the caller key.
        for (String canary : CANARIES) {
            assertThat(all).as("serialized record").doesNotContain(canary);
        }
        assertThat(all).doesNotContain(FakeLeg.FARMER_KEY.value()).doesNotContain("\"args\"")
                .doesNotContain("\"text\"");
        // The mandi price reference (market data, not anyone's words) is kept only with at least one yes.
        assertThat(d.get("price").isNull()).isEqualTo(!anyYes);
        assertThat(all.contains("Onion at Lasalgaon: 2,150")).isEqualTo(anyYes);
    }

    @Test
    void aCallThatHangsUpBeforeTheConsentQuestionsIsStillRecorded() {
        CallId c = CallId.of("call-hangup");
        LegId f = LegId.of("call-hangup-f");
        bus.publish(new ConsoleEvent.CallStarted(c, T0, "hi-IN"));
        bus.publish(new ConsoleEvent.LegJoined(c, T0, f, LegRole.FARMER, LegSource.TWILIO, "hi-IN"));
        bus.publish(new ConsoleEvent.MemoryLoaded(c, T0 + 10, FakeLeg.FARMER_KEY, false, List.of(), List.of()));
        bus.publish(new ConsoleEvent.TurnHeard(c, T0 + 2000, f, LegRole.FARMER, "hi-IN", "CANARY-words", T0 + 1000,
                T0 + 1500));
        bus.publish(new ConsoleEvent.LegEnded(c, T0 + 3000, f, LegRole.FARMER, "hangup"));
        bus.publish(new ConsoleEvent.CallEnded(c, T0 + 3000, 3000, "hangup"));
        CallRecord r = store.get("call-hangup").orElseThrow();
        JsonNode d = JSON.valueToTree(r).get("data");
        assertThat(r.consentFarmer()).isEqualTo(Consent.NONE);
        assertThat(r.consentBuyer()).isEqualTo(Consent.NONE);
        assertThat(r.farmerKey()).isNull();
        assertThat(r.outcome()).isEqualTo("no_terms");
        assertThat(d.get("termsOnly").asBoolean()).isTrue();
        assertThat(d.get("consentScope").asText()).isEqualTo("none");
        assertThat(d.get("farmer").get("latency").get("turns").asInt()).isEqualTo(1);
        assertThat(writer.openCalls()).isZero();
    }

    @Test
    void aFailedCallWithNoLegAndAnAssistantHangupAreRecorded() {
        CallId failed = CallId.of("call-failed");
        bus.publish(new ConsoleEvent.CallStarted(failed, T0, "hi-IN"));
        bus.publish(new ConsoleEvent.CallEnded(failed, T0 + 400, 400, "error"));
        CallId ended = CallId.of("call-ended-by-g-one");
        LegId f = LegId.of("call-ended-by-g-one-f");
        bus.publish(new ConsoleEvent.CallStarted(ended, T0 + 10_000, "hi-IN"));
        bus.publish(new ConsoleEvent.LegJoined(ended, T0 + 10_000, f, LegRole.FARMER, LegSource.SIMULATOR, "hi-IN"));
        bus.publish(new ConsoleEvent.ToolCalled(ended, T0 + 11_000, "end_call", JSON.createObjectNode(), true, "x", 1));
        bus.publish(new ConsoleEvent.ConsentRecorded(ended, LegRole.FARMER, "NONE", T0 + 12_000));
        bus.publish(new ConsoleEvent.ConsentRecorded(ended, LegRole.BUYER, "NONE", T0 + 12_000));
        bus.publish(new ConsoleEvent.LegEnded(ended, T0 + 12_000, f, LegRole.FARMER, "assistant_hangup"));
        bus.publish(new ConsoleEvent.CallEnded(ended, T0 + 12_000, 2000, "assistant_hangup"));

        CallRecord r = store.get("call-failed").orElseThrow();
        assertThat(r.endReason()).isEqualTo("error");
        assertThat(r.durationMs()).isEqualTo(400);
        JsonNode d = JSON.valueToTree(r).get("data");
        assertThat(d.get("joined").get("farmer").asBoolean()).isFalse();
        assertThat(d.get("termsOnly").asBoolean()).isTrue();
        CallRecord byGOne = store.get("call-ended-by-g-one").orElseThrow();
        assertThat(byGOne.endReason()).isEqualTo("assistant_hangup");
        assertThat(JSON.valueToTree(byGOne).get("data").get("farmer").get("tools").get(0).get("tool").asText())
                .isEqualTo("end_call");
        assertThat(store.count()).isEqualTo(2);
    }

    @Test
    void twoCallsMakeTwoRecordsAndTheSummaryCountsYesAndNone() {
        call("call-yes", "YES", "NO", true, false, false);
        call("call-none", "NO", "NONE", true, false, false);
        assertThat(store.count()).isEqualTo(2);
        JsonNode s = JSON.valueToTree(AnalyticsSummary.of(store.list(10)));
        assertThat(s.get("storedCalls").asInt()).isEqualTo(2);
        assertThat(s.get("callsWithYes").asInt()).isEqualTo(1);
        assertThat(s.get("callsWithoutYes").asInt()).isEqualTo(1);
        assertThat(s.get("bothYesDeals").asInt()).isEqualTo(2);
        assertThat(s.get("toolCounts").get("get_mandi_price").asInt()).isEqualTo(2);
        // Every call's turns count now: farmer turns took 500 ms to transcribe, buyer turns 200 ms.
        assertThat(s.get("medianMs").get("transcript").asLong()).isEqualTo(350);
    }

    @Test
    void forgetMeDeletesOnlyKeyedRecordsAndLeavesAnonymousOnesStoredAnonymously() {
        call("call-keyed", "YES", "YES", true, false, false);
        call("call-anon", "NO", "YES", true, false, false);
        assertThat(store.get("call-keyed").orElseThrow().farmerKey()).isEqualTo(FakeLeg.FARMER_KEY.value());
        assertThat(store.get("call-anon").orElseThrow().farmerKey()).isNull();

        CallId c = CallId.of("call-forget");
        LegId f = LegId.of("call-forget-f");
        bus.publish(new ConsoleEvent.CallStarted(c, T0 + 100_000, "hi-IN"));
        bus.publish(new ConsoleEvent.LegJoined(c, T0 + 100_000, f, LegRole.FARMER, LegSource.TWILIO, "hi-IN"));
        bus.publish(new ConsoleEvent.MemoryLoaded(c, T0 + 100_010, FakeLeg.FARMER_KEY, true,
                List.of(new ProfileFact("name", REMEMBERED_NAME, Provenance.said(CallId.of("old"), T0))), List.of()));
        bus.publish(new ConsoleEvent.ToolCalled(c, T0 + 101_000, "forget_me", JSON.createObjectNode(), true, "done", 5));
        bus.publish(new ConsoleEvent.ConsentRecorded(c, LegRole.FARMER, "YES", T0 + 102_000));
        bus.publish(new ConsoleEvent.CallEnded(c, T0 + 103_000, 3000, "hangup"));

        assertThat(store.get("call-keyed")).isEmpty();
        assertThat(store.get("call-anon")).isPresent();
        CallRecord forgetCall = store.get("call-forget").orElseThrow();
        assertThat(forgetCall.farmerKey()).isNull();
        JsonNode d = JSON.valueToTree(forgetCall).get("data");
        assertThat(d.get("notStored").get("farmer").asText()).isEqualTo("forgotten");
        assertThat(d.get("farmer").path("memory").isObject()).isFalse();
        assertThat(d.get("consentScope").asText()).isEqualTo("none");
        assertThat(forgetCall.toString()).doesNotContain(REMEMBERED_NAME);
        // The console's "forget" finds nothing more: anonymous records are not linked to the caller.
        assertThat(store.deleteByFarmerKey(FakeLeg.FARMER_KEY.value())).isZero();
        assertThat(store.count()).isEqualTo(2);
    }

    /** Ask G-One: a private question leaves no timing either, and a stated "name" that is a long number is dropped. */
    @Test
    void anAsideLeavesNoTimingAndANumberIsNeverKeptAsAStatedName() {
        CallId c = CallId.of("call-aside");
        LegId f = LegId.of("call-aside-f");
        bus.publish(new ConsoleEvent.CallStarted(c, T0, "hi-IN"));
        bus.publish(new ConsoleEvent.LegJoined(c, T0, f, LegRole.FARMER, LegSource.TWILIO, "hi-IN"));
        bus.publish(new ConsoleEvent.MemoryLoaded(c, T0 + 10, FakeLeg.FARMER_KEY, false, List.of(), List.of()));
        bus.publish(new ConsoleEvent.TurnHeard(c, T0 + 2000, f, LegRole.FARMER, "hi-IN", "CANARY-shared", T0 + 1000,
                T0 + 1500));
        bus.publish(new ConsoleEvent.TurnHeard(c, T0 + 4000, f, LegRole.FARMER, "hi-IN", "CANARY-aside", T0 + 3000,
                T0 + 3500));
        bus.publish(new ConsoleEvent.AsideAnswered(c, T0 + 4500, f, LegRole.FARMER, "hi-IN", "CANARY-aside",
                "CANARY-answer", T0 + 3000));
        bus.publish(new ConsoleEvent.ToolCalled(c, T0 + 5000, "save_profile_fact",
                JSON.createObjectNode().put("field", "name").put("value", "98765 43210"), true, "x", 1));
        bus.publish(new ConsoleEvent.ConsentRecorded(c, LegRole.FARMER, "YES", T0 + 6000));
        bus.publish(new ConsoleEvent.CallEnded(c, T0 + 6000, 6000, "hangup"));
        CallRecord r = store.get("call-aside").orElseThrow();
        JsonNode farmerSide = r.data().get("farmer");
        assertThat(farmerSide.get("timings")).hasSize(1);
        assertThat(farmerSide.get("timings").get(0).get("startedMs").asLong()).isEqualTo(T0 + 1000);
        assertThat(farmerSide.has("statedName")).isFalse();
        assertThat(r.farmerKey()).isEqualTo(FakeLeg.FARMER_KEY.value());
        assertThat(r.data().toString()).doesNotContain("98765").doesNotContain("CANARY");
    }

    @Test
    void theRetentionCapStillEvictsTheOldestRecordedCalls() {
        try (CallRecordStore small = new CallRecordStore("jdbc:sqlite::memory:", 2);
             CallRecordWriter w = new CallRecordWriter(bus, small, JSON, Clock.systemUTC())) {
            for (int i = 1; i <= 3; i++) {
                CallId c = CallId.of("cap-" + i);
                bus.publish(new ConsoleEvent.CallStarted(c, T0 + i * 1000L, "hi-IN"));
                bus.publish(new ConsoleEvent.CallEnded(c, T0 + i * 1000L + 500, 500, "hangup"));
            }
            assertThat(small.list(10)).extracting(CallRecord::callId).containsExactly("cap-3", "cap-2");
        }
    }

    @Test
    void aVersionTwoRowStillOpensAndReadsAsAYesCall(@TempDir Path dir) throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("g-one.db");
        try (CallRecordStore s = new CallRecordStore(url)) {
            assertThat(s.count()).isZero(); // creates the current table
        }
        try (Connection conn = DriverManager.getConnection(url); Statement st = conn.createStatement()) {
            st.execute("""
                    INSERT INTO call_records VALUES ('call-v2', 1000, 61000, 60000, 'hangup', '{"buyer":"ta-IN"}',
                    'no_deal', 'NO', 'YES', NULL,
                    '{"deal":{"commodity":"onion","farmerYes":false,"buyerYes":true},"price":null,
                    "notStored":{"farmer":"said_no"},"farmer":null,"buyer":{"legId":"b","language":"ta-IN",
                    "timings":[],"match":{"buyerId":"buyer-synthetic-7","name":"Sharma Traders"}}}', 62000, 2)""");
        }
        try (CallRecordStore s = new CallRecordStore(url)) {
            CallRecord old = s.get("call-v2").orElseThrow();
            assertThat(old.schemaVersion()).isEqualTo(2);
            JsonNode row = JSON.valueToTree(old.summary());
            assertThat(row.get("consentScope").asText()).isEqualTo("buyer");
            assertThat(row.get("termsOnly").asBoolean()).isFalse();
            assertThat(row.get("buyerName").asText()).isEqualTo("Sharma Traders");
            assertThat(row.get("tools").asInt()).isZero();
            JsonNode sum = JSON.valueToTree(AnalyticsSummary.of(s.list(10)));
            assertThat(sum.get("callsWithYes").asInt()).isEqualTo(1);
        }
    }
}
