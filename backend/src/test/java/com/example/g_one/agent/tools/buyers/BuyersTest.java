package com.example.g_one.agent.tools.buyers;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.deal.ReplyGuard;
import com.example.g_one.agent.tools.mandi.MandiCatalog;
import com.example.g_one.agent.tools.weather.DistrictTable;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallId;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.ToolResult;
import com.example.g_one.fakes.FakeLeg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BuyersTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BuyerDirectory directory = new BuyerDirectory(JSON);
    private final BuyerRanker ranker = new BuyerRanker(directory, new MandiCatalog(JSON), new DistrictTable(JSON));
    private final BuyerConsent consent = new BuyerConsent();
    private final FindBuyersTool find = new FindBuyersTool(ranker, consent);
    private final List<BuyerConnector.ConnectRequest> connected = new ArrayList<>();
    private final ConnectBuyerTool connect =
            new ConnectBuyerTool(directory, consent, () -> connected::add);
    private final CallId call = CallId.random();
    private final CallContext ctx = new CallContext(call, FakeLeg.farmer(call), FakeLeg.HINDI,
            FarmerMemory.empty(FakeLeg.FARMER_KEY));

    private static JsonNode args(String json) throws Exception {
        return JSON.readTree(json);
    }

    private ToolResult findOnions() throws Exception {
        return find.invoke(args("{\"commodity\":\"pyaz\",\"quantity\":20,\"district\":\"Nashik\"}"), ctx);
    }

    private ToolResult connectMehta() throws Exception {
        return connect.invoke(args("{\"buyerId\":\"mehta-agro\"}"), ctx);
    }

    @Test
    void directoryHasTheSpecifiedShapeAndOnlyEnvKeysForPhones() {
        assertThat(directory.all()).hasSizeBetween(6, 10);
        assertThat(directory.all()).allSatisfy(b -> {
            assertThat(b.phoneRef()).matches("[A-Z_]+");
            assertThat(b.name()).doesNotContainPattern("\\d{5,}");
        });
    }

    @Test
    void rankingUsesCropAndIsDeterministic() {
        List<BuyerRanker.Match> first = ranker.rank("Onion", 20.0, "Nashik");
        assertThat(first).extracting(m -> m.buyer().id()).doesNotContain("karnal-grain", "malwa-soya");
        assertThat(first).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
        assertThat(ranker.rank("Onion", 20.0, "Nashik")).isEqualTo(first);
    }

    @Test
    void aliasesReachTheSameCrop() {
        assertThat(ranker.rank("प्याज", null, null)).extracting(m -> m.buyer().id())
                .containsExactlyElementsOf(ranker.rank("onion", null, null).stream().map(m -> m.buyer().id()).toList());
    }

    @Test
    void aBetterPricedCloserAndMoreReliableBuyerRanksFirst() {
        assertThat(ranker.rank("Onion", 20.0, "Nashik").get(0).buyer().id()).isEqualTo("mehta-agro");
    }

    @Test
    void quantityOutsideTheBuyersRangeLowersTheirRank() {
        double fits = score("sahyadri-kirana", 5.0);
        double tooBig = score("sahyadri-kirana", 200.0);
        assertThat(fits).isGreaterThan(tooBig);
    }

    private double score(String id, Double quantity) {
        return ranker.rank("Onion", quantity, "Nashik").stream().filter(m -> m.buyer().id().equals(id)).findFirst()
                .orElseThrow().score();
    }

    @Test
    void unknownCropIsAnHonestEmptyAnswer() throws Exception {
        ToolResult r = find.invoke(args("{\"commodity\":\"saffron\"}"), ctx);
        assertThat(r.ok()).isTrue();
        assertThat(r.content()).contains("no buyer");
        assertThat(consent.suggested(call)).isEmpty();
    }

    @Test
    void toolReturnsTopThreeWithReasonsAndNoPhoneNumbers() throws Exception {
        ToolResult r = findOnions();
        assertThat(r.ok()).isTrue();
        JsonNode out = JSON.readTree(r.content());
        assertThat(out.path("buyers")).hasSize(3);
        assertThat(out.path("buyers").get(0).path("reason").asText()).contains("Buys onion").contains("rated");
        assertThat(r.content()).doesNotContain("phoneRef", "MEHTA_AGRO", "SHINDE").doesNotContainPattern("\\+?\\d{7,}");
        assertThat(r.content()).doesNotContainPattern("\\d");
    }

    @Test
    void missingCommodityIsAnError() throws Exception {
        assertThat(find.invoke(args("{}"), ctx).ok()).isFalse();
    }

    @Test
    void connectIsRefusedWithoutASpokenYes() throws Exception {
        consent.heard(call, "pyaz bechna hai");
        findOnions();
        consent.heard(call, "pata nahi, sochta hoon");

        ToolResult r = connectMehta();

        assertThat(r.ok()).isFalse();
        assertThat(r.content()).contains("clear yes");
        assertThat(connected).isEmpty();
    }

    @Test
    void connectIsRefusedForAYesOnTheSameTurnAsTheSuggestion() throws Exception {
        consent.heard(call, "haan");
        findOnions();

        assertThat(connectMehta().ok()).isFalse();
        assertThat(connected).isEmpty();
    }

    @Test
    void connectIsRefusedForABuyerNeverSuggested() throws Exception {
        consent.heard(call, "pyaz bechna hai");
        findOnions();
        consent.heard(call, "haan");

        assertThat(connect.invoke(args("{\"buyerId\":\"karnal-grain\"}"), ctx).ok()).isFalse();
        assertThat(connected).isEmpty();
    }

    @Test
    void anExplicitYesConnectsOnceAndCarriesTheRequest() throws Exception {
        consent.heard(call, "pyaz bechna hai");
        findOnions();
        consent.heard(call, "haan, phone laga do");

        assertThat(connectMehta().ok()).isTrue();
        assertThat(connected).singleElement().satisfies(c -> {
            assertThat(c.buyer().id()).isEqualTo("mehta-agro");
            assertThat(c.commodity()).isEqualTo("pyaz");
            assertThat(c.quantity()).isEqualTo("20 quintal");
            assertThat(c.district()).isEqualTo("Nashik");
        });

        assertThat(connectMehta().ok()).as("one yes dials once").isFalse();
        assertThat(connected).hasSize(1);
    }

    @Test
    void aConnectorFailureBecomesAnError() throws Exception {
        ConnectBuyerTool failing = new ConnectBuyerTool(directory, consent, () -> r -> {
            throw new IllegalStateException("no line");
        });
        consent.heard(call, "pyaz bechna hai");
        findOnions();
        consent.heard(call, "haan");

        assertThat(failing.invoke(args("{\"buyerId\":\"mehta-agro\"}"), ctx).ok()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "Haan", "हाँ", "ji haan theek hai", "ঠিক আছে", "okay done", "pakka"})
    void theseAreAYes(String s) {
        assertThat(ReplyGuard.read(s)).isEqualTo(ReplyGuard.Reading.YES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"no", "नहीं", "nahi", "na", "না"})
    void theseAreANo(String s) {
        assertThat(ReplyGuard.read(s)).isEqualTo(ReplyGuard.Reading.NO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes but only if you pay first", "haan agar paisa mile", "ठीक है लेकिन", "maybe", "theek nahi hai",
            "yes?", "", "okay so the price is what you said it would be", "kya bola aapne"})
    void theseAreUnclear(String s) {
        assertThat(ReplyGuard.read(s)).isEqualTo(ReplyGuard.Reading.UNCLEAR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"haan laga do", "unse baat karao", "Mehta ko phone lagao", "फ़ोन लगा दीजिए"})
    void theseAreConsent(String s) {
        assertThat(ReplyGuard.isConsent(s)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mat lagao", "phone laga do agar rate achha ho", "nahi", "pata nahi"})
    void theseAreNotConsent(String s) {
        assertThat(ReplyGuard.isConsent(s)).isFalse();
    }

    @Test
    void aSecondLookupOnTheYesTurnDoesNotCancelTheEarlierSuggestion() {
        BuyerConsent gate = new BuyerConsent();
        com.example.g_one.core.CallId id = new com.example.g_one.core.CallId("call-repeat-lookup");
        gate.heard(id, "pyaaz bechna hai");
        gate.suggested(id, java.util.List.of("mehta-agro"), "Onion", null, null);
        gate.heard(id, "haan, phone lagao");
        gate.suggested(id, java.util.List.of("mehta-agro"), "Onion", null, null);
        org.assertj.core.api.Assertions.assertThat(gate.allowed(id, "mehta-agro")).isTrue();
    }
}
