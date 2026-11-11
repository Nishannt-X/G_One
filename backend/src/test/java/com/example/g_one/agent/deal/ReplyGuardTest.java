package com.example.g_one.agent.deal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.deal.ReplyGuard.Reading;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The yes/no lexicon, table driven, in English, Hindi, Hinglish, Bengali, Tamil and Kannada. Includes every reply quoted
 * from the owner's test call ("रख सकते हैं" was not understood) and the idioms that carry a negation but mean yes.
 */
class ReplyGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            // From the live call and the owner's list.
            "रख सकते हैं", "हाँ", "ज़रूर", "zarur", "haan koi baat nahi", "हाँ कोई बात नहीं", "rakh lo", "रख लो", "chalega",
            "kar do", "theek hai rakh sakte ho", "sure why not", "go ahead", "no problem", "कोई बात नहीं",
            // English.
            "yes", "Yes please", "yeah sure", "okay", "ok", "of course", "absolutely", "definitely", "sounds good",
            "that's fine", "alright", "all right", "please do", "do it", "why not?", "I don't mind", "no worries",
            "yes, go ahead", "correct", "sure, no problem at all", "yep", "fine",
            // Hindi.
            "जी", "जी हाँ", "हां जी", "हाँ हाँ", "बिल्कुल", "जरूर रखिए", "ठीक है", "सही है", "हाँ रख लीजिए", "याद रखिए",
            "हाँ कर दीजिए", "हो जाए", "चलेगा", "अच्छा ठीक है", "क्यों नहीं", "कोई दिक्कत नहीं", "हाँ जी, रख सकती हैं",
            "पक्का", "मंज़ूर है", "रख लो ना", "हाँ भैया",
            // Hinglish.
            "haan", "haan ji", "hanji", "ji haan theek hai", "bilkul", "zaroor rakh lo", "haan rakh lijiye", "theek hai",
            "kyun nahi", "koi dikkat nahi", "haan haan bilkul", "pakka", "chalo theek hai", "sahi hai", "kar dijiye",
            "haan yaad rakho", "acha theek hai", "okay bhai",
            // Bengali and Benglish.
            "হ্যাঁ", "হ্যাঁ রাখুন", "ঠিক আছে", "আচ্ছা", "অবশ্যই", "রাখতে পারেন", "কোনো সমস্যা নেই", "হ্যাঁ করুন",
            "রাজি", "কেন নয়", "রাখুন না", "thik ache", "hya rakhun", "obosshoi",
            // Tamil.
            "ஆம்", "சரி", "ஆமா", "ஓகே", "கண்டிப்பாக", "பரவாயில்லை", "சரி வைத்துக்கொள்ளுங்கள்", "நிச்சயமாக",
            // Kannada.
            "ಹೌದು", "ಸರಿ", "ಆಯ್ತು", "ಓಕೆ", "ಖಂಡಿತ", "ಪರವಾಗಿಲ್ಲ", "ಸರಿ ಇಟ್ಟುಕೊಳ್ಳಿ", "ಹೌದು ಮಾಡಿ"})
    void theseAreAYes(String reply) {
        assertThat(ReplyGuard.read(reply)).as(reply).isEqualTo(Reading.YES);
        assertThat(ReplyGuard.permitsYes(reply)).as(reply).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // English.
            "no", "nope", "no thanks", "no thank you", "not now", "never", "don't", "please don't", "do not save it",
            "nah", "no need", "don't keep it",
            // Hindi.
            "नहीं", "नही", "ना", "जी नहीं", "नहीं जी", "बिल्कुल नहीं", "अभी नहीं", "मत रखो", "मत रखिए", "नहीं चाहिए",
            "रहने दो", "रहने दीजिए", "कभी नहीं", "जरूरत नहीं", "नहीं रख सकते", "नहीं नहीं", "मत करो",
            // Hinglish.
            "nahi", "nahin", "na", "nahi ji", "bilkul nahi", "abhi nahi", "mat rakho", "rehne do", "nahi chahiye",
            "kabhi nahi", "mat karo",
            // Bengali.
            "না", "না না", "চাই না", "দরকার নেই", "রাখবেন না", "এখন না", "থাক", "করবেন না",
            // Tamil.
            "இல்லை", "வேண்டாம்", "முடியாது", "இப்போது இல்லை",
            // Kannada.
            "ಇಲ್ಲ", "ಬೇಡ", "ಈಗ ಬೇಡ", "ಆಗಲ್ಲ"})
    void theseAreANo(String reply) {
        assertThat(ReplyGuard.read(reply)).as(reply).isEqualTo(Reading.NO);
        assertThat(ReplyGuard.permitsYes(reply)).as(reply).isFalse();
    }

    /** A condition vetoes a yes: never YES, and a model may not upgrade it either. */
    @ParameterizedTest
    @ValueSource(strings = {
            // English.
            "yes but only if you pay first", "yes if the price is right", "okay but not today", "maybe", "perhaps",
            "sure, unless it rains", "ok, provided he comes", "yes, later", "it depends", "probably yes",
            "yes, only the onion", "fine, except the transport",
            // Hindi.
            "हाँ अगर पैसा मिले", "ठीक है लेकिन", "हाँ लेकिन पहले भाव बताइए", "शायद", "हाँ मगर", "सिर्फ़ प्याज़ के लिए हाँ",
            "ठीक है पर कल", "हाँ बाद में", "जब खरीदार आए तब", "हाँ बशर्ते नकद हो",
            // Hinglish.
            "haan agar paisa mile", "theek hai lekin", "haan magar", "shayad", "haan par pehle rate batao",
            "sirf kal ke liye", "haan baad mein", "jab buyer aaye tab haan",
            // Bengali.
            "হ্যাঁ যদি দাম ঠিক হয়", "ঠিক আছে কিন্তু", "হয়তো", "শুধু আজ", "হ্যাঁ তবে পরে",
            // Tamil.
            "சரி ஆனால்", "ஒருவேளை", "ஆம் மட்டும் இன்று",
            // Kannada.
            "ಸರಿ ಆದರೆ", "ಬಹುಶಃ", "ಹೌದು ಮಾತ್ರ ಇವತ್ತು"})
    void conditionsAreNeverAYes(String reply) {
        assertThat(ReplyGuard.read(reply)).as(reply).isEqualTo(Reading.UNCLEAR);
        assertThat(ReplyGuard.verdict(reply).vetoed()).as(reply).isTrue();
        assertThat(ReplyGuard.permitsYes(reply)).as(reply).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Questions, doubts, mixed answers, noise and other topics.
            "yes?", "", "   ", "okay so the price is what you said it would be", "kya bola aapne", "what?",
            "theek nahi hai", "haan nahi", "yes no", "pata nahi", "पता नहीं", "मालूम नहीं", "not sure", "I don't know",
            "soch ke bataunga", "सोच कर बताता हूँ", "ruko", "एक मिनट", "hold on", "wait", "hmm", "क्या मतलब",
            "what do you mean", "कौन बोल रहा है", "मुझे भुट्टा बेचना है", "भाव क्या है", "pyaz ka bhav batao",
            "कितने बजे", "how much", "জানি না", "বলতে পারছি না", "কেন", "தெரியாது", "என்ன", "ಗೊತ್ತಿಲ್ಲ",
            "ಏನು", "mera naam Aniket hai", "अनिकेत", "Ramesh"})
    void theseAreUnclear(String reply) {
        assertThat(ReplyGuard.read(reply)).as(reply).isEqualTo(Reading.UNCLEAR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"haan laga do", "unse baat karao", "Mehta ko phone lagao", "फ़ोन लगा दीजिए", "connect kar do",
            "मिला दीजिए", "हाँ ज़रूर", "go ahead"})
    void theseAreConsent(String s) {
        assertThat(ReplyGuard.isConsent(s)).as(s).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mat lagao", "phone laga do agar rate achha ho", "nahi", "pata nahi", "abhi mat milao",
            "connect mat karo", "kya aap connect karoge?"})
    void theseAreNotConsent(String s) {
        assertThat(ReplyGuard.isConsent(s)).as(s).isFalse();
    }
}
