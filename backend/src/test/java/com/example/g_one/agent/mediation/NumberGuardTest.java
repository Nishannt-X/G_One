package com.example.g_one.agent.mediation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NumberGuardTest {
    private static boolean ok(String src, String from, String tr, String to) {
        return NumberGuard.check(src, from, tr, to).ok();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            eighteen hundred rupees per quintal | en-IN | 1800
            one thousand eight hundred rupees | en-IN | 1800
            forty quintal | en-IN | 40
            two hundred and fifty | en-IN | 250
            two lakh fifty thousand | en-IN | 250000
            one and a half lakh | en-IN | 150000
            1,800 | en-IN | 1800
            ₹1,50,000 | en-IN | 150000
            अठारह सौ रुपये | hi-IN | 1800
            एक हज़ार आठ सौ | hi-IN | 1800
            डेढ़ हज़ार | hi-IN | 1500
            ढाई सौ | hi-IN | 250
            साढ़े तीन हज़ार | hi-IN | 3500
            १८०० रुपये | hi-IN | 1800
            আঠারোশো টাকা | bn-IN | 1800
            এক হাজার আটশো | bn-IN | 1800
            পঞ্চাশটি | bn-IN | 50
            দেড় হাজার | bn-IN | 1500
            ১৮০০ টাকা | bn-IN | 1800
            ಹದಿನೆಂಟು ನೂರು ರೂಪಾಯಿ | kn-IN | 1800
            ಒಂದು ಸಾವಿರದ ಎಂಟು ನೂರು | kn-IN | 1800
            ಸಾವಿರದ ಎಂಟುನೂರು | kn-IN | 1800
            ಎರಡು ಸಾವಿರದ ನಾಲ್ಕುನೂರು | kn-IN | 2400
            ಐವತ್ತು ಕ್ವಿಂಟಾಲ್ | kn-IN | 50
            ಐವತ್ತಕ್ಕೆ | kn-IN | 50
            ಇಪ್ಪತ್ತೈದು | kn-IN | 25
            ಎರಡು ಸಾವಿರದ ಐನೂರು | kn-IN | 2500
            ೧೮೦೦ | kn-IN | 1800
            ஆயிரத்து எண்ணூறு ரூபாய் | ta-IN | 1800
            ஐம்பது குவிண்டால் | ta-IN | 50
            இருபத்தைந்து | ta-IN | 25
            atharah sau rupaye | hi-en | 1800
            pachas quintal | hi-en | 50
            atharo sho taka | bn-en | 1800
            ponchash quintal | bn-en | 50
            """)
    void readsTheNumber(String text, String code, String expected) {
        assertThat(NumberGuard.numbers(text, code)).contains(expected);
    }

    @Test
    void anEighteenHundredThatBecomesFifteenHundredFails() {
        var check = NumberGuard.check("eighteen hundred rupees per quintal", "en-IN", "ಹದಿನೈದು ನೂರು ರೂಪಾಯಿ ಪ್ರತಿ ಕ್ವಿಂಟಾಲ್‌ಗೆ", "kn-IN");
        assertThat(check.ok()).isFalse();
        assertThat(check.missing()).contains("1800");
        assertThat(check.invented()).contains("1500");
    }

    @Test
    void passesWhenWordsAndDigitsAgreeAcrossLanguages() {
        assertThat(ok("I will take forty quintal at eighteen hundred rupees", "en-IN",
                "ನಾನು ನಲವತ್ತು ಕ್ವಿಂಟಾಲ್ ಅನ್ನು ಹದಿನೆಂಟು ನೂರು ರೂಪಾಯಿಗೆ ತೆಗೆದುಕೊಳ್ಳುತ್ತೇನೆ", "kn-IN")).isTrue();
        assertThat(ok("अठारह सौ रुपये क्विंटल", "hi-IN", "1800 rupees per quintal", "en-IN")).isTrue();
        assertThat(ok("৪০ কুইন্টাল", "bn-IN", "forty quintals", "en-IN")).isTrue();
    }

    @Test
    void aDroppedOrInventedNumberFails() {
        assertThat(ok("pay 500 now", "en-IN", "ಈಗ ಪಾವತಿಸಿ", "kn-IN")).isFalse();
        assertThat(ok("pay now", "en-IN", "ಈಗ ಐನೂರು ಪಾವತಿಸಿ", "kn-IN")).isFalse();
    }

    @Test
    void smallWordsThatAreNotNumbersDoNotFail() {
        assertThat(ok("Give me the money", "en-IN", "मुझे पैसे दो", "hi-IN")).isTrue();
        assertThat(ok("It is not good", "en-IN", "এটা ভালো নয়", "bn-IN")).isTrue();
        assertThat(ok("I saw a truck", "en-IN", "मैंने एक ट्रक देखा", "hi-IN")).isTrue();
        assertThat(ok("come at seven", "en-IN", "7 बजे आइए", "hi-IN")).isTrue();
    }

    @Test
    void aSmallNumberBeforeAUnitCounts() {
        assertThat(ok("five quintal", "en-IN", "সাত কুইন্টাল", "bn-IN")).isFalse();
        assertThat(ok("five quintal", "en-IN", "পাঁচ কুইন্টাল", "bn-IN")).isTrue();
    }

    @Test
    void noNumbersAtAllPasses() {
        assertThat(ok("Pay on delivery", "en-IN", "ಡೆಲಿವರಿ ನಂತರ ಪಾವತಿಸಿ", "kn-IN")).isTrue();
    }

    @Test
    void digitizedRewritesStrongNumbersAndLeavesTheRest() {
        assertThat(NumberGuard.digitized("আমি পঞ্চাশ কুইন্টাল পেঁয়াজ আঠারোশো টাকায় নেব", "bn-IN")).isEqualTo("আমি 50 কুইন্টাল পেঁয়াজ 1800 টাকায় নেব");
        assertThat(NumberGuard.digitized("Give me a truck and two lakh fifty thousand now", "en-IN")).isEqualTo("Give me a truck and 250000 now");
        assertThat(NumberGuard.digitized("do paise", "hi-IN")).isEqualTo("do paise");
        assertThat(NumberGuard.digitized("atharah sau rupaye quintal", "hi-en")).isEqualTo("1800 rupaye quintal");
    }

    @Test
    void aSpokenYearIsOneNumber() {
        assertThat(NumberGuard.numbers("on the fourth of November, twenty twenty-six", "en-IN")).contains("2026").doesNotContain("20");
    }

    @Test
    void aPriceTheTurnSaidIsKeptAndAMisreadOneIsReplacedByTheTurnsOnlyFigure() {
        String turn = "আমি পঞ্চাশ কুইন্টাল পেঁয়াজ আঠারোশো টাকা কুইন্টালে নেব";
        assertThat(NumberGuard.trustedPrice(new java.math.BigDecimal("1800"), turn, "bn-IN", java.util.List.of())).isEqualByComparingTo("1800");
        assertThat(NumberGuard.trustedPrice(new java.math.BigDecimal("180"), turn, "bn-IN", java.util.List.of())).isEqualByComparingTo("1800");
        assertThat(NumberGuard.trustedPrice(new java.math.BigDecimal("2000"), "দু হাজার দুশো টাকা কুইন্টাল", "bn-IN", java.util.List.of())).isEqualByComparingTo("2200");
    }

    @Test
    void aPriceFromAnEarlierLineIsAcceptedAndAnAmbiguousMisreadIsDropped() {
        assertThat(NumberGuard.trustedPrice(new java.math.BigDecimal("2500"), "theek hai", "hi-en", java.util.List.of("do hazaar paanch sau chahiye"), "hi-en"))
                .isEqualByComparingTo("2500");
        assertThat(NumberGuard.trustedPrice(new java.math.BigDecimal("999"), "eighteen hundred or nineteen hundred", "en-IN", java.util.List.of())).isNull();
    }

    @Test
    void multiplierSuffixesAreReadAsTheWholeAmount() {
        assertThat(NumberGuard.numbers("I offer 2k", "en-IN")).contains("2000").doesNotContain("2");
        assertThat(NumberGuard.numbers("I offer 2K only", "en-IN")).contains("2000");
        assertThat(NumberGuard.numbers("2.5k per quintal", "en-IN")).contains("2500");
        assertThat(NumberGuard.numbers("1.5 lakh for all", "en-IN")).contains("150000");
        assertThat(NumberGuard.numbers("1.5L for all", "en-IN")).contains("150000");
        assertThat(NumberGuard.numbers("2cr total", "en-IN")).contains("20000000");
        assertThat(NumberGuard.numbers("2 hazaar per kg", "en-IN")).contains("2000");
        assertThat(NumberGuard.digitized("main 2k dunga", "hi-IN")).isEqualTo("main 2000 dunga");
    }

    @Test
    void anUnreadableSuffixNeverYieldsAPartialPrice() {
        assertThat(NumberGuard.trustedPrice(new java.math.BigDecimal("2000000"), "I offer 2m", "en-IN", java.util.List.of())).isNull();
        assertThat(NumberGuard.trustedPrice(new java.math.BigDecimal("2000"), "I offer 2x", "en-IN", java.util.List.of())).isNull();
        assertThat(NumberGuard.trustedPrice(new java.math.BigDecimal("2000"), "I offer 2k", "en-IN", java.util.List.of()))
                .isEqualByComparingTo("2000");
        assertThat(NumberGuard.trustedPrice(new java.math.BigDecimal("1800"), "10kg at 1800rs", "en-IN", java.util.List.of()))
                .isEqualByComparingTo("1800");
        assertThat(NumberGuard.trustedPrice(new java.math.BigDecimal("180"), "10kg at 1800rs", "en-IN", java.util.List.of()))
                .isEqualByComparingTo("1800");
    }
}
