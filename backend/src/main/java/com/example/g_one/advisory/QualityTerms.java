package com.example.g_one.advisory;

import com.example.g_one.agent.llm.SpokenNumbers;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts agricultural quality parameters (moisture %, FAQ grade, variety, bag type) from spoken negotiation text.
 */
public class QualityTerms {

    public record ExtractedTerms(
            Double moisturePercent,
            String faqGrade,
            String variety,
            String bagType,
            String rawText,
            String summaryEn,
            String summaryHi
    ) {
        public boolean hasAny() {
            return moisturePercent != null || faqGrade != null || variety != null || bagType != null;
        }
    }

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    // Moisture %: "12% moisture", "moisture 14 percent", "नमी 13.5 प्रतिशत", "12% nami"
    private static final Pattern MOISTURE_PAT = Pattern.compile(
            "(?:moisture|nami|नमी)\\s*(?:is|level)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:%|percent|pratishat|प्रतिशत)?|"
            + "(\\d+(?:\\.\\d+)?)\\s*(?:%|percent|pratishat|प्रतिशत)\\s*(?:moisture|nami|नमी)", F);

    // FAQ Grade: FAQ, Non-FAQ, Grade A/B/1/2, Super, Medium, Under-grade
    private static final Pattern FAQ_GRADE_PAT = Pattern.compile(
            "\\b(non[-\\s]?faq|नॉन[-\\s]?एफएक्यू)\\b|"
            + "\\b(faq|एफएक्यू|fair average quality)\\b|"
            + "\\b(grade\\s*[ab12]|ग्रेड\\s*[ab12एबी])\\b|"
            + "\\b(super|सुपर|1st quality|first quality|उत्तम)\\b|"
            + "\\b(medium|मध्यम|2nd quality|second quality)\\b|"
            + "\\b(under[-\\s]?grade|rejection|कमज़ोर)\\b", F);

    // Variety extraction: Lokwan, Sharbati, Pusa 1121, Basmati, Garwa, Pol, Chipsona, etc.
    private static final Pattern VARIETY_PAT = Pattern.compile(
            "\\b(sharbati|शरबती)\\b|"
            + "\\b(lokwan|lok-1|लोकवन)\\b|"
            + "\\b(pusa\\s*1121|1121|पूसा\\s*1121)\\b|"
            + "\\b(basmati|बासमती)\\b|"
            + "\\b(sona\\s*masoori|सोना\\s*मसूरी)\\b|"
            + "\\b(sonalika|सोनालिका)\\b|"
            + "\\b(garwa|गरवा)\\b|"
            + "\\b(pol|rangda|पोल|रांगड़ा)\\b|"
            + "\\b(red onion|lal pyaz|लाल\\s*प्याज)\\b|"
            + "\\b(white onion|safed pyaz|सफेद\\s*प्याज)\\b|"
            + "\\b(chipsona|चिपसोना)\\b|"
            + "\\b(kufri\\s*(?:jyoti|pukhraj|bahar)?|पुखराज)\\b|"
            + "\\b(js\\s*(?:335|9560|2034)|सोयाबीन)\\b|"
            + "\\b(bt\\s*cotton|बीटी\\s*कपास)\\b|"
            + "\\b(desi|hybrid|हाइब्रिड|देसी)\\b", F);

    // Bag type: Jute bag / gunny / bori / bardana, plastic / HDPE / leno / katta, net bag, loose / dheri
    private static final Pattern BAG_PAT = Pattern.compile(
            "\\b(jute\\s*(?:bag)?|gunny\\s*(?:bag)?|bardana|bori|बारदाना|जूट\\s*(?:की\\s*बोरी)?|बोरी)\\b|"
            + "\\b(plastic\\s*(?:bag)?|hdpe|leno|katta|प्लास्टिक|कट्टा|लेनो)\\b|"
            + "\\b(net\\s*bag|mesh\\s*bag|जालीदार\\s*(?:बोरी|बैग))\\b|"
            + "\\b(loose|bulk|dheri|खुला|ढेरी|ट्रॉली)\\b", F);

    public ExtractedTerms extract(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ExtractedTerms(null, null, null, null, "", "No quality terms detected.", "कोई गुणवत्ता शर्तें नहीं मिलीं।");
        }
        String text = raw.trim();

        Double moisture = null;
        Matcher mMoist = MOISTURE_PAT.matcher(text);
        if (mMoist.find()) {
            String valStr = mMoist.group(1) != null ? mMoist.group(1) : mMoist.group(2);
            moisture = Double.parseDouble(valStr);
        }

        String faqGrade = null;
        Matcher mFaq = FAQ_GRADE_PAT.matcher(text);
        if (mFaq.find()) {
            String m = mFaq.group().toLowerCase(Locale.ROOT);
            if (m.contains("non") || m.contains("नॉन")) {
                faqGrade = "Non-FAQ";
            } else if (m.contains("faq") || m.contains("एफएक्यू") || m.contains("fair average")) {
                faqGrade = "FAQ";
            } else if (m.contains("grade a") || m.contains("ग्रेड a") || m.contains("ग्रेड ए")) {
                faqGrade = "Grade A";
            } else if (m.contains("grade b") || m.contains("ग्रेड b") || m.contains("ग्रेड बी")) {
                faqGrade = "Grade B";
            } else if (m.contains("super") || m.contains("सुपर") || m.contains("1st") || m.contains("उत्तम")) {
                faqGrade = "Super";
            } else if (m.contains("medium") || m.contains("मध्यम") || m.contains("2nd")) {
                faqGrade = "Medium";
            } else if (m.contains("under") || m.contains("rejection") || m.contains("कमज़ोर")) {
                faqGrade = "Under-grade";
            } else {
                faqGrade = mFaq.group();
            }
        }

