package com.example.g_one.advisory;

import com.example.g_one.agent.llm.SpokenNumbers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses mandi deduction terms ("5 kg per bag cut", dhalta, kata, moisture cut, commission, palledari)
 * and calculates the true net ₹/quintal realization and a strategic counter-offer.
 */
public class DeductionShield {

    public record DeductionItem(
            String name,
            String hindiName,
            double value,
            String unit,
            double rupeesPerQuintal,
            String rawMatch
    ) {}

    public record Calculation(
            double basePriceRupeesPerQuintal,
            double netPriceRupeesPerQuintal,
            double totalDeductionsRupeesPerQuintal,
            double totalWeightCutKgPerQuintal,
            List<DeductionItem> items,
            double suggestedCounterOfferRupeesPerQuintal,
            String suggestionEn,
            String suggestionHi
    ) {}

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    // "5 kg per bag cut", "2 kg kata", "3 kg dhalta", "ढलता 2 किलो", "5 किलो काटा"
    private static final Pattern PER_BAG_WEIGHT_CUT = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:kg|kilo|किलो)\\s*(?:per|prati|प्रति|/)?\\s*(?:bag|bori|katta|बोरी|कट्टा)?\\s*(?:cut|kata|dhalta|ढलता|काटा)|"
            + "(?:cut|kata|dhalta|ढलता|काटा)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:kg|kilo|किलो)|"
            + "(\\d+(?:\\.\\d+)?)\\s*(?:kg|kilo|किलो)\\s*(?:cut|kata|dhalta|ढलता|काटा)", F);

    // Dhalta percentage or unspecified
    private static final Pattern DHALTA_PCT = Pattern.compile(
            "(?:dhalta|ढलता)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:%|percent|प्रतिशत)|"
            + "(\\d+(?:\\.\\d+)?)\\s*(?:%|percent|प्रतिशत)\\s*(?:dhalta|ढलता)", F);

    // Moisture cut: "3% moisture cut", "100 rs moisture cut", "नमी कटौती 2%"
    private static final Pattern MOISTURE_PCT = Pattern.compile(
            "(?:moisture|nami|नमी)\\s*(?:cut|कटौती)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:%|percent|प्रतिशत)|"
            + "(\\d+(?:\\.\\d+)?)\\s*(?:%|percent|प्रतिशत)\\s*(?:moisture|nami|नमी)\\s*(?:cut|कटौती)?", F);

    private static final Pattern MOISTURE_RS = Pattern.compile(
            "(?:moisture|nami|नमी)\\s*(?:cut|कटौती)?\\s*(?:₹|rs|rupay|रुपये)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:₹|rs|rupay|रुपये|rupees)?", F);

    // Commission / Aarhat: "2% commission", "आढ़त 2 प्रतिशत"
    private static final Pattern COMMISSION_PCT = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:%|percent|प्रतिशत)\\s*(?:commission|arhat|aarhat|आढ़त|कमीशन)|"
            + "(?:commission|arhat|aarhat|आढ़त|कमीशन)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:%|percent|प्रतिशत)", F);

    // Palledari / Hamali: "20 rupay palledari per bag", "पल्लेदारी 20 रुपये प्रति बोरी"
    private static final Pattern PALLEDARI = Pattern.compile(
            "(?:palledari|hamali|पल्लेदारी|हमाली|मजदूरी)\\s*(?:₹|rs|rupay|rupees|रुपये)?\\s*(\\d+(?:\\.\\d+)?)(?:\\s*(?:₹|rs|rupay|rupees|रुपये))?(?:\\s*(?:per|prati|प्रति|/)?\\s*(?:bag|bori|katta|quintal|क्विंटल|बोरी|कट्टा))?|"
            + "(\\d+(?:\\.\\d+)?)\\s*(?:₹|rs|rupay|rupees|रुपये)\\s*(?:per|prati|प्रति|/)?\\s*(?:bag|bori|katta|quintal|क्विंटल|बोरी|कट्टा)?\\s*(?:palledari|hamali|पल्लेदारी|हमाली|मजदूरी)", F);

    public Calculation analyze(double basePrice, String text) {
        if (text == null || text.isBlank()) {
            return new Calculation(
                    basePrice, basePrice, 0.0, 0.0, List.of(), basePrice,
                    "No deductions mentioned. Quoted price is ₹" + Math.round(basePrice) + "/quintal net.",
                    "कोई कटौती नहीं बताई गई है। उद्धृत भाव " + SpokenNumbers.words(Math.round(basePrice), "hi-IN") + " रुपये प्रति क्विंटल शुद्ध है।"
            );
        }

        List<DeductionItem> items = new ArrayList<>();
        double totalWeightCutKg = 0.0;
        double totalRupeesDeduction = 0.0;

        // 1. Per bag weight cut (assuming standard 50kg bag -> 2 bags per quintal)
        Matcher mCut = PER_BAG_WEIGHT_CUT.matcher(text);
        if (mCut.find()) {
            String valStr = mCut.group(1) != null ? mCut.group(1) : (mCut.group(2) != null ? mCut.group(2) : mCut.group(3));
            double kgPerBag = Double.parseDouble(valStr);
            double kgPerQuintal = kgPerBag * 2.0; // 2 bags of 50kg in a quintal
            double rsCut = (kgPerQuintal / 100.0) * basePrice;
            totalWeightCutKg += kgPerQuintal;
            totalRupeesDeduction += rsCut;
            items.add(new DeductionItem(
                    "Weight Cut (Kata/Dhalta)",
                    "वजन कटौती (काटा/ढलता)",
                    kgPerBag,
                    "kg/bag",
                    rsCut,
                    mCut.group()
            ));
        }

        // 2. Dhalta % if distinct from per-bag cut
        Matcher mDhalta = DHALTA_PCT.matcher(text);
        if (mDhalta.find()) {
            String valStr = mDhalta.group(1) != null ? mDhalta.group(1) : mDhalta.group(2);
            double pct = Double.parseDouble(valStr);
            double kgPerQuintal = pct; // 1% of 100kg = 1kg
            double rsCut = (pct / 100.0) * basePrice;
            totalWeightCutKg += kgPerQuintal;
            totalRupeesDeduction += rsCut;
            items.add(new DeductionItem(
                    "Dhalta Cut",
                    "ढलता कटौती",
                    pct,
                    "%",
                    rsCut,
                    mDhalta.group()
            ));
        }

        // 3. Moisture cut
        Matcher mMoistPct = MOISTURE_PCT.matcher(text);
        if (mMoistPct.find()) {
            String valStr = mMoistPct.group(1) != null ? mMoistPct.group(1) : mMoistPct.group(2);
            double pct = Double.parseDouble(valStr);
            double rsCut = (pct / 100.0) * basePrice;
            totalRupeesDeduction += rsCut;
            items.add(new DeductionItem(
                    "Moisture Cut",
                    "नमी कटौती",
                    pct,
                    "%",
                    rsCut,
                    mMoistPct.group()
            ));
        } else {
            Matcher mMoistRs = MOISTURE_RS.matcher(text);
            if (mMoistRs.find()) {
                double rs = Double.parseDouble(mMoistRs.group(1));
                totalRupeesDeduction += rs;
                items.add(new DeductionItem(
                        "Moisture Cut",
                        "नमी कटौती",
                        rs,
                        "₹/quintal",
                        rs,
                        mMoistRs.group()
                ));
            }
        }

        // 4. Commission / Aarhat
        Matcher mComm = COMMISSION_PCT.matcher(text);
        if (mComm.find()) {
            String valStr = mComm.group(1) != null ? mComm.group(1) : mComm.group(2);
            double pct = Double.parseDouble(valStr);
            double rsCut = (pct / 100.0) * basePrice;
            totalRupeesDeduction += rsCut;
            items.add(new DeductionItem(
                    "Commission (Aarhat)",
                    "आढ़त / कमीशन",
                    pct,
                    "%",
                    rsCut,
                    mComm.group()
            ));
        }

        // 5. Palledari / Hamali
        Matcher mPal = PALLEDARI.matcher(text);
        if (mPal.find()) {
            String valStr = mPal.group(1) != null ? mPal.group(1) : mPal.group(2);
            double rawAmount = Double.parseDouble(valStr);
            String match = mPal.group().toLowerCase();
            // If specified per bag or amount is small (<= 25), count as per bag (2 bags/quintal)
            double rsPerQuintal = (match.contains("quintal") || match.contains("क्विंटल"))
                    ? rawAmount
                    : rawAmount * 2.0;
            totalRupeesDeduction += rsPerQuintal;
            items.add(new DeductionItem(
                    "Palledari / Hamali",
                    "पल्लेदारी / हमाली",
                    rawAmount,
                    match.contains("quintal") || match.contains("क्विंटल") ? "₹/quintal" : "₹/bag",
                    rsPerQuintal,
                    mPal.group()
            ));
        }

        double netPrice = Math.max(0.0, basePrice - totalRupeesDeduction);
        // Strategic counter-offer: reject unauthorized commission and dhalta; quote net price
        double suggestedCounter = Math.round(basePrice - (totalRupeesDeduction * 0.25)); // counter with at least 75% of deductions restored

        long baseRound = Math.round(basePrice);
        long netRound = Math.round(netPrice);
        long lossRound = Math.round(totalRupeesDeduction);
        long counterRound = Math.round(suggestedCounter);

        String suggEn = String.format(
                "Deductions reduce your ₹%d/quintal offer by ₹%d, leaving net ₹%d/quintal. Counter-offer ₹%d/quintal net with zero commission and zero dhalta.",
                baseRound, lossRound, netRound, counterRound
        );

        String baseWords = SpokenNumbers.words(baseRound, "hi-IN");
        String lossWords = SpokenNumbers.words(lossRound, "hi-IN");
        String netWords = SpokenNumbers.words(netRound, "hi-IN");
        String counterWords = SpokenNumbers.words(counterRound, "hi-IN");

        String suggHi = String.format(
                "कटौतियों के कारण आपका %s रुपये का भाव %s रुपये घटकर शुद्ध %s रुपये प्रति क्विंटल रह जाता है। बिना कमीशन और बिना ढलता के %s रुपये प्रति क्विंटल शुद्ध का जवाबी प्रस्ताव दें।",
                baseWords, lossWords, netWords, counterWords
        );

        return new Calculation(
                basePrice,
                netPrice,
                totalRupeesDeduction,
                totalWeightCutKg,
                Collections.unmodifiableList(items),
                suggestedCounter,
                suggEn,
                suggHi
        );
    }
}
