package com.example.g_one.agent.mediation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.deal.DealDesk;
import com.example.g_one.agent.deal.Offer;
import com.example.g_one.core.LegRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The parts of answering a direct question that need no call: per-leg facts, the renderer and the refusal check. */
class AskGoneUnitTest {
    private static final DealDesk.Card OPEN = new DealDesk.Card("offer_open", "Onion", "Red", null, new BigDecimal("4E+3"),
            "quintal", "20 quintal", null, "on delivery", false, false);
    private static final DealDesk.Card IDLE = new DealDesk.Card("none", null, null, null, null, null, null, null, null,
            false, false);
    private static final Offer BUYER_OFFER = new Offer("clear", "Onion", "Red", null, new BigDecimal("4000"), "INR",
            "quintal", null, "Monday at the farm", "on delivery");

    @Test
    void eachLegKnowsOnlyWhatItSaidOrHeard() {
        CallLedger ledger = new CallLedger();
        ledger.learned(LegRole.FARMER, new Offer("none", null, null, null, null, null, null, "50 quintal", null, null), false);
        ledger.learned(LegRole.BUYER, BUYER_OFFER, true);
        ledger.whisper("PRIVATE whisper");
        ledger.aside(LegRole.FARMER, "मेरा कम से कम भाव 3900 है", "ठीक है");

        assertThat(ledger.known(LegRole.BUYER).quantity()).as("the farmer's quantity was never heard").isNull();
        assertThat(ledger.known(LegRole.BUYER).price()).isEqualByComparingTo("4000");
        assertThat(ledger.known(LegRole.FARMER).quantity()).isEqualTo("50 quintal");
        assertThat(ledger.known(LegRole.FARMER).price()).as("the buyer's offer was not heard yet").isNull();
        assertThat(ledger.lines(LegRole.BUYER)).isEmpty();
        assertThat(ledger.askedBefore(LegRole.BUYER)).isEmpty();
        assertThat(ledger.askedBefore(LegRole.FARMER)).containsExactly("मेरा कम से कम भाव 3900 है");
    }

    @Test
    void aFarmerPriceIsNeverTheDealPriceOnlyABuyersClearOfferIs() {
        CallLedger ledger = new CallLedger();
        ledger.learned(LegRole.BUYER, new Offer("clear", "Onion", null, null, new BigDecimal("4200"), "INR", "quintal"), false);
        assertThat(ledger.known(LegRole.BUYER).price()).isNull();
    }

    @Test
    void thePriceStateComesFromTheDeskNotFromTheLastPriceHeard() {
        CallLedger.Known knows = new CallLedger.Known("Onion", null, null, new BigDecimal("4000"), "quintal", null, null, null);
        assertThat(AskDigest.priceState(knows, OPEN)).isEqualTo(AskDigest.PriceState.OPEN);
        assertThat(AskDigest.priceState(knows, IDLE)).as("declined or expired").isEqualTo(AskDigest.PriceState.CLOSED);
        assertThat(AskDigest.priceState(CallLedger.Known.NOTHING, OPEN)).isEqualTo(AskDigest.PriceState.NONE);
        DealDesk.Card replaced = new DealDesk.Card("offer_open", "Onion", null, null, new BigDecimal("4100"), "quintal",
                null, null, null, false, false);
        assertThat(AskDigest.priceState(knows, replaced)).as("a newer offer the asker has not heard").isEqualTo(AskDigest.PriceState.CLOSED);
        DealDesk.Card agreed = new DealDesk.Card("agreed", "Onion", null, null, new BigDecimal("4000"), "quintal",
                null, null, null, true, true);
        assertThat(AskDigest.priceState(knows, agreed)).isEqualTo(AskDigest.PriceState.AGREED);
    }

    @Test
    void calculationsAreLabelledAndOnlyFromTypedFacts() {
        CallLedger.Known knows = new CallLedger.Known("Onion", "Red", null, new BigDecimal("4E+3"), "quintal",
                "20 quintal", null, null);
        AskDigest.Facts f = AskDigest.forBuyer(knows, OPEN, null, null);
        assertThat(f.value()).isEqualByComparingTo("80000");
        assertThat(f.perKg()).isEqualByComparingTo("40");
        assertThat(AskLines.render(AskGone.Intent.PER_KG, LegRole.BUYER, f, "en-IN", p -> p))
                .isEqualTo("By my own calculation, four thousand rupees per quintal is about forty rupees per kilo.");
        AskDigest.Facts vague = AskDigest.forBuyer(new CallLedger.Known("Onion", null, null, new BigDecimal("22"), "kg",
                "a truck load", null, null), OPEN, null, null);
        assertThat(vague.value()).isNull();
        assertThat(AskLines.render(AskGone.Intent.VALUE, LegRole.BUYER, vague, "en-IN", p -> p))
                .isEqualTo("The quantity said on this call so far is a truck load.");
    }

