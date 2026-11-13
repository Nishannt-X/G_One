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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The consent rules for stored call records (owner decision, ticket #19: every call is stored; consent gates only the
 * conversation-derived fields), driven through the console bus as a real call would be. Synthetic data
 * only; the "secret" strings below are canaries that must never reach a stored record.
 */
class CallRecordRulesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long T0 = 1_794_132_000_000L;
    private static final String FARMER_WORDS = "CANARY-farmer-said-onion-ninety-quintal";
    private static final String BUYER_WORDS = "CANARY-buyer-said-two-thousand";
    private static final String TRANSLATION = "CANARY-translated-line";
    private static final String ASSISTANT = "CANARY-assistant-reply";
    private static final String WHISPER = "CANARY-whisper-private-advice";
    private static final String TOOL_ARG = "CANARY-tool-argument";
    private static final String MEMORY_TOOL_RESULT = "CANARY-memory-tool-result";
    private static final String PHONE = "+15005550006";

    private final ConsoleBus bus = new ConsoleBus();
    private final CallRecordStore store = new CallRecordStore("jdbc:sqlite::memory:");
    private final CallRecordWriter writer = new CallRecordWriter(bus, store, JSON,
            Clock.fixed(Instant.ofEpochMilli(T0 + 999_000), ZoneOffset.UTC));

    @AfterEach
    void close() {
        writer.close();
        store.close();
    }

    /** A full synthetic call: both legs, turns, a whisper, tools, memory, a deal, then the given consents. */
    private void call(String id, String farmerConsent, String buyerConsent, boolean buyerLeg, boolean memory) {
        CallId c = CallId.of(id);
        LegId f = LegId.of(id + "-f");
        LegId b = LegId.of(id + "-b");
        List<ConsoleEvent> events = new ArrayList<>();
        events.add(new ConsoleEvent.CallStarted(c, T0, "hi-IN"));
        events.add(new ConsoleEvent.LegJoined(c, T0, f, LegRole.FARMER, LegSource.TWILIO, "hi-IN"));
        if (memory) {
            events.add(new ConsoleEvent.MemoryLoaded(c, T0 + 10, FakeLeg.FARMER_KEY, true,
                    List.of(new ProfileFact("name", "Suresh", Provenance.said(CallId.of("old"), T0 - 5000))),
                    List.of(new CallGist(CallId.of("old"), T0 - 9000, T0 - 8000, "Asked about onion prices.",
                            List.of("Call back with a buyer")))));
        }
        events.add(new ConsoleEvent.TurnHeard(c, T0 + 2000, f, LegRole.FARMER, "hi-IN", FARMER_WORDS, T0 + 1000, T0 + 1500));
        events.add(new ConsoleEvent.AssistantSaid(c, T0 + 2600, f, LegRole.FARMER, "hi-IN", ASSISTANT));
        events.add(new ConsoleEvent.ToolCalled(c, T0 + 2100, "get_mandi_price", JSON.createObjectNode().put("q", TOOL_ARG),
                true, "Onion at Lasalgaon: 2,150 rupees per quintal", 120));
        events.add(new ConsoleEvent.ToolCalled(c, T0 + 2200, "save_profile_fact", JSON.createObjectNode().put("v", TOOL_ARG),
                true, MEMORY_TOOL_RESULT, 15));
        events.add(new ConsoleEvent.WhisperSent(c, T0 + 2700, f, ConsoleEvent.WhisperKind.CAUTION, "hi-IN", WHISPER));
        // A private question to G-One and its answer: never stored, not even as a timing.
        events.add(new ConsoleEvent.AsideAnswered(c, T0 + 2800, f, LegRole.FARMER, "hi-IN", "CANARY-aside-question",
                "CANARY-aside-answer", T0 + 2750));
        if (buyerLeg) {
            events.add(new ConsoleEvent.LegJoined(c, T0 + 3000, b, LegRole.BUYER, LegSource.SIMULATOR, "ta-IN"));
            events.add(new ConsoleEvent.TurnHeard(c, T0 + 4000, b, LegRole.BUYER, "ta-IN", BUYER_WORDS, T0 + 3500, T0 + 3800));
            events.add(new ConsoleEvent.TurnTranslated(c, T0 + 4400, b, LegRole.BUYER, LegRole.FARMER, "ta-IN", "hi-IN",
                    TRANSLATION, T0 + 3500));
            events.add(new ConsoleEvent.DealUpdated(c, T0 + 5000, ConsoleEvent.DealState.AGREED, "onion", null,
                    "Lasalgaon", "10 quintal", new BigDecimal("2100"), "quintal", "Friday", true, true));
            events.add(new ConsoleEvent.LegEnded(c, T0 + 6000, b, LegRole.BUYER, "hangup"));
        }
        if (farmerConsent != null) events.add(new ConsoleEvent.ConsentRecorded(c, LegRole.FARMER, farmerConsent, T0 + 6500));
        if (buyerConsent != null) events.add(new ConsoleEvent.ConsentRecorded(c, LegRole.BUYER, buyerConsent, T0 + 6500));
        events.add(new ConsoleEvent.LegEnded(c, T0 + 7000, f, LegRole.FARMER, "hangup"));
        events.add(new ConsoleEvent.CallEnded(c, T0 + 7000, 7000, "deal_agreed"));
        events.forEach(bus::publish);
    }

    static Stream<Arguments> table() {
        // farmer consent, buyer consent, buyer leg, memory (caller key), expect the farmer's gated fields, expect
        // "terms only". Every call is stored: before ticket #19 a call without a yes left nothing at all.
        return Stream.of(
                Arguments.of("YES", "YES", true, true, true, false),
                Arguments.of("YES", "NO", true, true, true, false),
                Arguments.of("NO", "YES", true, true, false, false),
                Arguments.of("NO", "NO", true, true, false, true),
                Arguments.of("NONE", "NONE", true, true, false, true),
                Arguments.of(null, null, true, true, false, true),
                Arguments.of("YES", null, true, true, true, false),
                Arguments.of("YES", "YES", false, true, true, false),
                Arguments.of(null, "YES", true, true, false, false),
                Arguments.of("YES", "YES", true, false, false, false),
                Arguments.of("YES", "NONE", true, false, false, false),
                Arguments.of(null, "YES", false, true, false, true),
                Arguments.of("yes", "maybe", true, true, true, false));
    }

    @ParameterizedTest(name = "farmer={0} buyer={1} buyerLeg={2} key={3}")
    @MethodSource("table")
    void storesEveryCallAndGatesOnlyTheFarmersContent(String farmer, String buyer, boolean buyerLeg, boolean memory,
                                                      boolean keyed, boolean termsOnly) throws Exception {
        call("call-1", farmer, buyer, buyerLeg, memory);
        CallRecord r = store.get("call-1").orElseThrow(() -> new AssertionError("every ended call is stored"));
        JsonNode data = r.data();
        // Operational: every call, whoever said yes.
        assertThat(data.get("farmer").isObject()).isTrue();
        assertThat(data.get("buyer").isNull()).isEqualTo(!buyerLeg);
        assertThat(r.languages()).containsEntry("farmer", "hi-IN");
        assertThat(r.languages().containsKey("buyer")).isEqualTo(buyerLeg);
        assertThat(r.durationMs()).isEqualTo(7000);
        JsonNode f = data.get("farmer");
        assertThat(f.get("timings")).hasSize(1);
        assertThat(f.get("timings").get(0).get("repliedMs").asLong()).isEqualTo(T0 + 2600);
        assertThat(f.get("advice").get(0).get("kind").asText()).isEqualTo("caution");
        assertThat(f.get("tools")).hasSize(2);
        if (buyerLeg) {
            JsonNode b = data.get("buyer");
            assertThat(b.get("language").asText()).isEqualTo("ta-IN");
            assertThat(b.get("timings").get(0).get("translatedMs").asLong()).isEqualTo(T0 + 4400);
            assertThat(b.has("tools")).isFalse();
            assertThat(b.has("memory")).isFalse();
            // The deal card: every call, labelled when nobody said yes.
            assertThat(data.get("deal").get("commodity").asText()).isEqualTo("onion");
            assertThat(data.get("deal").get("price").decimalValue()).isEqualByComparingTo("2100");
            assertThat(data.get("deal").get("termsOnly").asBoolean()).isEqualTo(termsOnly);
            assertThat(r.outcome()).isEqualTo("agreed");
        } else {
            assertThat(data.get("deal").isNull()).isTrue();
            // A price lookup with no buyer is an information-only call.
            assertThat(r.outcome()).isEqualTo("info_only");
        }
        assertThat(data.get("termsOnly").asBoolean()).isEqualTo(termsOnly);
        assertThat(r.termsOnly()).isEqualTo(termsOnly);
        // Gated: only the farmer's yes, with a caller ID, keeps the caller key and the memory snapshot.
        assertThat(r.farmerKey()).isEqualTo(keyed ? FakeLeg.FARMER_KEY.value() : null);
        assertThat(data.get("notStored").has("farmer")).isEqualTo(!keyed);
        assertThat(f.get("memory").isObject()).isEqualTo(keyed);
        if (keyed) {
            assertThat(f.get("memory").get("facts").get(0).get("value").asText()).isEqualTo("Suresh");
        } else {
            assertThat(JSON.writeValueAsString(r)).doesNotContain("Suresh");
        }
        assertNoVerbatimOrSecrets(r);
    }

    private static void assertNoVerbatimOrSecrets(CallRecord r) throws Exception {
        String all = JSON.writeValueAsString(r.data()) + JSON.writeValueAsString(r.summary()) + r.languages();
        for (String canary : List.of(FARMER_WORDS, BUYER_WORDS, TRANSLATION, ASSISTANT, WHISPER, TOOL_ARG,
                MEMORY_TOOL_RESULT, PHONE, "CANARY")) {
            assertThat(all).as("stored record").doesNotContain(canary);
        }
        assertThat(all).doesNotContain("\"text\"").doesNotContain("audio").doesNotContain("args");
        // The caller key is never in the browser-facing JSON.
        assertThat(JSON.writeValueAsString(r)).doesNotContain(FakeLeg.FARMER_KEY.value());
    }

    /** Before ticket #19 the forgetting call left no record; now it is kept, anonymous (no caller key, no memory). */
    @Test
    void farmerWhoAsksToBeForgottenIsKeptAnonymouslyAndTheirOlderRecordsGo() {
        call("call-1", "YES", "YES", true, true);
        assertThat(store.get("call-1").orElseThrow().farmerKey()).isNotNull();

        CallId c = CallId.of("call-2");
        LegId f = LegId.of("call-2-f");
        bus.publish(new ConsoleEvent.CallStarted(c, T0 + 100_000, "hi-IN"));
        bus.publish(new ConsoleEvent.LegJoined(c, T0 + 100_000, f, LegRole.FARMER, LegSource.TWILIO, "hi-IN"));
        bus.publish(new ConsoleEvent.MemoryLoaded(c, T0 + 100_010, FakeLeg.FARMER_KEY, true, List.of(), List.of()));
        bus.publish(new ConsoleEvent.ToolCalled(c, T0 + 101_000, "forget_me", JSON.createObjectNode(), true, "done", 5));
        bus.publish(new ConsoleEvent.ConsentRecorded(c, LegRole.FARMER, "YES", T0 + 102_000));
        bus.publish(new ConsoleEvent.CallEnded(c, T0 + 103_000, 3000, "hangup"));

        assertThat(store.get("call-1")).isEmpty();
        CallRecord forgetting = store.get("call-2").orElseThrow();
        assertThat(forgetting.farmerKey()).isNull();
        assertThat(forgetting.data().get("notStored").get("farmer").asText()).isEqualTo("forgotten");
        assertThat(forgetting.data().get("farmer").get("memory").isNull()).isTrue();
        assertThat(forgetting.termsOnly()).isTrue();
    }

    @Test
    void theSampleReplayIsNeverStoredAndEndedCallsLeaveNothingOpen() {
        call("replay-1", "YES", "YES", true, true);
        assertThat(store.count()).isZero();
        call("call-1", "NO", "NO", true, true);
        assertThat(writer.openCalls()).isZero();
        // A call without a yes is stored too (terms only, anonymous); before ticket #19 it left nothing.
        assertThat(store.count()).isEqualTo(1);
        assertThat(store.get("call-1").orElseThrow().farmerKey()).isNull();
    }
}
