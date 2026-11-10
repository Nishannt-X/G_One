package com.example.g_one.agent.language;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.language.LanguageDetector.Detection;
import com.example.g_one.agent.language.LanguageDetector.Source;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Synthetic sentences in every offered language. */
class LanguageDetectorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Hindi                         | hi-IN
            हिंदी                           | hi-IN
            हिन्दी में बात करेंगे            | hi-IN
            I want to speak in Hindi      | hi-IN
            Hindi mein baat karo          | hi-IN
            English please                | en-IN
            English                       | en-IN
            अंग्रेज़ी                         | en-IN
            अंग्रेजी में बोलिए                | en-IN
            Hinglish                      | hi-en
            Bangla                        | bn-IN
            Bengali please                | bn-IN
            বাংলা                           | bn-IN
            আমি বাংলা বলতে চাই              | bn-IN
            Benglish                      | bn-en
            Tamil                         | ta-IN
            தமிழ்                         | ta-IN
            Kannada                       | kn-IN
            I speak Kannada               | kn-IN
            ಕನ್ನಡ                          | kn-IN
            """)
    void aNamedLanguageWins(String text, String code) {
        Detection d = LanguageDetector.detect(text);
        assertThat(d.code()).isEqualTo(code);
        assertThat(d.source()).isEqualTo(Source.NAMED);
        assertThat(d.hasContent()).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Hindi                    | false
            हिंदी                     | false
            English please           | false
            Hinglish                 | false
            हिंदी में बात करेंगे जी       | false
            hello Hindi              | false
            Hindi bhai               | false
            Hindi wheat price        | true
            हिंदी, गेहूँ का भाव         | true
            Hindi 20 quintal         | true
            English, what is the rate | true
            """)
    void onlyALanguageNameAndRequestWordsSayNothingMore(String text, boolean more) {
        assertThat(LanguageDetector.saysMoreThanALanguage(text)).isEqualTo(more);
    }

    @org.junit.jupiter.api.Test
    void aShortRequestBesideTheLanguageNameStillChoosesItAndCountsAsContent() {
        Detection d = LanguageDetector.detect("Hindi wheat price");
        assertThat(d.code()).isEqualTo("hi-IN");
        assertThat(d.source()).isEqualTo(Source.NAMED);
        assertThat(d.hasContent()).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            मुझे प्याज़ का भाव बताइए             | hi-IN
            আমি পেঁয়াজ বিক্রি করতে চাই           | bn-IN
            எனக்கு வெங்காயம் விலை சொல்லுங்கள்  | ta-IN
            ನನಗೆ ಈರುಳ್ಳಿ ಬೆಲೆ ಬೇಕು               | kn-IN
            """)
    void theScriptDecidesASentence(String text, String code) {
        Detection d = LanguageDetector.detect(text);
        assertThat(d.code()).isEqualTo(code);
        assertThat(d.source()).isEqualTo(Source.SCRIPT);
        assertThat(d.hasContent()).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            मेरा phone number दे दो please     | hi-en
            আমার phone number দিয়ে দিন please  | bn-en
            """)
    void latinWordsInStrengthMakeAMix(String text, String code) {
        Detection d = LanguageDetector.detect(text);
        assertThat(d.code()).isEqualTo(code);
        assertThat(d.source()).isEqualTo(Source.MIXED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"मुझे tractor चाहिए भाई", "आज mandi का भाव क्या है"})
    void oneBorrowedWordIsNotAMix(String text) {
        assertThat(LanguageDetector.detect(text).code()).isEqualTo("hi-IN");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            What is the price of onion today | en-IN | LATIN
            please tell me                   | en-IN | LATIN
            kya bhav hai aaj                 | hi-en | ROMANISED
            ami dhan bikri korbo             | bn-en | ROMANISED
            """)
    void latinTextIsEnglishUnlessClearlyRomanised(String text, String code, Source source) {
        Detection d = LanguageDetector.detect(text);
        assertThat(d.code()).isEqualTo(code);
        assertThat(d.source()).isEqualTo(source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "haan", "हाँ", "ठीक है", "okay", "hmm", "1500", "20 40", "hello", "Namaste", "tractor",
            "Hindi or English"})
    void aTurnThatSaysNothingAboutLanguageIsUnknown(String text) {
        Detection d = LanguageDetector.detect(text);
        if (!text.equals("Hindi or English")) assertThat(d.unknown()).as(text).isTrue();
        else assertThat(d.source()).as("two names are no choice").isNotEqualTo(Source.NAMED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"मुझे हिंदी स्कूल में पढ़े बच्चों की फीस भरनी है", "I went to the Bangla market to sell my onions"})
    void aLanguageNameInsideARealSentenceIsNotARequest(String text) {
        assertThat(LanguageDetector.detect(text).source()).isNotEqualTo(Source.NAMED);
    }
}
