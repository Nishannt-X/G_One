package com.example.g_one.agent.brain;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a farmer's turn is about, from words alone, in Hindi, Hinglish, English and Bengali. Cheap and without a model:
 * it picks the tools worth offering and the questions that have a fixed truthful answer. It misses things; a miss only
 * means the model answers instead.
 */
public final class TurnIntent {
    enum Kind { NAME_SOURCE, IDENTITY, REPEAT, GREETING, THANKS, BYE, YES, NO, PRICE, WEATHER, SELL, CONNECT, FORGET }

    private static final int SHORT_TURN_WORDS = 7;

    private static final Pattern NAME_SOURCE = Pattern.compile(
            "नाम.*(कैसे|कहाँ से|कहां से|किसने|क्यों).*(पता|मालूम|जान|बताया)|(कैसे|कहाँ से|कहां से).*(मेरा|मेरे) नाम|मेरा नाम क्या"
            + "|(मुझे|मुझको) (कैसे )?(पहचान|जानत|जानती)|कैसे (पता|जानत).*नाम"
            + "|naam.*(kaise|kahan se|kisne|kyun).*(pata|maloom|jaan|bataya)|(kaise|kahan se).*(mera|mere) naam|mera naam kya"
            + "|(mujhe|mujhko) (kaise )?(pehchan|pehchaan|pahchan|jaant)|kaise (pata|jaan).*naam"
            + "|how (do|did|come) you (know|have|get|got)\\b.*\\b(name|me)\\b|where did you get my name|who told you my name"
            + "|do you (know|recogni[sz]e) me|what('s| is) my name"
            + "|নাম.*(কী করে|কি করে|কীভাবে|কিভাবে|কোথা থেকে).*(জান|পেল)|(কী করে|কি করে|কীভাবে|কিভাবে).*(আমার নাম|আমাকে চেন)"
            + "|আমাকে চেন|আমার নাম কী|amar nam.*(ki kore|kivabe|kibhabe|kothay)|ki kore.*amar nam|amake chen|amar naam.*(ki kore|kivabe|kibhabe|kothay)");
    private static final Pattern IDENTITY = Pattern.compile(
            "(तुम|आप|तू) कौन|कौन बोल|क्या(-| )?(क्या )?कर (सकत|पात)|(आप|तुम) (इंसान|रोबोट|मशीन)|क्या (आप|तुम) .*(एआई|रोबोट|मशीन|इंसान)"
            + "|(tum|aap|tu) kaun|kaun bol|kya(-| )?(kya )?kar (sakt|pat)|(insaan|robot|machine) ho"
            + "|who are you|who('s| is) this|what can you do|what do you do|how can you help|are you (a |an )?(robot|human|ai|machine|real|bot)"
            + "|(আপনি|তুমি) কে|কে বলছ|কী করতে পার|কি করতে পার|apni ke|tumi ke|ke bolchen|ki korte par");
    private static final Pattern REPEAT = Pattern.compile(
            "फिर से|दोबारा|दुबारा|क्या बोल|क्या कहा|सुनाई नहीं|phir se|fir se|dobara|dubara|kya bol|kya kaha|sunai nahi"
            + "|repeat|say (that|it)? ?again|once more|didn'?t hear|come again|আবার বল|আর একবার|শুনতে পাইনি|abar bol|shunte pai ni");
    private static final Pattern GREETING = Pattern.compile(
            "^(नमस्ते|नमस्कार|राम राम|हेलो|हैलो|प्रणाम|hello|hi|hey|namaste|namaskar|ram ram|pranam|good (morning|afternoon|evening)"
            + "|নমস্কার|হ্যালো|আদাব|nomoskar)( जी| ji)?( .{0,20})?$");
    private static final Pattern THANKS = Pattern.compile("धन्यवाद|शुक्रिया|thanks|thank you|dhanyavaad|dhanyavad|shukriya|ধন্যবাদ|dhonnobad");
    private static final Pattern BYE = Pattern.compile(
            "अलविदा|बाय|फोन रख|रखता हूँ|रखती हूँ|बस इतना|bye|goodbye|phone rakh|rakhta hu|rakhti hu|bas itna|that'?s all|বিদায়|রাখছি|rakhchi");
    private static final Pattern YES = Pattern.compile(
            "^(हाँ|हां|जी|जी हाँ|जी हां|ठीक है|बिल्कुल|yes|yeah|yep|haan|han|ha|ji|ji haan|theek hai|thik hai|ok|okay|sure"
            + "|হ্যাঁ|হাঁ|ঠিক আছে|hyan|thik ache)( जी| ji)?$");
    private static final Pattern NO = Pattern.compile("^(नहीं|नही|ना|मत|no|nope|nahi|nahin|na|না|নাহ)( जी| ji)?$");
    private static final Pattern PRICE = Pattern.compile(
            "भाव|रेट|दाम|कीमत|मंडी|एमएसपी|\\bmsp\\b|bhav|bhaav|bhao|bhaw|\\brate\\b|daam|\\bdam\\b|kimat|keemat|mandi|price|market"
            + "|কত দাম|দাম|দর|বাজার|মান্ডি");
    private static final Pattern WEATHER = Pattern.compile(
            "मौसम|बारिश|बरसात|पानी गिर|तापमान|mausam|barish|baarish|barsaat|weather|rain|forecast|temperature"
            + "|আবহাওয়া|বৃষ্টি|bristi|brishti|abohawa");
    private static final Pattern SELL = Pattern.compile(
            "बेच|बिक|खरीदार|ग्राहक|व्यापारी|कितने में|bech|bik|kharidar|grahak|vyapari|kitne me|\\bsell|buyer"
            + "|বিক্রি|ক্রেতা|bikri|kreta");
    /** "Connect me to someone", "किसी से बात कराइए": the farmer wants a buyer on the line. */
    private static final Pattern CONNECT = Pattern.compile(
            "कनेक्ट|जोड़ दीजिए|जोड़ दो|जोड़िए|जोड दीजिए|जोड दो|जोडिए|बात करा|बात करवा|मिला दीजिए|मिला दो|मिलवा|फ़ोन लगा|फोन लगा"
            + "|connect|jod do|jod dijiye|jodiye|baat kara|baat karwa|milwa|mila do|mila dijiye|phone laga|call kara"
            + "|put me through|talk to (a|the|some) ?(buyer|trader|one|body)|speak to (a|the|some) ?(buyer|trader|one|body)"
            + "|জুড়ে দিন|জুড়ে দাও|কথা বলিয়ে|কানেক্ট|jure din|kotha boliye");
    /** "मैं वो नहीं हूँ", "wrong number", "someone else": the caller is not the person the name check asked about. */
    private static final Pattern DENIAL = Pattern.compile(
            "(मैं|मै|हम) (वो|वह|वे|उनमें से कोई) नहीं|मैं .{1,24} नहीं हूँ|मैं .{1,24} नहीं हूं|वो नहीं हूँ|वह नहीं हूँ|वो नहीं हूं|वह नहीं हूं|कोई और"
            + "|दूसरा (आदमी|व्यक्ति|बोल)|ग़लत नंबर|गलत नंबर|रॉन्ग नंबर|उनका (बेटा|भाई|पोता)|उनकी (बेटी|पत्नी|बहन)|उनके (पति|पिता|बेटे)"
            + "|(main|mai|hum) (wo|woh|vo|voh|ve) nahi|wo nahi hu|woh nahi hu|vo nahi hu|koi aur|galat number|wrong number"
            + "|unka (beta|bhai)|unki (beti|patni)|someone else|somebody else|not (him|her|them)\\b|i('m| am) not (him|her|that|the one|the person)"
            + "|আমি (উনি|সে|ও) নই|অন্য কেউ|ভুল নম্বর|ami (uni|se) noi|onno keu|vul number"
            + "|நான் அவர் இல்லை|வேறு ஆள்|தவறான எண்|ನಾನು ಅವರಲ್ಲ|ಬೇರೆ ವ್ಯಕ್ತಿ|ತಪ್ಪು ನಂಬರ್");
    private static final Pattern FORGET = Pattern.compile(
            "भूल जा|मिटा|forget me|delete my|bhool ja|bhul ja|mita do|ভুলে যা|মুছে|bhule ja|muche"
            + "|மறந்துவிடு|மறந்து விடு|ಮರೆತುಬಿಡ|ಮರೆತು ಬಿಡ");
    private static final Pattern STATED_NAME = Pattern.compile(
            "(?iu)(?:मेरा नाम|my name is|my name's|mera naam|mera nam|आमार नाम|আমার নাম|amar naam|amar nam|என் பெயர்|ನನ್ನ ಹೆಸರು)"
            + "\\s+([\\p{L}\\p{M}]+(?: [\\p{L}\\p{M}]+){0,5})");
    /** "मैं अनिकेत हूँ", "main Aniket hoon", "I am Aniket", "this is Aniket", "আমি অনিকেত". */
    private static final Pattern INTRODUCED = Pattern.compile(
            "(?iu)(?:^|[,.।!\\s])(?:मैं|मै|main|mai|i am|i'm|im|this is|it's|its|আমি|ami|நான்|ನಾನು)\\s+"
            + "([\\p{L}\\p{M}]+(?: [\\p{L}\\p{M}]+){0,2})\\s*(?:हूँ|हूं|हू|hoon|hun|hu|here|speaking|बोल रहा|बोल रही|bol raha|bol rahi)?");
    private static final Set<String> NOT_A_NAME = Set.of("है", "हैं", "hai", "hain", "जी", "ji", "বলে", "হল");
    /** A name ends at these words: "मेरा नाम अनिकेत है और मैं..." is अनिकेत. */
    private static final Set<String> NAME_ENDS = Set.of("है", "हैं", "हूँ", "हूं", "और", "पर", "तो", "से", "hai", "hain", "hoon",
            "hun", "hu", "aur", "and", "but", "is", "i", "here", "मैं", "मै", "main", "mai", "बोल", "bol", "speaking",
            "এবং", "আর", "বলছি", "হল", "আমি");
    /** Words that follow "मैं" or "I am" but are not a name. */
    private static final Set<String> NOT_INTRODUCED = Set.of("ठीक", "किसान", "वो", "वह", "वे", "यहाँ", "यहां", "बोल", "नहीं",
            "नही", "भी", "तो", "आपको", "आपसे", "उनका", "उनकी", "उनके", "बेचना", "बेच", "जानना", "पूछ", "कुछ", "एक", "अपना",
            "अपनी", "मंडी", "खेत", "गाँव", "गांव", "सुन", "समझ", "चाहता", "चाहती", "कह", "theek", "thik", "kisan", "wo", "woh",
            "vo", "yahan", "nahi", "bhi", "to", "unka", "unki", "bechna", "jaanna", "kuch", "ek", "fine", "good", "ok", "okay",
            "not", "a", "an", "the", "from", "here", "calling", "farmer", "well", "also", "his", "her", "their", "just",
            "looking", "trying", "want", "wanted", "going", "selling", "interested", "sorry", "speaking", "সে", "উনি", "ও", "ঠিক",
            "কৃষক", "নই", "চাই", "বিক্রি");
    private static final Set<String> QUESTION_WORDS = Set.of("क्या", "क्यों", "kya", "kyun", "what", "কী", "কি", "ki", "kaise", "कैसे");
    private static final Pattern NAME_QUESTION = Pattern.compile("(?iu)(नाम|\\bname\\b|নাম|\\bnaam\\b)[^.।!]*\\?");
    private static final Pattern NAME_REQUEST = Pattern.compile(
            "(?iu)\\b(?:tell|give|share)\\s+(?:me\\s+)?your\\s+name\\b|\\b(?:state|say)\\s+your\\s+name\\b"
            + "|(?:अपना|आपका|आपके) नाम[^.।!?]*(?:बताइए|बताएं|बताएँ|बताओ)"
            + "|(?:apna|aapka|aapke) naam[^.?!]*(?:bataye|batayen|batao)"
            + "|(?:আপনার|তোমার) নাম[^.।!?]*(?:বলুন|বলো)");
    private static final Set<String> NAME_FILLER = Set.of("मैं", "मै", "main", "mai", "हूँ", "हूं", "hoon", "hun", "hu", "है", "hai", "जी", "ji", "ami");
    private static final Set<String> NOT_A_SHORT_NAME = Set.of("नहीं", "नही", "nahi", "nahin", "no", "not", "बाद", "baad", "later", "अभी", "abhi", "why", "क्यों", "हाँ", "हां", "yes", "ok", "okay");
    private static final Pattern NAME_WORD = Pattern.compile("[\\p{L}\\p{M}]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern TRIM = Pattern.compile("^[\\p{P}\\s]+|[\\p{P}\\s]+$");

    private TurnIntent() {}

    static String normalized(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT).strip();
        return SPACES.matcher(TRIM.matcher(lower).replaceAll("")).replaceAll(" ");
    }

