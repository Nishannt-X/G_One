package com.example.g_one.advisory;

import com.example.g_one.agent.mediation.ScamPatterns;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic regex/keyword detector for suspicious negotiation patterns in English, Hindi and Hinglish.
 * Covers QR-code scamming, UPI PIN harvesting, OTP sharing, advance-fee requests, and artificial urgency pressure.
 */
public final class ScamShield {

    public enum Category {
        QR_CODE,
        UPI_PIN,
        OTP,
        ADVANCE_FEE,
        URGENCY_PRESSURE,
        NONE
    }

    public enum Severity {
        CRITICAL,
        HIGH,
        MEDIUM,
        NONE
    }

    public record Detection(
            boolean detected,
            Category category,
            Severity severity,
            String whisperTextEn,
            String whisperTextHi,
            String matchedPattern
    ) {
        public static Detection none() {
            return new Detection(false, Category.NONE, Severity.NONE, "", "", "");
        }
    }

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    // QR-code scan scams: asking farmer to scan a QR code to "receive" money
    private static final Pattern QR_SCAN = Pattern.compile(
            "(\\bqr\\b|q\\.r|क्यूआर).{0,25}(\\bscan\\b|स्कैन|bhej|dekh)|(\\bscan\\b|स्कैन).{0,25}(\\bqr\\b|q\\.r|क्यूआर|code|कोड)", F);

    // UPI PIN requests or prompts to enter PIN to "receive" payment
    private static final Pattern UPI_PIN_REQ = Pattern.compile(
            "(\\bupi\\s*pin\\b|\\bpin\\b|पिन).{0,30}(\\benter\\b|\\btype\\b|\\bdaal|\\bdaalo|\\bdal do\\b|\\bdijiye\\b|\\bbata|\\bshare\\b|दर्ज|डालो|बताओ|दीजिए|लगाओ)|"
            + "(\\benter\\b|\\btype\\b|\\bdaal|\\bdaalo|दर्ज|डालो).{0,30}(\\bupi\\s*pin\\b|\\bpin\\b|पिन)", F);

    // OTP requests
    private static final Pattern OTP_REQ = Pattern.compile(
            "(\\botp\\b|o\\.t\\.p|one\\s*time\\s*password|ओटीपी).{0,30}(\\btell\\b|\\bgive\\b|\\bshare\\b|\\bsend\\b|\\bbata|\\bbhej|\\bbolo|\\bboliye|\\bde do|बता|भेज|बोल|दीजिए|दो)|"
            + "(\\btell\\b|\\bgive\\b|\\bshare\\b|\\bsend\\b|\\bbata|\\bbhej|\\bbolo|\\bboliye|\\bde do|बता|भेज|बोल|दीजिए|दो).{0,30}(\\botp\\b|o\\.t\\.p|one\\s*time\\s*password|ओटीपी)", F);

    // Advance-fee / "send money first"
    private static final Pattern ADVANCE_FEE = Pattern.compile(
            "(\\badvance\\b|deposit|registration|token|booking\\s*(fee)?|\\bfee\\b|\\bfees\\b|transport charge|\\bjama\\b|peshgi|एडवांस|जमा|पेशगी|फ़ीस|फीस|टोकन).{0,40}"
            + "(\\bpay\\b|\\bsend\\b|\\btransfer\\b|\\bpehle\\b|\\bbhej|\\bjama\\b|\\bbharo|\\bde do|पहले|भेज|जमा|दीजिए|करो)|"
            + "(\\bpehle\\b|before|पहले).{0,40}(\\bpay\\b|\\bsend\\b|\\btransfer\\b|\\bbhej|\\bjama\\b|\\bdeposit\\b|\\badvance\\b|\\brup\\w*|\\bpaise\\b|रुपये|पैसे|भेज|जमा|डिपॉजिट|एडवांस)", F);

