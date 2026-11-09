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
final class TurnIntent {
    enum Kind { NAME_SOURCE, IDENTITY, REPEAT, GREETING, THANKS, BYE, YES, NO, PRICE, WEATHER, SELL, FORGET }

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
    private static final Pattern FORGET = Pattern.compile(
            "भूल जा|मिटा|forget me|delete my|bhool ja|bhul ja|mita do|ভুলে যা|মুছে|bhule ja|muche");
    private static final Pattern STATED_NAME = Pattern.compile(
            "(?iu)(?:मेरा नाम|my name is|mera naam|mera nam|আমার নাম|amar naam|amar nam)\\s+([\\p{L}\\p{M}]+(?: [\\p{L}\\p{M}]+){0,3})");
    private static final Set<String> NOT_A_NAME = Set.of("है", "हैं", "hai", "hain", "जी", "ji", "বলে", "হল");
    private static final Set<String> QUESTION_WORDS = Set.of("क्या", "क्यों", "kya", "kyun", "what", "কী", "কি", "ki", "kaise", "कैसे");
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
        if (FORGET.matcher(t).find()) out.add(Kind.FORGET);
        return out;
    }

    /** The name in "मेरा नाम X है", "my name is X" and the like; empty when the turn does not state one. */
    static Optional<String> statedName(String text) {
        Matcher m = STATED_NAME.matcher(SPACES.matcher(text == null ? "" : text.strip()).replaceAll(" "));
        if (!m.find()) return Optional.empty();
        List<String> words = new ArrayList<>(List.of(m.group(1).split(" ")));
        words.removeIf(w -> NOT_A_NAME.contains(w.toLowerCase(Locale.ROOT)));
        if (words.isEmpty() || words.size() > 3 || words.stream().anyMatch(w -> QUESTION_WORDS.contains(w.toLowerCase(Locale.ROOT)))) {
            return Optional.empty();
        }
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
