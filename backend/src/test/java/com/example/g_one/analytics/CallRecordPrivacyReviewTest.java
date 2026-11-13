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
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The privacy review of the every-call record (round 1), driven through the console bus as a real call would be:
 * deal terms are stored only as closed values (B1), no tool result is stored (B2), a per-turn timeline only for a party
 * that said yes (B3), a forget during an overlapping call keeps the caller out of its record (B4), no call is dropped
 * (B5), and the terms-only label follows the recorded answers (nit). Synthetic data only.
 */
class CallRecordPrivacyReviewTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long T0 = 1_794_132_000_000L;
    /** Seven or more digits with or without separators, in any string value of a record. */
    private static final Pattern PHONE_LIKE = Pattern.compile("\\d(?:[\\s\\-().+/]*\\d){6,}");
    private static final List<String> PLANTS = List.of("call me 1234567890", "+91 98765 43210",
            "House 12, Gandhi Road, Nashik 422001", "please tell my wife I will be late tonight",
            "https://example.com/pay");

    private final ConsoleBus bus = new ConsoleBus();
    private final CallRecordStore store = new CallRecordStore("jdbc:sqlite::memory:");
    private final CallRecordWriter writer = new CallRecordWriter(bus, store, JSON,
            Clock.fixed(Instant.ofEpochMilli(T0 + 999_000), ZoneOffset.UTC));

    @AfterEach
    void close() {
        writer.close();
        store.close();
    }

    /** The raw terms a deal update carries, before anything is stored. */
    record Terms(String crop, String market, String quantity, String pickup, String buyerId, String buyerResult) {
        static Terms normal() {
            return new Terms("onion", "Lasalgaon", "10 quintal", "Friday at the farm; payment cash on pickup",
                    "mehta-agro", "Calling Mehta Agro Traders now. Tell the farmer you are calling them.");
        }
    }

    private void call(String id, Terms terms, String farmer, String buyer, String priceResult) {
        CallId c = CallId.of(id);
        LegId f = LegId.of(id + "-f");
        LegId b = LegId.of(id + "-b");
        List<ConsoleEvent> e = new ArrayList<>();
        e.add(new ConsoleEvent.CallStarted(c, T0, "hi-IN"));
        e.add(new ConsoleEvent.LegJoined(c, T0, f, LegRole.FARMER, LegSource.TWILIO, "hi-IN"));
        e.add(new ConsoleEvent.MemoryLoaded(c, T0 + 10, FakeLeg.FARMER_KEY, true,
                List.of(new ProfileFact("name", "Suresh", Provenance.said(CallId.of("old"), T0 - 5000))),
                List.of(new CallGist(CallId.of("old"), T0 - 9000, T0 - 8000, "Old gist.", List.of()))));
        e.add(new ConsoleEvent.TurnHeard(c, T0 + 2000, f, LegRole.FARMER, "hi-IN", "words", T0 + 1000, T0 + 1500));
        e.add(new ConsoleEvent.AssistantSaid(c, T0 + 2600, f, LegRole.FARMER, "hi-IN", "reply"));
        e.add(new ConsoleEvent.ToolCalled(c, T0 + 3000, "get_mandi_price", JSON.createObjectNode(), true, priceResult, 9));
        e.add(new ConsoleEvent.ToolCalled(c, T0 + 4000, "connect_buyer",
                JSON.createObjectNode().put("buyerId", terms.buyerId()), true, terms.buyerResult(), 5));
        e.add(new ConsoleEvent.LegJoined(c, T0 + 5000, b, LegRole.BUYER, LegSource.SIMULATOR, "en-IN"));
        e.add(new ConsoleEvent.TurnHeard(c, T0 + 6000, b, LegRole.BUYER, "en-IN", "buyer words", T0 + 5600, T0 + 5800));
        e.add(new ConsoleEvent.TurnTranslated(c, T0 + 6400, b, LegRole.BUYER, LegRole.FARMER, "en-IN", "hi-IN", "x",
                T0 + 5600));
        e.add(new ConsoleEvent.DealUpdated(c, T0 + 7000, ConsoleEvent.DealState.AGREED, terms.crop(), "Red",
                terms.market(), terms.quantity(), new BigDecimal("2100"), "quintal", terms.pickup(), true, true));
        e.add(new ConsoleEvent.LegEnded(c, T0 + 8000, b, LegRole.BUYER, "hangup"));
        if (farmer != null) e.add(new ConsoleEvent.ConsentRecorded(c, LegRole.FARMER, farmer, T0 + 8500));
        if (buyer != null) e.add(new ConsoleEvent.ConsentRecorded(c, LegRole.BUYER, buyer, T0 + 8500));
        e.add(new ConsoleEvent.LegEnded(c, T0 + 9000, f, LegRole.FARMER, "hangup"));
        e.add(new ConsoleEvent.CallEnded(c, T0 + 9000, 9000, "hangup"));
        e.forEach(bus::publish);
    }

    private static String served(CallRecord r) throws Exception {
        return JSON.writeValueAsString(r) + JSON.writeValueAsString(r.summary());
    }

    /** Every string value anywhere in the record or its list row. */
    private static List<String> strings(CallRecord r) {
        List<String> out = new ArrayList<>();
        collect(JSON.valueToTree(r), out);
        collect(JSON.valueToTree(r.summary()), out);
        return out;
    }

    private static void collect(JsonNode n, List<String> out) {
        if (n.isTextual()) out.add(n.asText());
        n.fieldNames().forEachRemaining(out::add);
        n.forEach(child -> collect(child, out));
    }

    static Stream<Arguments> plantedTerms() {
        List<Arguments> out = new ArrayList<>();
        Terms n = Terms.normal();
        for (String plant : PLANTS) {
            out.add(Arguments.of("crop", plant, new Terms(plant, n.market(), n.quantity(), n.pickup(), n.buyerId(),
                    n.buyerResult())));
            out.add(Arguments.of("market", plant, new Terms(n.crop(), plant, n.quantity(), n.pickup(), n.buyerId(),
                    n.buyerResult())));
            out.add(Arguments.of("quantity", plant, new Terms(n.crop(), n.market(), plant, n.pickup(), n.buyerId(),
                    n.buyerResult())));
            out.add(Arguments.of("pickup", plant, new Terms(n.crop(), n.market(), n.quantity(), plant + "; payment cash",
                    n.buyerId(), n.buyerResult())));
            out.add(Arguments.of("payment", plant, new Terms(n.crop(), n.market(), n.quantity(),
                    "Friday at the farm; payment " + plant, n.buyerId(), n.buyerResult())));
            out.add(Arguments.of("buyer", plant, new Terms(n.crop(), n.market(), n.quantity(), n.pickup(), plant,
                    "Calling " + plant + " now. Tell the farmer you are calling them.")));
        }
        return out.stream();
    }

    /** B1: whatever words a term carries, only closed values reach the record, for a yes call and a no call alike. */
    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("plantedTerms")
    void plantedTextInADealTermNeverReachesTheRecord(String field, String plant, Terms terms) throws Exception {
        call("call-yes", terms, "YES", "YES", "Onion at Lasalgaon: 2,150 rupees per quintal");
        call("call-no", terms, "NO", "NO", "Onion at Lasalgaon: 2,150 rupees per quintal");
        for (String id : List.of("call-yes", "call-no")) {
            CallRecord r = store.get(id).orElseThrow();
            String all = served(r);
            assertThat(all).as(id).doesNotContain(plant);
            for (String word : List.of("1234567890", "98765", "Gandhi", "wife", "example.com", "https")) {
                assertThat(all).as(id).doesNotContain(word);
            }
            for (String s : strings(r)) {
                assertThat(PHONE_LIKE.matcher(s).find()).as(id + " string value: " + s).isFalse();
            }
            JsonNode deal = r.data().get("deal");
            switch (field) {
                case "crop" -> assertThat(deal.path("commodity").asText(null)).isIn("other", null);
                case "market" -> assertThat(deal.path("market").asText(null)).isIn("other", null);
                case "quantity" -> assertThat(deal.get("quantity").isNull()).isTrue();
                case "pickup" -> assertThat(deal.path("pickupKind").asText(null)).isIn("other", null);
                case "payment" -> assertThat(deal.path("paymentKind").asText(null)).isIn("other", null);
                case "buyer" -> {
                    assertThat(deal.has("buyerId")).isFalse();
                    assertThat(r.data().get("buyer").has("match")).isFalse();
                    assertThat(r.summary().buyerName()).isNull();
                }
                default -> throw new AssertionError(field);
            }
        }
    }

    /** B1: ordinary terms become closed values with fixed labels; the buyer comes from the directory by id. */
    @Test
    void ordinaryTermsBecomeClosedValues() throws Exception {
        call("call-1", Terms.normal(), "NO", "NO", "Onion at Lasalgaon: 2,150 rupees per quintal");
        JsonNode deal = store.get("call-1").orElseThrow().data().get("deal");
        assertThat(deal.get("commodity").asText()).isEqualTo("Onion");
        assertThat(deal.get("variety").isNull()).isTrue();
        assertThat(deal.get("market").asText()).isEqualTo("Lasalgaon");
        assertThat(deal.get("quantity").asText()).isEqualTo("10 quintal");
        assertThat(deal.get("quantityValue").decimalValue()).isEqualByComparingTo("10");
        assertThat(deal.get("quantityUnit").asText()).isEqualTo("quintal");
        assertThat(deal.get("price").decimalValue()).isEqualByComparingTo("2100");
        assertThat(deal.get("currency").asText()).isEqualTo("INR");
        assertThat(deal.get("priceBasis").asText()).isEqualTo("per_unit");
        assertThat(deal.get("total").decimalValue()).isEqualByComparingTo("21000");
        assertThat(deal.get("pickupKind").asText()).isEqualTo("at_farm");
        assertThat(deal.get("paymentKind").asText()).isEqualTo("on_delivery");
        assertThat(deal.get("pickup").asText()).isEqualTo("At the farm; payment on delivery");
        assertThat(deal.get("buyerId").asText()).isEqualTo("mehta-agro");
        assertThat(deal.get("buyerName").asText()).isEqualTo("Mehta Agro Traders");
        assertThat(deal.get("label").asText()).isEqualTo("kept without consent: terms only");
        assertThat(JSON.writeValueAsString(deal)).doesNotContain("Friday").doesNotContain("Red");
    }

    /** B2: the mandi tool's result is never stored, even with both parties' yes; only that the lookup ran, and when. */
    @Test
    void theMandiToolResultIsNeverStored() throws Exception {
        String result = "Onion at Lasalgaon: 2,150 rupees per quintal. Call Ramesh on 98765 43210 for a better rate.";
        call("call-1", Terms.normal(), "YES", "YES", result);
        CallRecord r = store.get("call-1").orElseThrow();
        String all = served(r);
        assertThat(all).doesNotContain("2,150").doesNotContain("Ramesh").doesNotContain("98765")
                .doesNotContain("better rate").doesNotContain("\"reference\"").doesNotContain("\"result\"");
        JsonNode price = r.data().get("price");
        assertThat(price.get("atMs").asLong()).isEqualTo(T0 + 3000);
        assertThat(price.get("offsetMs").asLong()).isEqualTo(3000);
        assertThat(price.has("reference")).isFalse();
    }

    /** B3: per-turn timelines only for the party that said yes; medians and counts for every call. */
    @Test
    void perTurnTimelinesOnlyForThePartyThatSaidYes() throws Exception {
        call("call-none", Terms.normal(), "NO", "NONE", "x");
        call("call-farmer", Terms.normal(), "YES", "NO", "x");
        call("call-buyer", Terms.normal(), "NO", "YES", "x");

        CallRecord none = store.get("call-none").orElseThrow();
        assertThat(served(none)).doesNotContain("\"timings\"").doesNotContain("heardMs").doesNotContain("repliedMs");
        JsonNode fl = none.data().get("farmer").get("latency");
        assertThat(fl.get("turns").asInt()).isEqualTo(1);
        assertThat(fl.get("transcript").asLong()).isEqualTo(500);
        assertThat(fl.get("transcriptCount").asInt()).isEqualTo(1);
        assertThat(fl.get("reply").asLong()).isEqualTo(600);
        assertThat(none.data().get("buyer").get("latency").get("translation").asLong()).isEqualTo(400);

        CallRecord farmer = store.get("call-farmer").orElseThrow();
        assertThat(farmer.data().get("farmer").get("timings")).hasSize(1);
        assertThat(farmer.data().get("buyer").has("timings")).isFalse();
        CallRecord buyer = store.get("call-buyer").orElseThrow();
        assertThat(buyer.data().get("farmer").has("timings")).isFalse();
        assertThat(buyer.data().get("buyer").get("timings")).hasSize(1);
    }

    /**
     * Nit: a farmer YES followed by "forget me" keeps the recorded answer (not terms only) while the linked fields are
     * withdrawn.
     */
    @Test
    void aYesFollowedByForgetIsNotTermsOnlyButIsNotKeyed() throws Exception {
        CallId c = CallId.of("call-forget");
        LegId f = LegId.of("call-forget-f");
        bus.publish(new ConsoleEvent.CallStarted(c, T0, "hi-IN"));
        bus.publish(new ConsoleEvent.LegJoined(c, T0, f, LegRole.FARMER, LegSource.TWILIO, "hi-IN"));
        bus.publish(new ConsoleEvent.MemoryLoaded(c, T0 + 10, FakeLeg.FARMER_KEY, true, List.of(), List.of()));
        bus.publish(new ConsoleEvent.ToolCalled(c, T0 + 1000, "forget_me", JSON.createObjectNode(), true, "done", 5));
        bus.publish(new ConsoleEvent.ConsentRecorded(c, LegRole.FARMER, "YES", T0 + 2000));
        bus.publish(new ConsoleEvent.CallEnded(c, T0 + 3000, 3000, "hangup"));
        CallRecord r = store.get("call-forget").orElseThrow();
        assertThat(r.consentFarmer()).isEqualTo(Consent.YES);
        assertThat(r.farmerKey()).isNull();
        JsonNode d = JSON.valueToTree(r).get("data");
        assertThat(d.get("termsOnly").asBoolean()).isFalse();
        assertThat(d.get("consentScope").asText()).isEqualTo("farmer");
        assertThat(d.get("keyed").asBoolean()).isFalse();
        assertThat(d.get("notStored").get("farmer").asText()).isEqualTo("forgotten");
        assertThat(JSON.valueToTree(r.summary()).get("termsOnly").asBoolean()).isFalse();
    }

    /**
     * B4: calls A and B share a caller key and overlap; A's caller says "forget me" while B is still open, then B ends
     * with a yes. B must not bring the key, memory or stated name back.
     */
    @Test
    void aForgetDuringAnOverlappingCallKeepsTheCallerOutOfItsRecord() throws Exception {
        CallId a = CallId.of("call-a");
        CallId b = CallId.of("call-b");
        LegId af = LegId.of("call-a-f");
        LegId bf = LegId.of("call-b-f");
        bus.publish(new ConsoleEvent.CallStarted(b, T0, "hi-IN"));
        bus.publish(new ConsoleEvent.LegJoined(b, T0, bf, LegRole.FARMER, LegSource.TWILIO, "hi-IN"));
        bus.publish(new ConsoleEvent.MemoryLoaded(b, T0 + 10, FakeLeg.FARMER_KEY, true,
                List.of(new ProfileFact("name", "Suresh", Provenance.said(CallId.of("old"), T0 - 5000))), List.of()));
        bus.publish(new ConsoleEvent.ToolCalled(b, T0 + 500, "save_profile_fact",
                JSON.createObjectNode().put("field", "name").put("value", "Rameshwar"), true, "ok", 2));
        bus.publish(new ConsoleEvent.TurnHeard(b, T0 + 2000, bf, LegRole.FARMER, "hi-IN", "w", T0 + 1000, T0 + 1500));
        bus.publish(new ConsoleEvent.CallStarted(a, T0 + 1000, "hi-IN"));
        bus.publish(new ConsoleEvent.LegJoined(a, T0 + 1000, af, LegRole.FARMER, LegSource.TWILIO, "hi-IN"));
        bus.publish(new ConsoleEvent.MemoryLoaded(a, T0 + 1010, FakeLeg.FARMER_KEY, true, List.of(), List.of()));
        bus.publish(new ConsoleEvent.ToolCalled(a, T0 + 3000, "forget_me", JSON.createObjectNode(), true, "done", 5));
        bus.publish(new ConsoleEvent.CallEnded(a, T0 + 4000, 3000, "hangup"));
        bus.publish(new ConsoleEvent.ConsentRecorded(b, LegRole.FARMER, "YES", T0 + 9000));
        bus.publish(new ConsoleEvent.CallEnded(b, T0 + 10_000, 10_000, "hangup"));

        CallRecord rb = store.get("call-b").orElseThrow();
        assertThat(rb.farmerKey()).isNull();
        assertThat(served(rb)).doesNotContain("Suresh").doesNotContain("Rameshwar").doesNotContain("\"timings\"");
        assertThat(rb.data().get("notStored").get("farmer").asText()).isEqualTo("forgotten");
        assertThat(rb.consentFarmer()).isEqualTo(Consent.YES);
        assertThat(store.deleteByFarmerKey(FakeLeg.FARMER_KEY.value())).isZero();
    }

    /** B4: a call that begins after the forget may link again, with its own yes. */
    @Test
    void aCallThatBeginsAfterTheForgetMayLinkAgain() {
        CallId a = CallId.of("call-a");
        bus.publish(new ConsoleEvent.CallStarted(a, T0, "hi-IN"));
        bus.publish(new ConsoleEvent.MemoryLoaded(a, T0 + 10, FakeLeg.FARMER_KEY, true, List.of(), List.of()));
        bus.publish(new ConsoleEvent.ToolCalled(a, T0 + 100, "forget_me", JSON.createObjectNode(), true, "done", 5));
        bus.publish(new ConsoleEvent.CallEnded(a, T0 + 200, 200, "hangup"));
        CallId c = CallId.of("call-later");
        bus.publish(new ConsoleEvent.CallStarted(c, T0 + 60_000, "hi-IN"));
        bus.publish(new ConsoleEvent.MemoryLoaded(c, T0 + 60_010, FakeLeg.FARMER_KEY, false, List.of(), List.of()));
        bus.publish(new ConsoleEvent.ConsentRecorded(c, LegRole.FARMER, "YES", T0 + 61_000));
        bus.publish(new ConsoleEvent.CallEnded(c, T0 + 62_000, 2000, "hangup"));
        assertThat(store.get("call-later").orElseThrow().farmerKey()).isEqualTo(FakeLeg.FARMER_KEY.value());
    }

    /** B5: sixty calls open at once all end up recorded; none is dropped. */
    @Test
    void sixtyConcurrentCallsMakeSixtyRecords() {
        for (int i = 0; i < 60; i++) {
            bus.publish(new ConsoleEvent.CallStarted(CallId.of("call-" + i), T0 + i, "hi-IN"));
        }
        for (int i = 0; i < 60; i++) {
            bus.publish(new ConsoleEvent.CallEnded(CallId.of("call-" + i), T0 + 100_000 + i, 100_000, "hangup"));
        }
        assertThat(store.count()).isEqualTo(60);
        assertThat(store.list(100)).allSatisfy(r -> assertThat(r.endReason()).isEqualTo("hangup"));
        assertThat(writer.openCalls()).isZero();
    }
}
