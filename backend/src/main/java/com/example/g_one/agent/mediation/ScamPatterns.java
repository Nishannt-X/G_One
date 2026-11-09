package com.example.g_one.agent.mediation;

import com.example.g_one.agent.caution.Caution;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Plain-code screen for the clearest scam patterns, in English, Hinglish, Hindi and Bengali. It needs no language model,
 * so a caution still reaches the farmer when the model is down. The model adds subtler cases; it can never remove these.
 */
public final class ScamPatterns {
    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final String SECRET = "(\\botp\\b|o\\.t\\.p|\\bpin\\b(?![ -]?code)|\\bcvv\\b|password|ओटीपी|पिन(?! ?कोड)|पासवर्ड|ওটিপি|পিন(?! ?কোড))";
    private static final Pattern SECRET_WORD = Pattern.compile(SECRET, F);
    private static final Pattern ASK = Pattern.compile("(\\btell\\b|\\bgive\\b|\\bshare\\b|\\bsend\\b|\\benter\\b|\\btype\\b|"
            + "\\bbata|\\bbhej|\\bdaal|\\bdal do\\b|\\bde do\\b|\\bdijiye\\b|\\bdo na\\b|\\bbolo\\b|\\bboliye\\b|बता|भेज|डाल|दीजिए|दो|बोलिए|बोलो|"
            + "দিন|বলুন|পাঠান)", F);
    /** A secret word near the digits it names, or a full Aadhaar or card number on its own. */
    private static final Pattern SHARED = Pattern.compile(SECRET + "[^\\d]{0,24}\\d{4,8}|\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}(?:[ -]?\\d{4})?\\b", F);
    private static final Pattern PAY_FIRST_MONEY = Pattern.compile("(advance|deposit|registration|\\bfee\\b|\\bfees\\b|"
            + "transport charge|\\bjama\\b|peshgi|एडवांस|जमा|पेशगी|फ़ीस|फीस|অগ্রিম|জমা|ফি)", F);
    private static final Pattern PAY_FIRST_ASK = Pattern.compile("(\\byou\\b.{0,24}\\b(pay|send|deposit|transfer)\\b|"
            + "\\b(pay|send|deposit|transfer)\\b.{0,20}\\b(first|before)\\b|\\baap\\w*\\b.{0,40}\\b(bhej|jama|bharo|bhar|kar|de)\\w*|"
            + "\\bpehle\\b.{0,30}\\b(bhej|jama|bhar|de)\\w*|आप.{0,40}(भेज|जमा|भर|दीजिए|दें)|पहले.{0,30}(भेज|जमा|भर|दीजिए|दें)|"
            + "আপনি.{0,30}(পাঠান|জমা|দিন)|আগে.{0,30}(পাঠান|জমা|দিন))", F);
    private static final Pattern OFFERS_TO_PAY = Pattern.compile("(\\bi\\b.{0,12}\\b(will|can|'ll)?\\s*pay\\b|\\bi'll pay\\b|\\bwe\\b.{0,12}\\bpay\\b|"
            + "\\bmain\\b.{0,20}\\b(dunga|doonga|de dunga)\\b|\\bhum\\b.{0,20}\\b(denge|de denge)\\b|मैं.{0,20}दूँगा|मैं.{0,20}दूंगा|हम.{0,20}देंगे|"
            + "আমি.{0,20}দেব|(\\badvance\\b|\\bpeshgi\\b|\\bjama\\b).{0,16}\\b(dunga|doonga|karunga|denge|karenge)\\b|"
            + "(एडवांस|पेशगी|जमा).{0,16}(दूँगा|दूंगा|करूँगा|करूंगा|देंगे|करेंगे))", F);
    private static final Pattern THREAT = Pattern.compile("(\\bkill you\\b|\\bbeat you\\b|you will regret|maar dunga|maar doonga|"
            + "jaan se|dekh lunga|dekh loonga|मार दूँगा|मार दूंगा|जान से|देख लूँगा|देख लूंगा|মেরে ফেলব|দেখে নেব)", F);

    /** Scanning a QR code or approving a request, which never brings money in. */
    private static final Pattern PULL_PAYMENT = Pattern.compile("(\\bqr\\b|\\bscan\\b|\\bscanner\\b|क्यूआर|स्कैन|কিউআর|স্ক্যান|"
            + "\\bcollect request\\b|\\bapprove\\b.{0,24}\\b(request|collect|payment)\\b|(रिक्वेस्ट|अनुरोध).{0,24}(अप्रूव|स्वीकार)|अप्रूव)", F);
    private static final Pattern TO_RECEIVE = Pattern.compile("(\\breceive\\b|\\bget (the |your )?(money|payment|paid|advance)\\b|"
            + "\\bmilega\\b|\\bmilenge\\b|\\bmil ja\\w*\\b|\\baa ja\\w*\\b|\\baayega\\b|मिलेगा|मिलेंगे|मिल जाएग|आ जाएग|आएगा|পাবেন|পেয়ে যাবেন)", F);
    /** The buyer doing the scanning to pay the farmer is how a normal UPI payment works. */
    private static final Pattern SPEAKER_SCANS = Pattern.compile("(\\b(i|we)\\b.{0,16}\\b(scan|approve)\\b|\\b(main|hum)\\b.{0,24}\\bscan\\b|"
            + "मैं.{0,24}स्कैन|हम.{0,24}स्कैन|আমি.{0,24}স্ক্যান)", F);