        String variety = null;
        Matcher mVar = VARIETY_PAT.matcher(text);
        if (mVar.find()) {
            String m = mVar.group().toLowerCase(Locale.ROOT);
            if (m.contains("sharbati") || m.contains("शरबती")) variety = "Sharbati";
            else if (m.contains("lokwan") || m.contains("lok-1") || m.contains("लोकवन")) variety = "Lokwan";
            else if (m.contains("1121") || m.contains("पूसा")) variety = "Pusa 1121";
            else if (m.contains("basmati") || m.contains("बासमती")) variety = "Basmati";
            else if (m.contains("sona masoori") || m.contains("सोना")) variety = "Sona Masoori";
            else if (m.contains("sonalika") || m.contains("सोनालिका")) variety = "Sonalika";
            else if (m.contains("garwa") || m.contains("गरवा")) variety = "Garwa";
            else if (m.contains("pol") || m.contains("rangda") || m.contains("पोल")) variety = "Pol/Rangda";
            else if (m.contains("red onion") || m.contains("lal pyaz") || m.contains("लाल")) variety = "Red Onion";
            else if (m.contains("white onion") || m.contains("safed pyaz") || m.contains("सफेद")) variety = "White Onion";
            else if (m.contains("chipsona") || m.contains("चिपसोना")) variety = "Chipsona";
            else if (m.contains("pukhraj") || m.contains("पुखराज") || m.contains("kufri")) variety = "Kufri/Pukhraj";
            else if (m.contains("js") || m.contains("सोयाबीन")) variety = "JS Soybean";
            else if (m.contains("bt") || m.contains("बीटी")) variety = "Bt Cotton";
            else if (m.contains("hybrid") || m.contains("हाइब्रिड")) variety = "Hybrid";
            else if (m.contains("desi") || m.contains("देसी")) variety = "Desi";
            else variety = mVar.group();
        }

        String bagType = null;
        Matcher mBag = BAG_PAT.matcher(text);
        if (mBag.find()) {
            String m = mBag.group().toLowerCase(Locale.ROOT);
            if (m.contains("jute") || m.contains("gunny") || m.contains("bardana") || m.contains("bori")
                    || m.contains("जूट") || m.contains("बारदाना") || m.contains("बोरी")) {
                bagType = "Jute Bag (Bori)";
            } else if (m.contains("plastic") || m.contains("hdpe") || m.contains("katta")
                    || m.contains("प्लास्टिक") || m.contains("कट्टा") || m.contains("लेनो")) {
                bagType = "Plastic/HDPE Bag (Katta)";
            } else if (m.contains("net") || m.contains("mesh") || m.contains("जालीदार")) {
                bagType = "Net/Mesh Bag";
            } else if (m.contains("loose") || m.contains("bulk") || m.contains("dheri")
                    || m.contains("खुला") || m.contains("ढेरी") || m.contains("ट्रॉली")) {
                bagType = "Loose / Bulk (Trolley)";
            } else {
                bagType = mBag.group();
            }
        }

        String summaryEn = buildSummaryEn(moisture, faqGrade, variety, bagType);
        String summaryHi = buildSummaryHi(moisture, faqGrade, variety, bagType);

        return new ExtractedTerms(moisture, faqGrade, variety, bagType, text, summaryEn, summaryHi);
    }

    private String buildSummaryEn(Double moisture, String faqGrade, String variety, String bagType) {
        StringBuilder sb = new StringBuilder("Quality terms: ");
        boolean first = true;
        if (variety != null) {
            sb.append("Variety: ").append(variety);
            first = false;
        }
        if (faqGrade != null) {
            if (!first) sb.append(", ");
            sb.append("Grade: ").append(faqGrade);
            first = false;
        }
        if (moisture != null) {
            if (!first) sb.append(", ");
            sb.append("Moisture: ").append(moisture).append("%");
            first = false;
        }
        if (bagType != null) {
            if (!first) sb.append(", ");
            sb.append("Packing: ").append(bagType);
            first = false;
        }
        if (first) {
            return "No specific quality terms identified.";
        }
        sb.append(".");
        return sb.toString();
    }

    private String buildSummaryHi(Double moisture, String faqGrade, String variety, String bagType) {
        StringBuilder sb = new StringBuilder("गुणवत्ता विवरण: ");
        boolean first = true;
        if (variety != null) {
            sb.append("किस्म: ").append(variety);
            first = false;
        }
        if (faqGrade != null) {
            if (!first) sb.append(", ");
            sb.append("ग्रेड: ").append(faqGrade);
            first = false;
        }
        if (moisture != null) {
            if (!first) sb.append(", ");
            long roundMoist = Math.round(moisture);
            sb.append("नमी: ").append(SpokenNumbers.words(roundMoist, "hi-IN")).append(" प्रतिशत");
            first = false;
        }
        if (bagType != null) {
            if (!first) sb.append(", ");
            sb.append("पैकिंग: ").append(bagType);
            first = false;
        }
        if (first) {
            return "कोई विशिष्ट गुणवत्ता शर्तें दर्ज नहीं हैं।";
        }
        sb.append("।");
        return sb.toString();
    }
}
