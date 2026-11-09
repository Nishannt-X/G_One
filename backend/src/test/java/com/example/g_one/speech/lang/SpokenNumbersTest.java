package com.example.g_one.speech.lang;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Digits become words for Timbre in every language we speak, and the routes tell Gnani the right codes. */
class SpokenNumbersTest {

    private static void check(String language, String input, String expected) {
        assertThat(SpokenNumbers.normalize(input, language)).as(input).isEqualTo(expected);
    }

    @Test
    void hindiRupeesKilosAndQuintals() {
        check("hi-IN", "₹2,400", "दो हज़ार चार सौ रुपये");
        check("hi-IN", "2400 रुपये", "दो हज़ार चार सौ रुपये");
        check("hi-IN", "50 किलो", "पचास किलो");
        check("hi-IN", "200 quintal", "दो सौ क्विंटल");
        check("hi-IN", "₹1,800-2,200 प्रति क्विंटल", "एक हज़ार आठ सौ से दो हज़ार दो सौ रुपये प्रति क्विंटल");
        check("hi-IN", "₹2400/quintal", "दो हज़ार चार सौ रुपये प्रति क्विंटल");
        check("hi-IN", "12%", "बारह प्रतिशत");
    }

    @Test
    void hindiFractionsAndIndianGrouping() {
        check("hi-IN", "1.5 क्विंटल", "डेढ़ क्विंटल");
        check("hi-IN", "2.5 क्विंटल", "ढाई क्विंटल");
        check("hi-IN", "3.5 क्विंटल", "साढ़े तीन क्विंटल");
        check("hi-IN", "₹1,50,000", "एक लाख पचास हज़ार रुपये");
        check("hi-IN", "1.5 lakh", "एक लाख पचास हज़ार");
        check("hi-IN", "२४०० रुपये", "दो हज़ार चार सौ रुपये");
    }

    @Test
    void english() {
        check("en-IN", "Rs 2400/quintal", "two thousand four hundred rupees per quintal");
        check("en-IN", "25 kg", "twenty five kilos");
        check("en-IN", "₹12.50", "twelve rupees fifty paise");
        check("en-IN", "₹1,25,000", "one lakh twenty five thousand rupees");
    }

    @Test
    void hinglishFollowsTheScriptOfTheText() {
        check("hi-en", "2400 rupaye per quintal", "do hazaar chaar sau rupaye per quintal");
        check("hi-en", "2.5 quintal", "dhaai quintal");
        check("hi-en", "गेहूं 50 किलो", "गेहूं पचास किलो");
    }

    @Test
    void bengaliAndBenglish() {
        check("bn-IN", "২৪০০ টাকা", "দুই হাজার চারশো টাকা");
        check("bn-IN", "50 কেজি", "পঞ্চাশ কেজি");
        check("bn-IN", "১.৫ কুইন্টাল", "দেড় কুইন্টাল");
        check("bn-en", "₹300 per kg", "তিনশো টাকা প্রতি কেজি");
        check("bn-en", "amar 20 quintal dhan", "amar কুড়ি কুইন্টাল dhan");
    }

    @Test
    void identifiersAndOtherTextAreLeftAlone() {
        check("en-IN", "no digits here", "no digits here");
        check("en-IN", "grade B2B lot", "grade B2B lot");
        check("hi-IN", "9876543210", "नौ आठ सात छह पाँच चार तीन दो एक शून्य");
        check("en-IN", "007", "zero zero seven");
        check("ta-IN", "50 kg", "50 kg");
    }

    @Test
    void textAlreadyInWordsDoesNotChange() {
        String once = SpokenNumbers.normalize("₹2,400 प्रति क्विंटल", "hi-IN");
        assertThat(SpokenNumbers.normalize(once, "hi-IN")).isEqualTo(once);
    }

    @Test
    void routesGiveGnaniCodesItKnows() {
        assertThat(LanguageRoute.of("bn-IN", true)).isEqualTo(new LanguageRoute("bn-IN,en-IN", "bn-IN", "bn-IN", "transcribe"));
        assertThat(LanguageRoute.of("bn-en", true)).isEqualTo(new LanguageRoute("bn-IN,en-IN", "bn-IN", "bn-IN", "codemix"));
        assertThat(LanguageRoute.of("hi-en", true)).isEqualTo(new LanguageRoute("hi-IN,en-IN", "hi-IN", "hi-en", "codemix"));
        assertThat(LanguageRoute.of("bn-en", false)).isEqualTo(new LanguageRoute("bn-IN", "bn-IN", "bn-IN", ""));
        assertThat(LanguageRoute.of("hi-en", false)).isEqualTo(new LanguageRoute("hi-IN", "hi-IN", "hi-en", ""));
        assertThat(LanguageRoute.of("ta-IN", true).sttSingle()).isEqualTo("ta-IN");
    }
}
