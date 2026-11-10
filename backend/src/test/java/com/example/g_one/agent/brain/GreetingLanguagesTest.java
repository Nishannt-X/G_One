package com.example.g_one.agent.brain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.AgentBrain.Reply;
import com.example.g_one.agent.language.LanguageLines;
import com.example.g_one.agent.llm.FixedLines;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.Language;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The greeting in every configured language: after the English-first language question it repeats no welcome and is
 * spoken in the chosen language; a call that starts in a fixed language keeps its welcome. The language list comes from
 * the default of {@code g-one.languages} in application.yml, so adding a language without its fixed lines fails here.
 */
class GreetingLanguagesTest {
    private static final Pattern LANGUAGES_DEFAULT = Pattern.compile("languages: \\$\\{G_ONE_LANGUAGES:([^}]+)}");
    /** The old welcome sentence that offered only Hindi or English. */
    private static final List<String> HINDI_OR_ENGLISH = List.of("Speak Hindi or English", "हिंदी या अंग्रेज़ी");
    /** The script each language's lines are written in (Hinglish and Benglish use the native script). */
    private static final Map<String, Pattern> SCRIPT = Map.of(
            "hi", Pattern.compile("\\p{InDevanagari}"),
            "bn", Pattern.compile("\\p{InBengali}"),
            "ta", Pattern.compile("\\p{InTamil}"),
            "kn", Pattern.compile("\\p{InKannada}"),
            "en", Pattern.compile("^[\\p{InBasicLatin}\\p{InGeneralPunctuation}]+$"));

    private BrainFixture f;

    @BeforeEach
    void setUp() throws Exception {
        f = new BrainFixture(false);
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    /** The configured languages (code:label:voice), from application.yml. */
    static List<Language> configured() throws IOException {
        try (InputStream in = GreetingLanguagesTest.class.getResourceAsStream("/application.yml")) {
            String yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = LANGUAGES_DEFAULT.matcher(yml);
            assertThat(m.find()).as("g-one.languages default in application.yml").isTrue();
            return Arrays.stream(m.group(1).split(",")).map(s -> s.trim().split(":"))
                    .map(p -> new Language(p[0], p[1], p[2])).toList();
        }
    }

    private static void inItsScript(String text, Language lang) {
        assertThat(text).as("%s line in its own script", lang.code()).containsPattern(SCRIPT.get(FixedLines.prefix(lang.code())));
    }

    private static void noOldWelcome(String text) {
        for (String s : HINDI_OR_ENGLISH) assertThat(text).doesNotContain(s);
    }

    @Test
    void theConfiguredListIsTheSevenLanguagesTheQuestionOffers() throws IOException {
        assertThat(configured()).extracting(Language::code)
                .containsExactlyInAnyOrder("hi-IN", "en-IN", "hi-en", "bn-IN", "bn-en", "ta-IN", "kn-IN");
    }

    @ParameterizedTest
    @MethodSource("configured")
    void everyConfiguredLanguageHasItsOwnFixedLinesAndWelcomeBack(Language lang) {
        assertThat(f.lines.languages()).as("fixed lines for %s", lang.code()).contains(lang.code());
        FixedLines.Lines l = f.lines.forLanguage(lang.code());
        assertThat(l.code()).isEqualTo(lang.code());
        for (String s : List.of(l.welcome(), l.memoryNotice(), l.withheld(), l.nameCheck(), l.forgotten(), l.goodbye())) {
            assertThat(s).isNotBlank().doesNotContainPattern("[0-9]");
            inItsScript(s, lang);
            noOldWelcome(s);
        }
        assertThat(l.nameCheck("Suresh")).contains("Suresh").endsWith("?");
        assertThat(new WelcomeBack(BrainFixture.JSON).line(lang.code(), f.seedSuresh()))
                .hasValueSatisfying(w -> {
                    assertThat(w.code()).isEqualTo(lang.code());
                    assertThat(w.text()).contains("Suresh").doesNotContainPattern("[0-9]");
                });
        inItsScript(LanguageLines.confirmation(lang.code()), lang);
    }

    @ParameterizedTest
    @MethodSource("configured")
    void afterTheLanguageChoiceANewFarmerHearsOnlyTheMemoryNoticeInThatLanguage(Language lang) {
        FixedLines.Lines l = f.lines.forLanguage(lang.code());
        Reply r = f.brain.greetAfterLanguageChoice(BrainFixture.newFarmer(lang));
        assertThat(r.text()).isEqualTo(l.memoryNotice());
        assertThat(r.text()).doesNotContain(l.welcome()).doesNotContain("Welcome to G-One");
        noOldWelcome(r.text());
        inItsScript(r.text(), lang);
        assertThat(r.language()).isEqualTo(lang);
        assertThat(r.endCall()).isFalse();
        assertThat(f.llm.requests()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("configured")
    void afterTheLanguageChoiceAWithheldCallerHearsOnlyTheWithheldLine(Language lang) {
        FixedLines.Lines l = f.lines.forLanguage(lang.code());
        Reply r = f.brain.greetAfterLanguageChoice(BrainFixture.withheld(lang));
        assertThat(r.text()).isEqualTo(l.withheld());
        assertThat(r.language()).isEqualTo(lang);
    }

    @ParameterizedTest
    @MethodSource("configured")
    void afterTheLanguageChoiceAReturningFarmerHearsTheNameCheckInThatLanguage(Language lang) {
        CallContext ctx = BrainFixture.returning(lang, f.seedSuresh());
        Reply r = f.brain.greetAfterLanguageChoice(ctx);
        assertThat(r.text()).isEqualTo(f.lines.forLanguage(lang.code()).nameCheck("Suresh")).endsWith("?");
        assertThat(r.language()).isEqualTo(lang);
    }

    @ParameterizedTest
    @MethodSource("configured")
    void aCallStartedInAFixedLanguageKeepsAWelcomeThatOffersNoLanguagePair(Language lang) {
        FixedLines.Lines l = f.lines.forLanguage(lang.code());
        Reply r = f.brain.greet(BrainFixture.newFarmer(lang));
        assertThat(r.text()).isEqualTo(l.welcome() + " " + l.memoryNotice());
        noOldWelcome(r.text());
        assertThat(r.language()).isEqualTo(lang);
        assertThat(f.brain.greet(BrainFixture.withheld(lang)).text()).isEqualTo(l.welcome() + " " + l.withheld());
    }

    @Test
    void aCodeWithNoLinesUsesTheSameLanguageFirstThenEnglish() {
        assertThat(f.lines.forLanguage("hi-XX").code()).isEqualTo("hi-IN");
        assertThat(f.lines.forLanguage("ta-LK").code()).isEqualTo("ta-IN");
        assertThat(f.lines.forLanguage("mr-IN").code()).isEqualTo("en-IN");
    }
}
