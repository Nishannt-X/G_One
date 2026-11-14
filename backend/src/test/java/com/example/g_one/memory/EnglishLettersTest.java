package com.example.g_one.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Names in English letters, from the scripts the farmers' languages use. All names are invented examples. */
class EnglishLettersTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        // Devanagari: the silent last and middle "a" are dropped, clusters and nasals read as said.
        "रमेश पाटिल, Ramesh Patil",
        "रामेश्वर पाटिल, Rameshwar Patil",
        "कमला देवी, Kamla Devi",
        "गुरप्रीत सिंह, Gurprit Singh",
        "राजेन्द्र, Rajendra",
        "अमरनाथ, Amarnath",
        "लक्ष्मी, Lakshmi",
        "चंपा, Champa",
        "हरि, Hari",
        // Bengali
        "মিতা দাস, Mita Das",
        "সুমন রায়, Suman Ray",
        "মৃণাল, Mrinal",
        // Tamil: no silent vowel; pulli ends a word
        "செல்வி, Selvi",
        "சரவணன், Saravanan",
        "முருகன், Murukan",
        // Kannada
        "ರಮೇಶ್, Ramesh",
        "ಲಕ್ಷ್ಮಿ, Lakshmi",
        "ಸುಮಾ, Suma",
    })
    void nativeScriptNamesComeOutInEnglishLetters(String native_, String english) {
        assertThat(EnglishLetters.name(native_)).contains(english);
        assertThat(EnglishLetters.hasNonLatinLetters(english)).isFalse();
    }

    @Test
    void mixedAndOddInputIsCleanedNotStoredAsIs() {
        assertThat(EnglishLetters.name("Ramesh पाटिल")).contains("Ramesh Patil");
        assertThat(EnglishLetters.name("  Asha  ")).contains("Asha");
        assertThat(EnglishLetters.name("Ramēsh")).contains("Ramesh");
        assertThat(EnglishLetters.name("राज123")).contains("Raj");
        assertThat(EnglishLetters.name("रमेश‍पाटिल")).hasValueSatisfying(n -> assertThat(n).matches("[A-Za-z ]+"));
        assertThat(EnglishLetters.name("O'Neil-Rao.")).contains("O'Neil-Rao");
    }

    @ParameterizedTest
    @ValueSource(strings = {"王伟", "محمد", "123", "  ", "🙂", "-"})
    void nothingUsableLeftIsEmpty(String text) {
        assertThat(EnglishLetters.name(text)).isEmpty();
    }

    @Test
    void detectsLettersOutsideLatin() {
        assertThat(EnglishLetters.hasNonLatinLetters("Ramesh")).isFalse();
        assertThat(EnglishLetters.hasNonLatinLetters("40 quintal")).isFalse();
        assertThat(EnglishLetters.hasNonLatinLetters("रमेश")).isTrue();
        assertThat(EnglishLetters.hasNonLatinLetters(null)).isFalse();
    }
}