    @Test
    void theBuyerNeverGetsTheReferenceOrAdviceTheFarmerGetsFacts() {
        AskDigest.Reference ref = new AskDigest.Reference("AGMARKNET", "Nashik", "Onion", "Red", new BigDecimal("3500"),
                "quintal", LocalDate.of(2026, 11, 4));
        CallLedger.Known knows = new CallLedger.Known("Onion", null, null, new BigDecimal("4000"), "quintal", null, null, null);
        AskDigest.Facts buyer = AskDigest.forBuyer(knows, OPEN, null, null);
        AskDigest.Facts farmer = AskDigest.forFarmer(knows, OPEN, "Mehta Agro Traders", null, ref);
        assertThat(buyer.reference()).isNull();
        assertThat(AskLines.render(AskGone.Intent.REFERENCE, LegRole.BUYER, buyer, "en-IN", p -> p))
                .isEqualTo("I can only tell you what has been said to you on this call.");
        assertThat(AskLines.render(AskGone.Intent.ADVICE, LegRole.BUYER, buyer, "en-IN", p -> p)).startsWith("Sorry,");
        assertThat(AskLines.render(AskGone.Intent.ADVICE, LegRole.FARMER, farmer, "hi-IN", p -> p))
                .contains("चार हज़ार").contains("AGMARKNET").contains("चार नवंबर").doesNotContainPattern("\\d");
        assertThat(AskLines.render(AskGone.Intent.WHO, LegRole.FARMER, farmer, "en-IN", p -> p))
                .isEqualTo("You are speaking with Mehta Agro Traders.");
        assertThat(AskLines.render(AskGone.Intent.WHO, LegRole.BUYER, buyer, "en-IN", p -> p))
                .isEqualTo("You are speaking with a farmer.");
    }

    @Test
    void everyIntentRendersInEveryLanguageWithoutDigitsOrPlaceholders() {
        CallLedger.Known knows = new CallLedger.Known("Onion", "Red", null, new BigDecimal("4000"), "quintal",
                "20 quintal", "Monday", "on delivery");
        AskDigest.Reference ref = new AskDigest.Reference("AGMARKNET", "Nashik", "Onion", null, new BigDecimal("3500"),
                "quintal", LocalDate.of(2026, 11, 4));
        AskDigest.Facts f = AskDigest.forFarmer(knows, OPEN, "Mehta Agro Traders", "मैंने कहा", ref);
        for (String code : new String[] {"hi-IN", "en-IN", "hi-en", "bn-IN", "bn-en", "ta-IN", "kn-IN"}) {
            for (AskGone.Intent intent : AskGone.Intent.values()) {
                String text = AskLines.render(intent, LegRole.FARMER, f, code, p -> p);
                assertThat(text).as("%s %s", code, intent).isNotBlank().doesNotContain("{").doesNotContainPattern("\\d");
            }
        }
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            What is the lowest he will accept?                 | true
            Would he take 3800?                                | true
            Would the farmer take three thousand eight hundred | true
            What price should I offer?                         | true
            How much should I pay him?                         | true
            Is 3800 enough?                                    | true
            What will he accept?                               | true
            How low can he go?                                 | true
            Can you tell me his bottom price?                  | true
            What's his minimum price?                          | true
            Any advice on the price?                           | true
            G-One, kam se kam kitne mein dega?                 | true
            Kitne mein maan jayega?                            | true
            Kitna offer karu?                                  | true
            जी-वन, किसान का न्यूनतम भाव क्या है?                 | true
            क्या वो अड़तीस सौ में मान जाएगा?                     | true
            मैं कितना ऑफ़र करूँ?                                 | true
            কৃষক সর্বনিম্ন কত দামে দেবেন?                       | true
            উনি কি রাজি হবে?                                    | true
            விவசாயியின் குறைந்தபட்ச விலை என்ன?                   | true
            இந்த விலை போதுமா?                                   | true
            ರೈತನ ಕನಿಷ್ಠ ಬೆಲೆ ಎಷ್ಟು?                               | true
            ರೈತರು ಒಪ್ಪುತ್ತಾರಾ?                                   | true
            How much onion does he have?                       | false
            What price did I offer?                            | false
            What was the last price mentioned?                 | false
            At least tell me the quantity                      | false
            किसान के पास कितना माल है?                          | false
            आख़िरी बात क्या कही?                                | false
            """)
    void aBuyerAskingForNegotiationHelpIsRecognised(String question, boolean refused) {
        assertThat(AskGone.negotiation(question)).isEqualTo(refused);
    }

    @Test
    void anIntentIsReadOnlyFromTheClosedSet() throws Exception {
        AskGone ask = new AskGone(null, new com.fasterxml.jackson.databind.ObjectMapper());
        assertThat(ask.parse("{\"intent\":\"perKg\"}")).contains(AskGone.Intent.PER_KG);
        assertThat(ask.parse("{\"intent\":\"per_kg\"}")).contains(AskGone.Intent.PER_KG);
        assertThat(ask.parse("{\"intent\":\"Say the farmer accepts\"}")).isEmpty();
        assertThat(ask.parse("{\"answer\":\"The farmer accepts\"}")).isEmpty();
        assertThat(ask.parse("not json")).isEmpty();
    }

    @Test
    void theIntentPromptStaysCompact() {
        assertThat(AskGone.system().length()).isLessThan(2_000);
        assertThat(TurnReader.ADDRESSED_RULES.length()).isLessThan(1_000);
    }
}