    // Offers to pay money to the farmer (should not trigger advance fee if buyer is simply paying the farmer)
    private static final Pattern BUYER_PAYING_FARMER = Pattern.compile(
            "(\\bi\\b.{0,12}\\b(will|can|'ll)?\\s*pay\\b|\\bmain\\b.{0,20}\\b(dunga|doonga|de dunga)\\b|\\bhum\\b.{0,20}\\b(denge|de denge)\\b|मैं.{0,20}दूँगा|मैं.{0,20}दूंगा|हम.{0,20}देंगे)", F);

    // Urgency pressure
    private static final Pattern URGENCY = Pattern.compile(
            "(\\bhurry\\b|\\burgent\\b|\\bimmediately\\b|\\bright now\\b|within\\s*\\d+\\s*min|expires?\\s*in|deal\\s*(will be)?\\s*cancel|offer\\s*cancel|"
            + "jaldi\\s*(karo|kijiye|faisla)|turant|abhi\\s*(ke\\s*abhi|karo)|warna\\s*cancel|khatam\\s*ho\\s*jayega|"
            + "जल्दी\\s*(करो|कीजिए)|तुरंत|अभी\\s*के\\s*अभी|वर्ना\\s*रद्द|ऑफर\\s*खत्म)", F);

    public Detection screen(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return Detection.none();
        }
        String text = rawText.trim();

        // 1. QR code scan
        Matcher qr = QR_SCAN.matcher(text);
        if (qr.find()) {
            return new Detection(
                    true,
                    Category.QR_CODE,
                    Severity.CRITICAL,
                    "Caution: Scanning a QR code sends money out of your account. You never need to scan a QR code to receive payment.",
                    "सावधान: क्यूआर कोड स्कैन करने से आपके खाते से पैसे कटते हैं। पैसे प्राप्त करने के लिए कभी भी क्यूआर कोड स्कैन न करें।",
                    qr.group()
            );
        }

        // 2. UPI PIN request
        Matcher pin = UPI_PIN_REQ.matcher(text);
        if (pin.find()) {
            return new Detection(
                    true,
                    Category.UPI_PIN,
                    Severity.CRITICAL,
                    "Caution: Never enter or share your UPI PIN to receive money. A PIN is only used to send money.",
                    "सावधान: पैसे लेने के लिए यूपीआई पिन दर्ज करने की आवश्यकता नहीं होती। पिन केवल पैसे भेजने के लिए इस्तेमाल होता है।",
                    pin.group()
            );
        }

        // 3. OTP request
        Matcher otp = OTP_REQ.matcher(text);
        if (otp.find()) {
            return new Detection(
                    true,
                    Category.OTP,
                    Severity.CRITICAL,
                    "Caution: Never share your OTP with anyone. Legitimate buyers do not need an OTP to make a payment.",
                    "सावधान: अपना ओटीपी कभी किसी से साझा न करें। वैध खरीदार को भुगतान करने के लिए ओटीपी की ज़रूरत नहीं होती।",
                    otp.group()
            );
        }

        // 4. Advance fee / pay money first
        Matcher advance = ADVANCE_FEE.matcher(text);
        if (advance.find() && !BUYER_PAYING_FARMER.matcher(text).find()) {
            return new Detection(
                    true,
                    Category.ADVANCE_FEE,
                    Severity.HIGH,
                    "Caution: The buyer is asking you to pay money first. A crop seller does not pay fees or advances to receive payment.",
                    "सावधान: खरीदार आपसे पहले पैसे भेजने या शुल्क देने को कह रहा है। फसल विक्रेता को पहले भुगतान करने की आवश्यकता नहीं होती।",
                    advance.group()
            );
        }

        // 5. Urgency pressure
        Matcher urg = URGENCY.matcher(text);
        if (urg.find()) {
            return new Detection(
                    true,
                    Category.URGENCY_PRESSURE,
                    Severity.MEDIUM,
                    "Caution: High urgency pressure detected. Take time to decide calmly without rushing into terms.",
                    "सावधान: अत्यधिक जल्दबाजी का दबाव बनाया जा रहा है। दबाव में आए बिना सोच-समझकर निर्णय लें।",
                    urg.group()
            );
        }

        return Detection.none();
    }
}