    /** Everyday ways to settle a sale after the goods move. */
    private static final Pattern ORDINARY_TERMS = Pattern.compile("(\\b(pay|pays|paying|payment|paid|cash|money|upi|transfer)\\b.{0,30}"
            + "\\b(on|after|at|upon)\\s+(the\\s+)?(delivery|pickup|pick-up|collection|loading|weighing|receipt)\\b|\\bcod\\b|"
            + "\\b(after|once)\\b.{0,30}\\b(delivery|pickup|goods|receive|received)\\b.{0,30}\\b(pay|payment|cash|upi|transfer)\\b|"
            + "\\b(delivery|pickup)\\s*(pe|par|ke\\s*baad)\\b.{0,24}\\b(paisa|paise|payment|cash)\\b|"
            + "\\b(paisa|paise|payment|cash)\\b.{0,24}\\b(delivery|pickup)\\s*(pe|par|ke\\s*baad)\\b|"
            + "\\bmaal\\b.{0,24}\\b(pe|par|ke\\s*baad)\\b.{0,24}\\b(paisa|paise|payment|cash)\\b|"
            + "\\b(cash|nakad)\\b.{0,12}\\b(dunga|doonga|denge)\\b|"
            + "\\bbaad\\s*(me|mein)\\b.{0,24}\\b(bhej|de|kar)\\w*\\s*(dunga|doonga|denge)\\b|"
            + "\\b(token|bayana)\\b.{0,24}\\b(dunga|doonga|denge)\\b|"
            + "(डिलीवरी|पिकअप)\\s*(पे|पर|के\\s*बाद).{0,24}(पैसा|पैसे|पेमेंट|भुगतान|नकद|कैश)|"
            + "(पैसा|पैसे|पेमेंट|भुगतान|नकद|कैश).{0,24}(डिलीवरी|पिकअप)\\s*(पे|पर|के\\s*बाद)|"
            + "माल.{0,24}(पे|पर|के\\s*बाद).{0,24}(पैसा|पैसे|पेमेंट|भुगतान|नकद|कैश)|"
            + "(नकद|कैश).{0,16}(दूँगा|दूंगा|देंगे)|बाद\\s*में.{0,24}(दूँगा|दूंगा|देंगे)|"
            + "(ডেলিভারি|মাল).{0,24}(পরে|পেয়ে).{0,24}(টাকা|পেমেন্ট))", F);
    /** Any of these beside ordinary terms means the turn still needs the model's reading. */
    private static final Pattern RISK_WORD = Pattern.compile(SECRET + "|\\b(qr|scan\\w*|collect|approve|aadhaar|aadhar|card|code|link|"
            + "advance|deposit|registration|fee|fees|first|before|pehle|peshgi|jama)\\b|क्यूआर|स्कैन|कोड|कार्ड|आधार|लिंक|एडवांस|पहले|जमा|"
            + "पेशगी|फ़ीस|फीस|কিউআর|স্ক্যান|কোড|কার্ড|আধার|লিংক|অগ্রিম|আগে|জমা", F);

    private static final Pattern LONG_DIGITS = Pattern.compile("\\d(?:[ -]?\\d){3,}");

    private ScamPatterns() {}

    /** Cautions a buyer's words earn. */
    static Set<Caution> buyer(String text) {
        Set<Caution> found = EnumSet.noneOf(Caution.class);
        if (SECRET_WORD.matcher(text).find() && ASK.matcher(text).find()) found.add(Caution.SECRET_REQUEST);
        if (PULL_PAYMENT.matcher(text).find() && TO_RECEIVE.matcher(text).find() && !SPEAKER_SCANS.matcher(text).find()) {
            found.add(Caution.SECRET_REQUEST);
        }
        if (PAY_FIRST_MONEY.matcher(text).find() && PAY_FIRST_ASK.matcher(text).find()
                && !OFFERS_TO_PAY.matcher(text).find()) {
            found.add(Caution.ADVANCE_PAYMENT);
        }
        if (THREAT.matcher(text).find()) found.add(Caution.THREAT);
        return found;
    }

    /**
     * The model's cautions this turn's own words can bear. A turn that only settles how the goods get paid for cannot
     * be a request for a secret or for money first, however the model read it. Nothing the plain-code screen finds
     * passes through here.
     */
    static Set<Caution> supported(Set<Caution> model, String text) {
        Set<Caution> kept = EnumSet.noneOf(Caution.class);
        kept.addAll(model);
        if (ORDINARY_TERMS.matcher(text).find() && !RISK_WORD.matcher(text).find()) {
            kept.remove(Caution.SECRET_REQUEST);
            kept.remove(Caution.ADVANCE_PAYMENT);
        }
        return kept;
    }

    /** The turn with its digits masked when it seems to carry a secret, for the transcript, the console and the model. */
    public static String masked(String text) {
        return SHARED.matcher(text).find() ? LONG_DIGITS.matcher(text).replaceAll("****") : text;
    }

    /** True when the farmer seems to have said a secret of their own aloud. */
    static boolean farmerSharedSecret(String text) {
        return SHARED.matcher(text).find();
    }
}