    static Set<Kind> of(String text) {
        String t = normalized(text);
        Set<Kind> out = EnumSet.noneOf(Kind.class);
        if (t.isEmpty()) return out;
        boolean isShort = t.split(" ").length <= SHORT_TURN_WORDS;
        if (NAME_SOURCE.matcher(t).find()) out.add(Kind.NAME_SOURCE);
        if (IDENTITY.matcher(t).find()) out.add(Kind.IDENTITY);
        if (isShort && REPEAT.matcher(t).find()) out.add(Kind.REPEAT);
        if (GREETING.matcher(t).find()) out.add(Kind.GREETING);
        if (isShort && THANKS.matcher(t).find()) out.add(Kind.THANKS);
        if (isShort && BYE.matcher(t).find()) out.add(Kind.BYE);
        if (YES.matcher(t).matches()) out.add(Kind.YES);
        if (NO.matcher(t).matches()) out.add(Kind.NO);
        if (PRICE.matcher(t).find()) out.add(Kind.PRICE);
        if (WEATHER.matcher(t).find()) out.add(Kind.WEATHER);
        if (SELL.matcher(t).find()) out.add(Kind.SELL);
        if (CONNECT.matcher(t).find()) out.add(Kind.CONNECT);
        if (FORGET.matcher(t).find()) out.add(Kind.FORGET);
        return out;
    }

