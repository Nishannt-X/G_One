package com.example.g_one.agent.language;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.language.LanguageDetector.Detection;
import com.example.g_one.agent.language.LanguageDetector.Source;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LanguageTrackerTest {
    private static final Detection H = new Detection("hi-IN", Source.SCRIPT, true);
    private static final Detection E = new Detection("en-IN", Source.LATIN, true);
    private static final Detection B = new Detection("bn-IN", Source.SCRIPT, true);
    private static final Detection T = new Detection("ta-IN", Source.SCRIPT, true);
    private static final Detection K = new Detection("kn-IN", Source.SCRIPT, true);

    /** The switches a run of turns causes, as "code:reason". */
    private static List<String> run(LanguageTracker tracker, Detection... turns) {
        List<String> out = new ArrayList<>();
        for (Detection d : turns) tracker.observe(d).ifPresent(s -> out.add(s.code() + ":" + s.reason()));
        return out;
    }

    private static LanguageTracker seeded(String current, Detection first) {
        LanguageTracker t = new LanguageTracker(current);
        t.seed(first);
        return t;
    }

    @Test
    void oneTurnInAnotherLanguageChangesNothing() {
        assertThat(run(new LanguageTracker("hi-IN"), E, H, H, H, H, E, H)).isEmpty();
    }

    @Test
    void aBorrowedWordChangesNothing() {
        LanguageTracker t = new LanguageTracker("hi-IN");
        for (int i = 0; i < 6; i++) {
            assertThat(t.observe(LanguageDetector.detect("मुझे tractor चाहिए भाई"))).isEmpty();
        }
    }

    @Test
    void aRunFromANamedStartIsAPlainSwitch() {
        assertThat(run(new LanguageTracker("en-IN"), H, H)).containsExactly("hi-IN:run");
    }

    @Test
    void aRunAwayFromASpokenLanguageBecomesTheMix() {
        assertThat(run(seeded("en-IN", E), H, H)).containsExactly("hi-en:mix");
        assertThat(run(seeded("en-IN", E), B, B)).containsExactly("bn-en:mix");
    }

    @Test
    void alternatingWithEnglishBecomesTheMix() {
        assertThat(run(seeded("hi-IN", H), E, H, E)).containsExactly("hi-en:mix");
        assertThat(run(seeded("bn-IN", B), E, B, E)).containsExactly("bn-en:mix");
        assertThat(run(seeded("en-IN", E), H, E, H)).containsExactly("hi-en:mix");
    }

    @Test
    void oneCodeMixedTurnIsEnoughFromEitherOfItsLanguages() {
        assertThat(run(new LanguageTracker("hi-IN"), LanguageDetector.detect("मेरा phone number दे दो please"))).containsExactly("hi-en:mix");
        assertThat(run(new LanguageTracker("en-IN"), LanguageDetector.detect("मेरा phone number दे दो please"))).containsExactly("hi-en:mix");
    }

    @Test
    void romanisedHindiNeedsARun() {
        Detection roman = LanguageDetector.detect("kya bhav hai aaj");
        assertThat(run(new LanguageTracker("en-IN"), roman)).isEmpty();
        assertThat(run(new LanguageTracker("en-IN"), roman, roman)).containsExactly("hi-en:run");
    }

    @Test
    void aMixStaysThroughAOneOffTurnOfEitherLanguage() {
        LanguageTracker t = new LanguageTracker("hi-en");
        assertThat(run(t, E, H, E, H, H, H, E)).isEmpty();
    }

    @Test
    void onlyALongClearRunLeavesAMix() {
        assertThat(run(new LanguageTracker("hi-en"), H, H, H)).isEmpty();
        assertThat(run(new LanguageTracker("hi-en"), H, H, H, H)).containsExactly("hi-IN:run");
        assertThat(run(new LanguageTracker("hi-en"), E, E, E, E)).containsExactly("en-IN:run");
        assertThat(run(new LanguageTracker("bn-en"), B, B, B)).isEmpty();
        assertThat(run(new LanguageTracker("bn-en"), B, B, B, B)).containsExactly("bn-IN:run");
    }

    @Test
    void turnsBeforeTheMixDoNotCountTowardLeavingIt() {
        assertThat(run(seeded("en-IN", E), H, H, H, H, H)).containsExactly("hi-en:mix");
        assertThat(run(seeded("en-IN", E), H, H, H, H, H, H)).containsExactly("hi-en:mix", "hi-IN:run");
    }

    @Test
    void aLanguageWithNoMixedCodeNeverGetsOne() {
        assertThat(run(seeded("en-IN", E), T, T)).containsExactly("ta-IN:run");
        assertThat(run(seeded("kn-IN", K), E, K, E, K)).isEmpty();
        assertThat(run(seeded("en-IN", E), K, E, K, E)).isEmpty();
    }

    @Test
    void unknownAndVeryShortTurnsAreIgnored() {
        LanguageTracker t = new LanguageTracker("hi-IN");
        Detection shortEnglish = new Detection("en-IN", Source.LATIN, false);
        assertThat(run(t, E, Detection.UNKNOWN, shortEnglish, LanguageDetector.detect("haan"), LanguageDetector.detect("1500"))).isEmpty();
        assertThat(run(t, E)).as("noise does not break a run").containsExactly("en-IN:run");
    }

    @Test
    void namingALanguageTakesEffectAtOnceEvenInAMix() {
        assertThat(run(new LanguageTracker("hi-en"), LanguageDetector.detect("English please"))).containsExactly("en-IN:named");
    }

    @Test
    void theMixTableHasOnlyTheTwoCodesThatExist() {
        assertThat(LanguageTracker.MIXES).extracting(LanguageTracker.Mix::mixed).containsExactly("hi-en", "bn-en");
    }
}
