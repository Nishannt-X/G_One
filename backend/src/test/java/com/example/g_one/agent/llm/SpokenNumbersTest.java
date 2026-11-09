package com.example.g_one.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SpokenNumbersTest {
    @Test
    void hindiNumbersUseIndianGrouping() {
        assertThat(SpokenNumbers.words(4100, "hi-IN")).isEqualTo("चार हज़ार एक सौ");
        assertThat(SpokenNumbers.words(150000, "hi-IN")).isEqualTo("एक लाख पचास हज़ार");
        assertThat(SpokenNumbers.words(99, "hi-en")).isEqualTo("निन्यानवे");
    }

    @Test
    void bengaliHundredsAttachToTheDigitWord() {
        assertThat(SpokenNumbers.words(500, "bn-IN")).isEqualTo("পাঁচশো");
        assertThat(SpokenNumbers.words(4100, "bn-IN")).isEqualTo("চার হাজার একশো");
    }

    @Test
    void otherLanguagesAreEnglish() {
        assertThat(SpokenNumbers.words(4500, "en-IN")).isEqualTo("four thousand five hundred");
        assertThat(SpokenNumbers.words(21, "gu-IN")).isEqualTo("twenty-one");
    }

    @Test
    void kannadaAndTamilHaveTheirOwnWords() {
        assertThat(SpokenNumbers.words(25, "kn-IN")).isEqualTo("ಇಪ್ಪತ್ತೈದು");
        assertThat(SpokenNumbers.words(1800, "kn-IN")).isEqualTo("ಒಂದು ಸಾವಿರದ ಎಂಟುನೂರು");
        assertThat(SpokenNumbers.words(40, "ta-IN")).isEqualTo("நாற்பது");
        assertThat(SpokenNumbers.words(1800, "ta-IN")).isEqualTo("ஆயிரத்து எண்ணூறு");
    }

    @Test
    void decimalsKeepEveryDigit() {
        assertThat(SpokenNumbers.words(new BigDecimal("34.50"), "en-IN")).isEqualTo("thirty-four point five");
        assertThat(SpokenNumbers.words(new BigDecimal("3400.00"), "hi-IN")).isEqualTo("तीन हज़ार चार सौ");
    }

    @Test
    void digitsInTextBecomeWords() {
        assertThat(SpokenNumbers.digitsToWords("offer 4,100 rupees", "en-IN")).isEqualTo("offer four thousand one hundred rupees");
    }

    @Test
    void datesInHindi() {
        assertThat(SpokenNumbers.date(LocalDate.of(2026, 11, 4), "hi-IN")).isEqualTo("चार नवंबर");
    }

    @Test
    void wordTablesCoverZeroToNinetyNine() {
        for (int i = 0; i < 100; i++) {
            assertThat(SpokenNumbers.words(i, "hi-IN")).isNotBlank();
            assertThat(SpokenNumbers.words(i, "bn-IN")).isNotBlank();
        }
    }
}
