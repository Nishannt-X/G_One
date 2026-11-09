package com.example.g_one.agent.language;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.language.LanguageFlow.Kind;
import com.example.g_one.agent.language.LanguageFlow.Outcome;
import com.example.g_one.agent.language.LanguageFlow.State;
import com.example.g_one.core.Language;
import com.example.g_one.core.LegRole;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LanguageFlowTest {
    private static final List<String> OFFERED = List.of("hi-IN", "en-IN", "hi-en", "bn-IN", "bn-en", "ta-IN", "kn-IN");

    private final LanguageFlow flow = new LanguageFlow(c -> OFFERED.contains(c) ? Optional.of(new Language(c, c, "v")) : Optional.empty());

    @Test
    void pendingThenAskedThenChosenThenFollowed() {
        assertThat(flow.state()).isEqualTo(State.PENDING);
        flow.asked();
        assertThat(flow.state()).isEqualTo(State.ASKED);

        Outcome chosen = flow.onTurn("हिंदी", false);
        assertThat(chosen.kind()).isEqualTo(Kind.CHOSEN);
        assertThat(chosen.language().code()).isEqualTo("hi-IN");
        assertThat(chosen.reason()).isEqualTo("named");
        assertThat(chosen.hasContent()).isFalse();
        assertThat(flow.state()).isEqualTo(State.DECIDED);

        assertThat(flow.onTurn("What is the price of onion", false).kind()).isEqualTo(Kind.KEEP);
        Outcome switched = flow.onTurn("Please tell me the weather today", false);
        assertThat(switched.kind()).isEqualTo(Kind.SWITCHED);
        assertThat(switched.language().code()).isEqualTo("en-IN");
    }

    @Test
    void aSentenceChoosesItsLanguageAndIsStillAnswered() {
        flow.asked();
        Outcome chosen = flow.onTurn("ನನಗೆ ಈರುಳ್ಳಿ ಬೆಲೆ ಬೇಕು", false);
        assertThat(chosen.language().code()).isEqualTo("kn-IN");
        assertThat(chosen.reason()).isEqualTo("detected");
        assertThat(chosen.hasContent()).isTrue();
    }

    @Test
    void askedOnceMoreThenSettlesOnHinglish() {
        flow.asked();
        assertThat(flow.onTurn("haan", false).kind()).isEqualTo(Kind.REASK);
        assertThat(flow.state()).isEqualTo(State.REASKED);
        Outcome fallback = flow.onTurn("1500", false);
        assertThat(fallback.kind()).isEqualTo(Kind.CHOSEN);
        assertThat(fallback.language().code()).isEqualTo("hi-en");
        assertThat(fallback.reason()).isEqualTo("default");
        assertThat(fallback.hasContent()).isFalse();
    }

    @Test
    void aSecondAnswerCanStillChoose() {
        flow.asked();
        flow.onTurn("hmm", false);
        assertThat(flow.onTurn("Tamil", false).language().code()).isEqualTo("ta-IN");
    }

    @Test
    void nothingUsefulWhileGOneIsSpeakingIsNotHeldAgainstTheCaller() {
        flow.asked();
        assertThat(flow.onTurn("haan", true).kind()).isEqualTo(Kind.IGNORED);
        assertThat(flow.state()).isEqualTo(State.ASKED);
    }

    @Test
    void aTurnBeforeTheQuestionIsIgnored() {
        assertThat(flow.onTurn("haan", false).kind()).isEqualTo(Kind.IGNORED);
    }

    @Test
    void aLanguageThatIsNotOfferedIsNotChosen() {
        LanguageFlow only = new LanguageFlow(c -> c.equals("en-IN") ? Optional.of(new Language(c, c, "v")) : Optional.empty());
        only.asked();
        assertThat(only.onTurn("Tamil", false).kind()).isEqualTo(Kind.REASK);
        assertThat(only.onTurn("Tamil", false).language().code()).as("no Hinglish offered, so English").isEqualTo("en-IN");
    }

    @Test
    void theLinesAreEnglishFirstAndCoverEveryOfferedLanguage() {
        assertThat(LanguageLines.opener(LegRole.FARMER)).startsWith("Hello, this is G-One.").contains("Which language");
        assertThat(LanguageLines.opener(LegRole.BUYER)).contains("connecting you to a farmer");
        for (String code : OFFERED) {
            assertThat(LanguageLines.confirmation(code)).as(code).isNotBlank();
            if (!code.equals("en-IN")) assertThat(LanguageLines.confirmation(code)).as(code).doesNotContain("English");
        }
    }
}