    /** The name in "मेरा नाम X है", "my name is X" and the like; empty when the turn does not state one. */
    public static Optional<String> statedName(String text) {
        Matcher m = STATED_NAME.matcher(SPACES.matcher(text == null ? "" : text.strip()).replaceAll(" "));
        if (!m.find()) return Optional.empty();
        return nameWords(m.group(1), Set.of());
    }

    /**
     * The name in "मैं अनिकेत हूँ", "main Aniket hoon", "I am Aniket", "नहीं, मैं राम हूँ", or a stated name. Use it only where a
     * name is expected (an answer to the name check or to a name question): "मैं किसान हूँ" and "I am fine" are not names.
     */
    public static Optional<String> introducedName(String text) {
        Optional<String> stated = statedName(text);
        if (stated.isPresent()) return stated;
        Matcher m = INTRODUCED.matcher(SPACES.matcher(text == null ? "" : text.strip()).replaceAll(" "));
        while (m.find()) {
            Optional<String> name = nameWords(m.group(1), NOT_INTRODUCED);
            if (name.isPresent()) return name;
        }
        return Optional.empty();
    }

    /** The words of a name up to the first word that ends it; empty for a question, a non-name or more than three words. */
    private static Optional<String> nameWords(String raw, Set<String> notFirst) {
        List<String> words = new ArrayList<>();
        for (String w : raw.split(" ")) {
            String lower = w.toLowerCase(Locale.ROOT);
            if (NAME_ENDS.contains(lower)) break;
            if (NOT_A_NAME.contains(lower)) continue;
            words.add(w);
        }
        if (words.isEmpty() || words.size() > 3) return Optional.empty();
        if (notFirst.contains(words.get(0).toLowerCase(Locale.ROOT))) return Optional.empty();
        for (String w : words) {
            String lower = w.toLowerCase(Locale.ROOT);
            if (QUESTION_WORDS.contains(lower) || NOT_A_SHORT_NAME.contains(lower) || notFirst.contains(lower)) return Optional.empty();
        }
        return Optional.of(String.join(" ", words));
    }

