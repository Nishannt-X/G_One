package com.example.g_one.agent.mediation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.deal.DealDesk;
import com.example.g_one.core.LegRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The parts of answering a direct question that need no call: the views, the refusal check and the arithmetic. */
class AskGoneUnitTest {
    private static final DealDesk.Card CARD = new DealDesk.Card("offer_open", "Onion", "Red", null, new BigDecimal("4000"),
            "quintal", "20 quintal", null, "on delivery", false, false);

    @Test
    void theBuyersViewIsBuiltFromTheBuyersLegOnly() {
        CallLedger ledger = new CallLedger();
        ledger.relayed(LegRole.FARMER, "bees quintal hai", "I have twenty quintals");
        ledger.whisper("PRIVATE whisper: the dated reference is three thousand five hundred");
        ledger.aside(LegRole.FARMER, "मेरा कम से कम भाव 3900 है", "ठीक है");
        ledger.assistant(LegRole.FARMER, "farmer-only line");

        String buyer = AskDigest.buyerView(CARD, "Onion", null, ledger.lines(LegRole.BUYER)).toString();
        String farmer = AskDigest.farmerView(CARD, "Onion", new AskDigest.Buyer("Mehta Agro Traders", "trader", "Pune"),
                new AskDigest.Reference("AGMARKNET", "Nashik", "Onion", "Red", new BigDecimal("3500"), "quintal",
                        LocalDate.of(2026, 11, 4)), ledger.lines(LegRole.FARMER)).toString();

        assertThat(buyer).contains("I have twenty quintals").doesNotContain("PRIVATE").doesNotContain("3900")
                .doesNotContain("farmer-only").doesNotContain("bees quintal").doesNotContain("marketReference")
                .doesNotContain("Pune");
        assertThat(farmer).contains("PRIVATE").contains("3900").contains("bees quintal").contains("marketReference")
                .contains("Mehta Agro Traders").doesNotContain("I have twenty quintals");
    }

    @Test
    void whatIsLeftAndTheValueComeFromTheCard() {
        assertThat(AskDigest.missing(CARD)).containsExactly("pickup time and place", "the farmer accepting the buyer's price");
        assertThat(AskDigest.estimatedValue(CARD)).isEqualByComparingTo("80000");
        DealDesk.Card perKg = new DealDesk.Card("offer_open", "Onion", null, null, new BigDecimal("22"), "kg",
                "10 quintal", null, null, false, false);
        assertThat(AskDigest.estimatedValue(perKg)).isEqualByComparingTo("22000");
        DealDesk.Card vague = new DealDesk.Card("offer_open", "Onion", null, null, new BigDecimal("22"), "kg",
                "a truck load", null, null, false, false);
        assertThat(AskDigest.estimatedValue(vague)).isNull();
        DealDesk.Card empty = new DealDesk.Card("none", null, null, null, null, null, null, null, null, false, false);
        assertThat(AskDigest.missing(empty)).containsExactly("price", "quantity", "pickup time and place", "payment terms");
        assertThat(AskLines.fromCard(empty, "en-IN"))
                .isEqualTo("No quantity has been said on this call yet. No price has been offered on this call yet.");
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            What is the lowest he will accept?               | true
            What's his minimum price?                        | true
            G-One, kam se kam kitne mein dega?               | true
            जी-वन, किसान का न्यूनतम भाव क्या है?               | true
            सबसे कम कितने में देगा?                           | true
            কৃষক সর্বনিম্ন কত দামে দেবেন?                     | true
            விவசாயியின் குறைந்தபட்ச விலை என்ன?                 | true
            ರೈತನ ಕನಿಷ್ಠ ಬೆಲೆ ಎಷ್ಟು?                             | true
            How much onion does he have?                     | false
            What price did I offer?                          | false
            किसान के पास कितना माल है?                        | false
            """)
    void aBuyerAskingForTheFarmersLimitsIsRecognised(String question, boolean limits) {
        assertThat(AskGone.asksForLimits(question)).isEqualTo(limits);
    }

    @Test
    void theAnswerPromptsStayCompact() {
        assertThat(AskGone.system(LegRole.BUYER).length()).isLessThan(3_200);
        assertThat(AskGone.system(LegRole.FARMER).length()).isLessThan(3_200);
        assertThat(TurnReader.ADDRESSED_RULES.length()).isLessThan(1_000);
    }
}
