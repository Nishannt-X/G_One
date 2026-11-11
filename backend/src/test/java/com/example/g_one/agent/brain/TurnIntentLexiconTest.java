package com.example.g_one.agent.brain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.closing.CallClosing;
import com.example.g_one.agent.language.LanguageDetector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The widened word lists: goodbyes, "forget me", "connect me", denials of identity, names, and language names. */
class TurnIntentLexiconTest {

    @ParameterizedTest
    @ValueSource(strings = {"बस", "बस जी", "कुछ नहीं", "kuch nahi", "nothing", "बस हो गया", "फिर बात करेंगे", "chalo bye",
            "that will be all", "talk later", "পরে কথা হবে", "আজ এই পর্যন্ত", "பிறகு பேசலாம்", "ಮತ್ತೆ ಮಾತಾಡೋಣ", "ಇಷ್ಟೇ",
            "ठीक है बस", "अच्छा चलता हूँ", "i have to go", "bas thank you"})
    void moreWaysToSayThatIsAll(String text) {
        assertThat(CallClosing.saysDone(text)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"बस इतना बताइए कि भुट्टे का भाव क्या है और खरीदार कहाँ मिलेगा", "कुछ नहीं समझ आया भाई क्या बोला आपने फिर से",
            "nothing is selling at the mandi these days what should i do", "बसंत", "मुझे भुट्टा बेचना है"})
    void requestsAreNotGoodbyes(String text) {
        assertThat(CallClosing.saysDone(text)).as(text).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"मुझे भूल जाओ", "याद मत रखो मुझे", "मेरा डेटा हटा दो", "forget about me", "delete my data",
            "erase my details", "don't remember me", "yaad mat rakhna", "আমাকে ভুলে যান", "মনে রাখবেন না"})
    void forgetMe(String text) {
        assertThat(TurnIntent.of(text)).as(text).contains(TurnIntent.Kind.FORGET);
    }

    @ParameterizedTest
    @ValueSource(strings = {"आप मुझे किसी से कनेक्ट कीजिए", "किसी खरीदार से बात कराइए", "connect me to a buyer", "baat karao kisi se",
            "मिला दीजिए खरीदार से", "put me through to the trader", "ক্রেতার সঙ্গে জুড়ে দিন"})
    void connectMe(String text) {
        assertThat(TurnIntent.wantsConnection(text)).as(text).isTrue();
        assertThat(ToolDiet.relevant(TurnIntent.of(text), false, false,
                com.example.g_one.agent.tools.memory.MemoryGate.State.NEW, false)).contains(ToolDiet.FIND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"मैं वो नहीं हूँ", "नहीं मैं उनका बेटा हूँ", "main woh nahi hoon", "I am someone else", "wrong number",
            "गलत नंबर लगा है", "कोई और बोल रहा है", "আমি উনি নই", "ভুল নম্বর"})
    void denials(String text) {
        assertThat(TurnIntent.deniesIdentity(text)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"मुझे भुट्टा बेचना है", "हाँ", "भाव क्या है", "I am not sure", "मैं ठीक हूँ"})
    void notDenials(String text) {
        assertThat(TurnIntent.deniesIdentity(text)).as(text).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            मेरा नाम अनिकेत है और मैं भुटा भेजना चाहता हूँ। | अनिकेत
            नहीं, मैं राम हूँ                              | राम
            मैं अनिकेत हूँ                                 | अनिकेत
            main Aniket hoon                              | Aniket
            I am Aniket                                   | Aniket
            this is Ramesh speaking                       | Ramesh
            my name is Sita Devi                          | Sita Devi
            আমার নাম রহিম                                 | রহিম
            """)
    void namesInASentence(String text, String name) {
        assertThat(TurnIntent.introducedName(text)).as(text).contains(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"मैं किसान हूँ", "मैं ठीक हूँ", "I am fine", "I am from Nashik", "मैं भुट्टा बेचना चाहता हूँ",
            "main bechna chahta hoon", "मैं वो नहीं हूँ"})
    void notNames(String text) {
        assertThat(TurnIntent.introducedName(text)).as(text).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            বাংলায়           | bn-IN
            தமிழில்          | ta-IN
            ಕನ್ನಡದಲ್ಲಿ        | kn-IN
            Hindustani      | hi-IN
            ইংরেজিতে          | en-IN
            """)
    void inflectedLanguageNames(String text, String code) {
        assertThat(LanguageDetector.detect(text).code()).as(text).isEqualTo(code);
    }
}