    /** True when the turn says the caller is not the person asked about ("मैं वो नहीं हूँ", "wrong number"). */
    public static boolean deniesIdentity(String text) {
        return DENIAL.matcher(normalized(text)).find();
    }

    /** True for "connect me to someone" and the like. */
    static boolean wantsConnection(String text) {
        return CONNECT.matcher(normalized(text)).find();
    }

    /** True when an assistant line asks the farmer's name, such as "और आपका नाम क्या है?" or "May I know your name?". */
    public static boolean asksName(String assistantLine) {
        return assistantLine != null && (NAME_QUESTION.matcher(assistantLine).find() || NAME_REQUEST.matcher(assistantLine).find());
    }

    /**
     * A bare answer to a name question: "Ramesh", "रमेश", "Ramesh hai", "main Ramesh", "मैं रमेश हूँ". Use it only right
     * after the name was asked; empty when the turn is something else (a yes or no, a price or sale request, a refusal,
     * more than three name words).
     */
    public static Optional<String> shortName(String text) {
        Optional<String> stated = statedName(text);
        if (stated.isPresent()) return stated;
        String plain = SPACES.matcher(text == null ? "" : text.strip()).replaceAll(" ");
        if (plain.isEmpty() || plain.split(" ").length > 6 || unreadable(plain)) return Optional.empty();
        Set<Kind> intents = of(plain);
        intents.remove(Kind.GREETING);
        if (!intents.isEmpty()) return Optional.empty();
        List<String> words = new ArrayList<>();
        for (String raw : plain.split(" ")) {
            String w = TRIM.matcher(raw).replaceAll("");
            String lower = w.toLowerCase(Locale.ROOT);
            if (w.isEmpty() || NAME_FILLER.contains(lower)) continue;
            if (!NAME_WORD.matcher(w).matches() || NOT_A_SHORT_NAME.contains(lower) || QUESTION_WORDS.contains(lower)) {
                return Optional.empty();
            }
            words.add(w);
        }
        if (words.isEmpty() || words.size() > 3) return Optional.empty();
        return Optional.of(String.join(" ", words));
    }

    /** True when the turn has no letters at all ("...", "?"), which is noise rather than speech. */
    static boolean noWords(String text) {
        return text.codePoints().noneMatch(Character::isLetter);
    }

    /** True when the turn is in a script this class has no words for, so nothing can be concluded from a miss. */
    static boolean unreadable(String text) {
        return ReplyLanguage.dominantPrefix(text).filter(p -> !p.equals("en") && !p.equals("hi") && !p.equals("bn")).isPresent();
    }
}
