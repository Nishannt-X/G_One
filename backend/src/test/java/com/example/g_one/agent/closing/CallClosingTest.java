package com.example.g_one.agent.closing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.closing.CallClosing.Ask;
import com.example.g_one.agent.closing.CallClosing.Consent;
import com.example.g_one.agent.closing.CallClosing.Question;
import com.example.g_one.agent.closing.CallClosing.Step;
import com.example.g_one.agent.llm.FixedLines;
import com.example.g_one.core.LegRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class CallClosingTest {
    private static final LegRole F = LegRole.FARMER;
    private static final LegRole B = LegRole.BUYER;

    private static CallClosing both() {
        CallClosing c = new CallClosing(false);
        c.joined(B);
        return c;
    }

    /** Both said yes to ending, so both hear the save question. */
    private static CallClosing atSave(CallClosing c) {
        c.start();
        c.heard(F, "हाँ");
        Step s = c.heard(B, "yes");
        assertThat(s.asks()).extracting(Ask::role, Ask::question)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(F, Question.SAVE), org.assertj.core.groups.Tuple.tuple(B, Question.SAVE));
        return c;
    }

    @Test
    void startAsksEveryPresentPartyToEndTheCall() {
        Step s = both().start();
        assertThat(s.asks()).extracting(Ask::role, Ask::question, Ask::again)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(F, Question.END, false),
                        org.assertj.core.groups.Tuple.tuple(B, Question.END, false));
    }

    @Test
    void aRepeatAsksAgainOnAFreshTokenWithoutUsingUpTheReask() {
        CallClosing c = both();
        long first = c.start().asks().get(1).token();
        Ask again = c.repeat(B).orElseThrow();
        assertThat(again.question()).isEqualTo(Question.END);
        assertThat(again.again()).isFalse();
        assertThat(c.timeout(B, first).asks()).as("the earlier timer is stale").isEmpty();
        assertThat(c.heard(B, "hmm").asks()).as("the one re-ask is still there").singleElement()
                .satisfies(a -> assertThat(a.again()).isTrue());
        assertThat(c.repeat(B)).isPresent();
        c.heard(B, "yes");
        assertThat(c.repeat(B)).as("answered: nothing to repeat").isEmpty();
        assertThat(both().repeat(B)).as("no check running").isEmpty();
    }

    @Test
    void oneYesWaitsForTheOther() {
        CallClosing c = both();
        c.start();
        Step s = c.heard(F, "yes");
        assertThat(s.asks()).isEmpty();
        assertThat(s.finished()).isFalse();
        assertThat(c.active()).isTrue();
    }

    @Test
    void oneNoResumesAndThatLegIsNotAskedForThreeTurns() {
        CallClosing c = both();
        c.start();
        c.heard(F, "yes");
        Step s = c.heard(B, "no");
        assertThat(s.resumed()).isTrue();
        assertThat(c.active()).isFalse();
        for (int i = 0; i < CallClosing.COOLDOWN_TURNS; i++) {
            assertThat(c.mayAsk(B)).isFalse();
            c.turnTaken(B);
        }
        assertThat(c.mayAsk(B)).isTrue();
    }

    @Test
    void unclearOrSilentIsAskedOnceMoreThenTreatedAsNotReady() {
        CallClosing c = new CallClosing(false);
        long token = c.start().asks().get(0).token();
        Step again = c.timeout(F, token);
        assertThat(again.asks()).singleElement().satisfies(a -> assertThat(a.again()).isTrue());
        assertThat(c.timeout(F, token).asks()).as("a stale timer does nothing").isEmpty();
        Step after = c.heard(F, "what?");
        assertThat(after.resumed()).isTrue();
    }

    static Stream<Arguments> matrix() {
        return Stream.of(
                Arguments.of("हाँ", "yes", Consent.YES, Consent.YES),
                Arguments.of("yes", "no", Consent.YES, Consent.NO),
                Arguments.of("नहीं", "no", Consent.NO, Consent.NO),
                Arguments.of("no", "yes", Consent.NO, Consent.YES));
    }

    @ParameterizedTest
    @MethodSource("matrix")
    void theSaveAnswerIsRecordedPerParty(String farmer, String buyer, Consent f, Consent b) {
        CallClosing c = atSave(both());
        assertThat(c.heard(F, farmer).finished()).isFalse();
        Step done = c.heard(B, buyer);
        assertThat(done.finished()).isTrue();
        assertThat(done.consent()).isEqualTo(Map.of(F, f, B, b));
        assertThat(c.finished()).isTrue();
    }

    @Test
    void silenceOnTheSaveQuestionAfterOneReaskIsNone() {
        CallClosing c = new CallClosing(false);
        c.start();
        long token = c.heard(F, "yes").asks().get(0).token();
        long again = c.timeout(F, token).asks().get(0).token();
        Step done = c.timeout(F, again);
        assertThat(done.consent()).containsEntry(F, Consent.NONE).containsEntry(B, Consent.NONE);
    }

    @Test
    void aPartyWhoLeftIsNoneAndTheOtherIsStillAsked() {
        CallClosing c = both();
        c.start();
        c.heard(F, "yes");
        Step s = c.left(B);
        assertThat(s.asks()).singleElement().satisfies(a -> assertThat(a.question()).isEqualTo(Question.SAVE));
        Step done = c.heard(F, "yes");
        assertThat(done.consent()).isEqualTo(Map.of(F, Consent.YES, B, Consent.NONE));
    }

    @Test
    void hangingUpBeforeAnsweringTheSaveQuestionIsNone() {
        CallClosing c = atSave(both());
        c.heard(B, "yes");
        assertThat(c.left(F).consent()).isEqualTo(Map.of(F, Consent.NONE, B, Consent.YES));
    }

    @Test
    void aWithheldFarmerIsNeverAskedToBeRemembered() {
        CallClosing c = new CallClosing(true);
        c.joined(B);
        c.start();
        c.heard(F, "yes");
        Step s = c.heard(B, "yes");
        assertThat(s.asks()).singleElement().satisfies(a -> assertThat(a.role()).isEqualTo(B));
        assertThat(c.heard(B, "yes").consent()).isEqualTo(Map.of(F, Consent.NONE, B, Consent.YES));

        CallClosing alone = new CallClosing(true);
        alone.start();
        assertThat(alone.heard(F, "yes").consent()).isEqualTo(Map.of(F, Consent.NONE, B, Consent.NONE));
    }

    @Test
    void nothingAnsweredIsNoneForEveryone() {
        assertThat(both().consent()).isEqualTo(Map.of(F, Consent.NONE, B, Consent.NONE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bye", "ok bye", "that's all", "nothing else, thank you", "thank you, bye", "बस इतना ही",
            "धन्यवाद, अलविदा", "और कुछ नहीं", "bas itna hi", "আর কিছু না", "বিদায়", "போதும்", "வேறு எதுவும் இல்லை", "ಸಾಕು",
            "ಬೇರೆ ಏನೂ ಇಲ್ಲ"})
    void shortClosingTurnsStartTheCheck(String text) {
        assertThat(CallClosing.saysDone(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"four thousand three hundred per quintal", "pyaaz ka bhav batao", "हाँ", "what is the price",
            "okay bye but first tell me the price of onion at the nashik mandi today please"})
    void negotiationTurnsDoNot(String text) {
        assertThat(CallClosing.saysDone(text)).isFalse();
    }

    @Test
    void everyConfiguredLanguageHasEveryClosingLine() throws Exception {
        FixedLines fixed = new FixedLines(new ObjectMapper());
        for (String code : fixed.languages()) {
            assertThat(ClosingLines.key(code)).as(code).isEqualTo(code);
            for (String k : ClosingLines.KEYS) assertThat(ClosingLines.line(k, code)).as(code + " " + k).isNotBlank();
            assertThat(ClosingLines.ask(Question.SAVE, code)).as("never claims a recording").doesNotContainIgnoringCase("record");
        }
        assertThat(fixed.languages()).contains("hi-IN", "en-IN", "hi-en", "bn-IN", "bn-en", "ta-IN", "kn-IN");
        assertThat(ClosingLines.ask(Question.END, "en-IN")).isEqualTo("Would you like to end the call? Please say yes or no.");
    }
}
