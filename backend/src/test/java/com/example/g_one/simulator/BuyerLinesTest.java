package com.example.g_one.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.fakes.FakeLeg;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The buyer's screen is fed from this filter, so what it refuses never leaves the server. */
class BuyerLinesTest {
    private static final CallId CALL = CallId.of("call-1");
    private static final FakeLeg BUYER = FakeLeg.buyer(CALL);
    private static final LegId FARMER = LegId.of("leg-farmer");

    private static Optional<BuyerLines.Line> line(ConsoleEvent event) {
        return BuyerLines.forBuyer(event, BUYER).stream().findFirst();
    }

    @Test
    void theBuyersOwnQuestionToGOneAndItsAnswerPassButTheFarmersNeverDo() {
        assertThat(BuyerLines.forBuyer(new ConsoleEvent.AsideAnswered(CALL, 1, BUYER.id(), LegRole.BUYER, "en-IN",
                "G-One, how much?", "No quantity has been said on this call yet.", 0), BUYER)).containsExactly(
                new BuyerLines.Line("you_said", "en-IN", "G-One, how much?"),
                new BuyerLines.Line("g_one", "en-IN", "No quantity has been said on this call yet."));
        assertThat(BuyerLines.forBuyer(new ConsoleEvent.AsideAnswered(CALL, 1, FARMER, LegRole.FARMER, "hi-IN",
                "जी-वन, मेरा कम से कम भाव 3900 है", "ठीक है", 0), BUYER)).isEmpty();
    }

    @Test
    void theBuyersOwnWordsAndTheirTranslationPass() {
        assertThat(line(new ConsoleEvent.TurnHeard(CALL, 1, BUYER.id(), LegRole.BUYER, "en-IN", "I can pay", 0, 1)))
                .contains(new BuyerLines.Line("you_said", "en-IN", "I can pay"));
        assertThat(line(new ConsoleEvent.TurnTranslated(CALL, 1, BUYER.id(), LegRole.BUYER, LegRole.FARMER, "en-IN", "hi-IN", "दे सकता हूँ", 0)))
                .contains(new BuyerLines.Line("they_hear", "hi-IN", "दे सकता हूँ"));
    }

    @Test
    void whatTheBuyerIsToldPasses() {
        assertThat(line(new ConsoleEvent.TurnTranslated(CALL, 1, FARMER, LegRole.FARMER, LegRole.BUYER, "hi-IN", "en-IN", "I want more", 0)))
                .contains(new BuyerLines.Line("you_hear", "en-IN", "I want more"));
        assertThat(line(new ConsoleEvent.AssistantSaid(CALL, 1, BUYER.id(), LegRole.BUYER, "en-IN", "Do you agree?")))
                .contains(new BuyerLines.Line("g_one", "en-IN", "Do you agree?"));
    }

    @Test
    void everythingThatIsTheFarmersOrTheOperatorsStaysBack() {
        List<ConsoleEvent> privateEvents = List.of(
                new ConsoleEvent.WhisperSent(CALL, 1, FARMER, ConsoleEvent.WhisperKind.CAUTION, "hi-IN", "Do not share the OTP"),
                new ConsoleEvent.WhisperSent(CALL, 1, BUYER.id(), ConsoleEvent.WhisperKind.TIP, "hi-IN", "misrouted"),
                new ConsoleEvent.AssistantSaid(CALL, 1, FARMER, LegRole.FARMER, "hi-IN", "Your earlier price"),
                new ConsoleEvent.TurnHeard(CALL, 1, FARMER, LegRole.FARMER, "hi-IN", "My lowest is 4000", 0, 1),
                new ConsoleEvent.ToolCalled(CALL, 1, "mandi_price", null, true, "reference 4300", 5),
                new ConsoleEvent.MemoryLoaded(CALL, 1, FakeLeg.FARMER_KEY, true, List.of(), List.of()),
                new ConsoleEvent.DealUpdated(CALL, 1, ConsoleEvent.DealState.DRAFT, "onion", null, null, "20", null, "quintal", null, false, false),
                new ConsoleEvent.TurnMeasured(CALL, 1, "model", "m", 1, 1, 1, 1, ""));
        assertThat(privateEvents).allSatisfy(event -> assertThat(line(event)).isEmpty());
    }

    @Test
    void linesFromAnotherCallDoNotPass() {
        CallId other = CallId.of("call-2");
        assertThat(line(new ConsoleEvent.TurnTranslated(other, 1, FARMER, LegRole.FARMER, LegRole.BUYER, "hi-IN", "en-IN", "another call", 0)))
                .isEmpty();
    }
}
