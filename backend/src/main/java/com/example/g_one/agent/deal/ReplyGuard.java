package com.example.g_one.agent.deal;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Plain-code reading of a spoken yes or no, in English, Hindi, Hinglish, Bengali, Tamil and Kannada. Unclear means not a
 * yes: this guard fails closed, and it also vetoes a model that reads a conditional as a yes ("yes, but...", "haan
 * agar..."). Used for the deal confirmation, the consent to connect a buyer, the end-of-call questions and the
 * returning farmer's name check.
 *
 * <p>A reply is read in this order, on words (so "ha" never matches inside "hamara"):
 * <ol>
 *   <li>A doubt ("pata nahi", "not sure", "soch ke batata hoon", "ruko") is unclear, whatever else it says.</li>
 *   <li>Idioms that carry a negation but mean yes ("koi baat nahi", "no problem", "why not", "क्यों नहीं",
 *       "কোনো সমস্যা নেই", "பரவாயில்லை") count as a yes, and their negation words are not read again.</li>
 *   <li>Phrases that mean no ("abhi nahi", "rehne do", "mat rakho", "no thanks") count as a no, and their words are not
 *       read again, so the "rakho" in "mat rakho" is not a yes.</li>
 *   <li>A condition ("agar", "lekin", "but", "only", "যদি", "ஆனால்") or a question vetoes the reply: unclear.</li>
 *   <li>Yes phrases and words ("रख सकते हैं", "go ahead", "chalega", "ज़रूर") and no words are counted. Both, or neither,
 *       is unclear; a yes in a long sentence is unclear too, because a long turn is saying something else.</li>
 * </ol>
 * Honorifics ("जी", "sir", "bhaiya") are ignored, so "जी नहीं" is a no and "जी हाँ" a yes; "जी" alone is a yes.
 */
public final class ReplyGuard {
    /** What a short reply meant. */
    public enum Reading { YES, NO, UNCLEAR }

    /**
     * The reading and whether it was vetoed: a question, a condition, a doubt, or yes and no together. A vetoed reply
     * must never become a yes, not even through a language model.
     */
    public record Verdict(Reading reading, boolean vetoed) {}

    /**
     * A longer sentence that happens to contain "okay" is not an answer; the model reads those. Counted after yes
     * phrases became one word, without honorifics.
     */
    static final int MAX_YES_WORDS = 8;

    private static final String YES_MARK = "\u0001yes";
    private static final String NO_MARK = "\u0001no";

    /** Doubt, delay or "what do you mean": unclear even when a yes or no word is in it. */
    private static final List<String> UNSURE = phrases(
            "pata nahi", "pata nahin", "pata nai", "पता नहीं", "पता नही", "maloom nahi", "malum nahi", "मालूम नहीं",
            "don't know", "dont know", "do not know", "not sure", "no idea", "can't say", "cant say", "cannot say",
            "soch ke", "soch kar", "sochke", "सोच के", "सोच कर", "सोचकर", "सोचके", "पक्का नहीं", "pakka nahi",
            "कह नहीं सकता", "कह नहीं सकती", "keh nahi sakta", "baad mein bataunga", "बाद में बताऊंगा", "बाद में बताता",
            "let me think", "i'll think", "think about it", "hmm", "hmmm", "हम्म", "umm", "uh", "kya matlab",
            "क्या मतलब", "what do you mean", "ruko", "रुको", "रुकिए", "ek minute", "एक मिनट", "hold on", "wait",
            "জানি না", "jani na", "বলতে পারছি না", "ভেবে দেখি", "দাঁড়ান", "தெரியாது", "தெரியவில்லை", "யோசிக்கிறேன்",
            "ಗೊತ್ತಿಲ್ಲ", "ಗೊತ್ತಿಲ", "ಯೋಚಿಸುತ್ತೇನೆ");
    /** Negation words that mean yes in these idioms. */
    private static final List<String> YES_IDIOMS = phrases(
            "koi baat nahi", "koi baat nahin", "koi baat nai", "कोई बात नहीं", "कोई बात नही", "koi dikkat nahi",
            "कोई दिक्कत नहीं", "koi problem nahi", "कोई प्रॉब्लम नहीं", "कोई प्रोब्लम नहीं", "koi pareshani nahi",
            "कोई परेशानी नहीं", "koi harj nahi", "कोई हर्ज नहीं", "कोई आपत्ति नहीं", "koi aapatti nahi", "no problem",
            "not a problem", "no issue", "no issues", "no worries", "why not", "kyun nahi", "kyon nahi", "kyu nahi",
            "kyun nahin", "क्यों नहीं", "क्यूं नहीं", "क्यो नहीं", "i don't mind", "i dont mind", "don't mind", "dont mind",
            "কোনো সমস্যা নেই", "কোন সমস্যা নেই", "সমস্যা নেই", "কোনো অসুবিধা নেই", "কোন অসুবিধা নেই", "অসুবিধা নেই",
            "kono somossa nei", "kono problem nei", "kono osubidha nei", "কেন নয়", "keno noy", "பரவாயில்லை",
            "பிரச்சனை இல்லை", "பிரச்சினை இல்லை", "ஏன் இல்லை", "ಪರವಾಗಿಲ್ಲ", "ಸಮಸ್ಯೆ ಇಲ್ಲ", "ತೊಂದರೆ ಇಲ್ಲ", "ಯಾಕಿಲ್ಲ");
    /** "Why not" stays a yes even when speech-to-text wrote a question mark after it. */
    private static final List<String> WHY_NOT = phrases("why not", "kyun nahi", "kyon nahi", "kyu nahi", "kyun nahin",
            "क्यों नहीं", "क्यूं नहीं", "क्यो नहीं", "কেন নয়", "keno noy", "ஏன் இல்லை", "ಯಾಕಿಲ್ಲ");
    /** Phrases that mean no; their words are not read again. */
    private static final List<String> NO_PHRASES = phrases(
            "bilkul nahi", "बिल्कुल नहीं", "बिलकुल नहीं", "abhi nahi", "अभी नहीं", "not now", "no thanks", "no thank you",
            "nahi chahiye", "नहीं चाहिए", "zarurat nahi", "zaroorat nahi", "जरूरत नहीं", "mat karo", "mat kijiye",
            "mat rakho", "mat rakhiye", "mat rakhna", "मत रखो", "मत रखिए", "मत रखना", "मत करो", "मत कीजिए",
            "nahi rakh", "नहीं रख", "nahi kar", "नहीं कर", "rehne do", "rahne do", "rehne dijiye", "रहने दो", "रहने दीजिए",
            "no need", "not needed", "kabhi nahi", "कभी नहीं", "please don't", "do not", "don't save", "don't keep",
            "চাই না", "দরকার নেই", "লাগবে না", "করবেন না", "রাখবেন না", "এখন না", "না না", "থাক", "இப்போது இல்லை",
            "வேண்டாம்", "ಈಗ ಬೇಡ", "ಬೇಡವೇ ಬೇಡ");
    private static final List<String> YES_PHRASES = phrases(
            "go ahead", "please do", "yes please", "sounds good", "all right", "that's fine", "thats fine", "fine by me",
            "do it", "of course", "kar do", "kar dijiye", "kar dijie", "kar lo", "kar lijiye", "कर दो", "कर दीजिए",
            "कर दीजिये", "कर लो", "कर लीजिए", "rakh lo", "rakh lijiye", "rakh sakte", "rakh sakti", "rakh lena", "रख लो",
            "रख लीजिए", "रख लीजिये", "रख सकते", "रख सकती", "रख लेना", "याद रखो", "याद रखिए", "याद रख", "yaad rakho",
            "yaad rakh", "ho jaye", "ho jaaye", "हो जाए", "हो जाये", "हो जाएगा", "theek hai", "thik hai", "ठीक है",
            "sahi hai", "सही है", "चलो ठीक", "thik ache", "ঠিক আছে", "রাখতে পারেন", "রাখুন", "করুন", "rakhte paren",
            "rakhun", "korun", "মনে রাখুন", "வைத்துக்கொள்ளுங்கள்", "வைத்துக்கொள்ளலாம்", "செய்யுங்கள்", "ಇಟ್ಟುಕೊಳ್ಳಿ",
            "ನೆನಪಿಟ್ಟುಕೊಳ್ಳಿ", "ಮಾಡಿ");
    private static final Set<String> YES = words("yes", "yeah", "yea", "yep", "yup", "ya", "ok", "okay", "okey", "k",
            "sure", "correct", "right", "agreed", "agree", "done", "confirm", "confirmed", "accepted", "accept", "fine",
            "alright", "absolutely", "definitely", "certainly", "surely", "please", "haan", "han", "haa", "ha", "haanji",
            "hanji", "hnji", "hmm-hmm", "theek", "thik", "sahi", "pakka", "manzoor", "manjoor", "bilkul", "zarur",
            "zaroor", "jarur", "jaroor", "avashya", "chalega", "chalo", "achha", "acha", "accha", "rakho", "rakhiye",
            "rakhie", "हाँ", "हां", "हा", "हाँजी", "हांजी", "ठीक", "सही", "पक्का", "मंज़ूर", "मंजूर", "बिल्कुल", "बिलकुल",
            "ज़रूर", "जरूर", "अवश्य", "चलेगा", "चलो", "अच्छा", "रखो", "रखिए", "रखिये", "ओके",
            "হ্যাঁ", "হাঁ", "হা", "ঠিক", "আচ্ছা", "রাজি", "অবশ্যই", "নিশ্চয়ই", "নিশ্চয়", "চলবে", "hya", "hyan", "achha",
            "obosshoi", "oboshoi", "nishchoy", "cholbe", "thik",
            "ಹೌದು", "ಸರಿ", "ಆಯಿತು", "ಆಯ್ತು", "ಒಪ್ಪಿಗೆ", "ಓಕೆ", "ಖಂಡಿತ", "ಖಂಡಿತವಾಗಿ", "ಹೂಂ", "ಬೇಕು",
            "ஆம்", "ஆமா", "ஆமாம்", "ஆமாங்க", "சரி", "சரிங்க", "ஓகே", "கண்டிப்பாக", "நிச்சயமாக", "தாராளமாக");
    private static final Set<String> NO = words("no", "nope", "nah", "nahi", "nahin", "nai", "naa", "na", "mat", "never",
            "not", "don't", "dont", "doesn't", "doesnt", "won't", "wont", "cannot", "can't", "cant", "rehne", "rahne",
            "मत", "नहीं", "नही", "ना", "रहने", "কখনো", "না", "নয়", "নই", "নেই", "থাক",
            "ಇಲ್ಲ", "ಬೇಡ", "ಬೇಡಾ", "ಬೇಡಿ", "ಆಗಲ್ಲ", "இல்லை", "இல்ல", "வேண்டாம்", "வேணாம்", "முடியாது", "கூடாது");
    /** Any of these makes a reply unclear: it sets a condition. */
    private static final Set<String> CONDITIONAL = words("if", "unless", "but", "maybe", "perhaps", "only", "except",
            "provided", "depends", "depending", "probably", "later", "first", "agar", "lekin", "par", "magar", "shayad",
            "sirf", "bashart", "baad", "pehle", "pahle", "jab", "tabhi", "अगर", "लेकिन", "मगर", "परंतु", "पर", "शायद",
            "सिर्फ़", "सिर्फ", "बशर्ते", "बाद", "पहले", "जब", "तभी", "যদি", "কিন্তু", "হয়তো", "শুধু", "তবে", "পরে", "jodi",
            "kintu", "hoyto", "shudhu", "tobe", "ಆದರೆ", "ಆದ್ರೆ", "ಬಹುಶಃ", "ಮಾತ್ರ", "ಇದ್ದರೆ", "ஆனால்", "ஆனா", "ஒருவேளை",
            "மட்டும்", "என்றால்");
    /** A turn starting with or made of these asks something back, even without a question mark. */
    private static final Set<String> QUESTION = words("what", "why", "how", "who", "which", "when", "kya", "kyun", "kyon",
            "kaise", "kaun", "kitna", "kitne", "kab", "क्या", "क्यों", "कैसे", "कौन", "कितना", "कितने", "कब", "কী", "কি",
            "কেন", "কীভাবে", "কে", "কত", "என்ன", "ஏன்", "எப்படி", "ಏನು", "ಯಾಕೆ", "ಹೇಗೆ");
    /** Honorifics and filler: never an answer on their own, except "जी" alone, which is a yes. */
    private static final Set<String> FILLER = words("जी", "ji", "jee", "sir", "saab", "sahab", "साहब", "साब", "bhai",
            "bhaiya", "भैया", "भाई", "didi", "दीदी", "madam", "मैडम", "plz", "arey", "are", "अरे", "toh", "to", "तो",
            "haan-ji", "দাদা", "দিদি", "ஐயா", "ಸಾರ್");
    private static final Set<String> BARE_YES = words("जी", "ji", "jee");
    private static final Set<String> EMPHATIC = words("na", "naa", "ना", "না");
    private static final List<String> CONNECT_ASK = phrases("connect", "call karo", "call kar do", "call kijiye",
            "call kariye", "phone lagao", "phone laga do", "phone lagaiye", "laga do", "lagao", "milao", "mila do",
            "baat karao", "baat karwao", "baat kara do", "फ़ोन लगा", "फोन लगा", "लगा दो", "लगा दीजिए", "लगाइए", "लगाओ",
            "मिलाओ", "मिला दो", "मिला दीजिए", "बात करा", "बात करवा", "कनेक्ट", "জুড়ে দিন", "ফোন করুন", "কথা বলিয়ে",
            "இணைக்கவும்", "ಸಂಪರ್ಕಿಸಿ");

    private ReplyGuard() {}

    /** Reads a reply. A question, a conditional, a doubt, a mix of yes and no, or a long sentence is UNCLEAR. */
    public static Reading read(String text) {
        return verdict(text).reading();
    }

    /** The reading, and whether anything in the reply vetoes a yes. */
    public static Verdict verdict(String text) {
        if (text == null || text.isBlank()) return new Verdict(Reading.UNCLEAR, false);
        List<String> tokens = tokens(text);
        if (tokens.isEmpty()) return new Verdict(Reading.UNCLEAR, false);
        if (tokens.size() > 3 * MAX_YES_WORDS) return new Verdict(Reading.UNCLEAR, false);
        String joined = " " + String.join(" ", tokens) + " ";
        if (containsAny(joined, UNSURE)) return new Verdict(Reading.UNCLEAR, true);
        boolean whyNot = containsAny(joined, WHY_NOT);
        String rest = replaceAll(joined, YES_IDIOMS, YES_MARK);
        rest = replaceAll(rest, NO_PHRASES, NO_MARK);
        rest = replaceAll(rest, YES_PHRASES, YES_MARK);
        List<String> left = new ArrayList<>(Arrays.asList(rest.strip().split(" +")));
        left.removeIf(String::isEmpty);
        boolean question = (text.contains("?") || text.contains("\uFF1F")) && !whyNot;
        if (question || left.stream().anyMatch(w -> CONDITIONAL.contains(w) || QUESTION.contains(w))) {
            return new Verdict(Reading.UNCLEAR, true);
        }
        boolean yes = left.stream().anyMatch(w -> w.equals(YES_MARK) || YES.contains(w));
        // An emphatic "na" after a request ("rakh lo na", "রাখুন না") asks, it does not refuse.
        if (yes && left.size() > 1 && EMPHATIC.contains(left.get(left.size() - 1))) left.remove(left.size() - 1);
        boolean no = left.stream().anyMatch(w -> w.equals(NO_MARK) || NO.contains(w));
        List<String> said = left.stream().filter(w -> !FILLER.contains(w)).toList();
        if (!yes && !no && said.isEmpty() && left.stream().anyMatch(BARE_YES::contains)) yes = true;
        if (yes && no) return new Verdict(Reading.UNCLEAR, true);
        if (no) return new Verdict(Reading.NO, false);
        if (!yes) return new Verdict(Reading.UNCLEAR, false);
        return said.size() <= MAX_YES_WORDS ? new Verdict(Reading.YES, false) : new Verdict(Reading.UNCLEAR, false);
    }

    /** True unless the text carries a condition, a negation, a doubt or a question: a model's "yes" must also pass this. */
    public static boolean permitsYes(String text) {
        if (text == null) return false;
        Verdict v = verdict(text);
        return !v.vetoed() && v.reading() != Reading.NO;
    }

    /** An explicit yes, or a plain request to connect ("haan laga do", "unse baat karao"). */
    public static boolean isConsent(String text) {
        if (!permitsYes(text)) return false;
        if (read(text) == Reading.YES) return true;
        String joined = " " + String.join(" ", tokens(text)) + " ";
        return containsAny(joined, CONNECT_ASK);
    }

    /** Lower case, one script form: no nukta (ज़ is ज), chandrabindu as anusvara (हाँ is हां). */
    static String normalized(String text) {
        String s = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replace("़", "");
        return Normalizer.normalize(s, Normalizer.Form.NFC).replace('ँ', 'ं').replace('’', '\'');
    }

    private static boolean containsAny(String joined, List<String> phrases) {
        return phrases.stream().anyMatch(joined::contains);
    }

    /** Longest phrases first, so "no problem at all" is not half read as "no". */
    private static String replaceAll(String joined, List<String> phrases, String mark) {
        String out = joined;
        for (String p : phrases) {
            while (out.contains(p)) out = out.replace(p, " " + mark + " ");
        }
        return out;
    }

    private static List<String> tokens(String text) {
        // Keep letters, combining vowel signs and the apostrophe; everything else separates words.
        return Arrays.stream(normalized(text).split("[^\\p{L}\\p{M}']+")).map(w -> w.replaceAll("^'+|'+$", ""))
                .filter(w -> !w.isEmpty()).toList();
    }

    private static Set<String> words(String... words) {
        return Arrays.stream(words).map(ReplyGuard::normalized).collect(Collectors.toUnmodifiableSet());
    }

    /** Each phrase as " word word ", in the normal form, longest first. */
    private static List<String> phrases(String... phrases) {
        return Arrays.stream(phrases).map(p -> " " + String.join(" ", tokens(p)) + " ").distinct()
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }
}
