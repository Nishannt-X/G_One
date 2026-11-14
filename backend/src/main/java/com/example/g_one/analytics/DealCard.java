package com.example.g_one.analytics;

import com.example.g_one.agent.tools.buyers.Buyer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deal card a record keeps, built only from constrained values: a crop and market from the catalog (else "other"),
 * a bounded quantity and price with a unit from a closed list, pickup and payment mapped to closed kinds with fixed
 * labels, and a buyer only by directory id. Spoken text is read here to choose a value and is never stored; a value
 * that cannot be read safely is left out. Text with a phone-like run of digits, an email address or a link is never
 * read at all.
 */
final class DealCard {
    /** Largest quantity kept, in its own unit. */
    static final BigDecimal MAX_QUANTITY = new BigDecimal("1000000");
    /** Largest price kept, in rupees. */
    static final BigDecimal MAX_PRICE = new BigDecimal("10000000");

    /** Seven or more digits, with or without spaces, dashes, dots or brackets between them (any script's digits). */
    private static final Pattern PHONE_LIKE = Pattern.compile("\\d(?:[\\s\\-().+/]*\\d){6,}",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+");
    private static final Pattern LINK = Pattern.compile(
            "(?i)(https?:|www\\.|://|\\b[a-z0-9-]+\\.(com|in|org|net|io|co|me|info|app|ly)\\b)");
    private static final Pattern QUANTITY = Pattern.compile("^\\s*(.+?)\\s*([\\p{L}\\p{M}.]+)\\s*$");
    private static final Pattern DIGITS = Pattern.compile("\\d{1,7}(?:,\\d{2,3})*(?:\\.\\d{1,3})?");
    /** The combined pickup field as the deal desk writes it: "Monday at the farm; payment on delivery". */
    private static final Pattern PAYMENT_PART = Pattern.compile("(?:^|;\\s*)payment\\s+(.+)$", Pattern.DOTALL);

    private DealCard() {}

    /** A unit a quantity or price may be in. {@code kg} is its weight in kilograms, or null for counted units. */
    enum Unit {
        KG("kg", BigDecimal.ONE), QUINTAL("quintal", BigDecimal.valueOf(100)), TONNE("tonne", BigDecimal.valueOf(1000)),
        BAG("bag", null), DOZEN("dozen", null), PIECE("piece", null);

        final String wire;
        final BigDecimal kg;

        Unit(String wire, BigDecimal kg) {
            this.wire = wire;
            this.kg = kg;
        }
    }

    private static final Map<String, Unit> UNITS = new LinkedHashMap<>();

    static {
        units(Unit.KG, "kg", "kgs", "kilo", "kilos", "kilogram", "kilograms", "किलो", "किलोग्राम", "কেজি", "কিলো",
                "கிலோ", "ಕೆಜಿ", "ಕಿಲೋ");
        units(Unit.QUINTAL, "quintal", "quintals", "qtl", "qtls", "q", "क्विंटल", "कुंतल", "কুইন্টাল", "குவிண்டால்",
                "ಕ್ವಿಂಟಾಲ್");
        units(Unit.TONNE, "ton", "tons", "tonne", "tonnes", "टन", "টন", "டன்", "ಟನ್");
        units(Unit.BAG, "bag", "bags", "bori", "boriyan", "बोरी", "बोरा", "বস্তা", "மூட்டை", "ಚೀಲ");
        units(Unit.DOZEN, "dozen", "dozens", "darjan", "दर्जन", "ডজন", "டஜன்", "ಡಜನ್");
        units(Unit.PIECE, "piece", "pieces", "pcs", "pc", "nag", "नग", "पीस", "পিস", "துண்டு", "ಪೀಸ್");
    }

    private static void units(Unit unit, String... names) {
        for (String n : names) UNITS.put(n, unit);
    }

    /** The unit named, if it is on the list. */
    static Optional<Unit> unit(String spoken) {
        if (spoken == null) return Optional.empty();
        String k = norm(spoken).replaceAll("[.\\s]+$", "");
        return Optional.ofNullable(UNITS.get(k));
    }

    /** A bounded positive amount in a listed unit. */
    record Quantity(BigDecimal value, Unit unit) {
        String label() {
            return value.stripTrailingZeros().toPlainString() + " " + unit.wire;
        }
    }

    /** "10 quintal", "ten quintals", "१० क्विंटल"; null when it is not a bounded amount in a listed unit. */
    static Quantity quantity(String spoken) {
        if (spoken == null || spoken.isBlank() || unsafe(spoken)) return null;
        Matcher m = QUANTITY.matcher(asciiDigits(spoken));
        if (!m.matches()) return null;
        Optional<Unit> unit = unit(m.group(2));
        if (unit.isEmpty()) return null;
        BigDecimal value;
        String amount = m.group(1).strip();
        if (DIGITS.matcher(amount).matches()) {
            value = new BigDecimal(amount.replace(",", ""));
        } else {
            Long words = numberWords(amount);
            if (words == null) return null;
            value = BigDecimal.valueOf(words);
        }
        if (value.signum() <= 0 || value.compareTo(MAX_QUANTITY) > 0) return null;
        return new Quantity(value, unit.get());
    }

    /** Where the goods change hands. */
    enum Pickup {
        BUYER_COLLECTS("Buyer collects"), FARMER_DELIVERS("Farmer delivers"), AT_FARM("At the farm"),
        OTHER("Other arrangement"), UNSPECIFIED("Pickup not stated");

        final String label;

        Pickup(String label) {
            this.label = label;
        }

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** How the farmer is paid. */
    enum Payment {
        ON_DELIVERY("on delivery"), ADVANCE("in advance"), PARTIAL_ADVANCE("part in advance"),
        DAYS_AFTER("some days after delivery"), UPI("by UPI"), CASH("in cash"), OTHER("other terms"),
        UNSPECIFIED("not stated");

        final String label;

        Payment(String label) {
            this.label = label;
        }

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    // Keyword lists in English, romanised Hindi and Bengali, Hindi, Bengali, Tamil and Kannada. The first kind whose
    // list matches wins, so the more specific kinds come first.
    private static final Map<Pickup, List<String>> PICKUP_WORDS = new LinkedHashMap<>();
    private static final Map<Payment, List<String>> PAYMENT_WORDS = new LinkedHashMap<>();

    static {
        PICKUP_WORDS.put(Pickup.BUYER_COLLECTS, List.of("buyer collects", "collect", "collects", "pick up", "picks up",
                "pickup", "lift", "uthayega", "utha lega", "le jayega", "le jaayega", "lene aayega", "gaadi bhejega",
                "niye jabe", "tule nebe", "उठाएगा", "उठा लेगा", "ले जाएगा", "लेने आएगा", "गाड़ी भेजेगा", "নিয়ে যাবে",
                "তুলে নেবে", "গাড়ি পাঠাবে", "எடுத்துக்கொள்வார்", "எடுத்துச் செல்வார்", "ಕೊಂಡೊಯ್ಯುತ್ತಾರೆ",
                "ತೆಗೆದುಕೊಂಡು ಹೋಗುತ್ತಾರೆ"));
        PICKUP_WORDS.put(Pickup.FARMER_DELIVERS, List.of("deliver", "delivers", "bring", "brings", "drop", "mandi",
                "market yard", "warehouse", "godown", "pahuncha", "pahunchayenge", "le aaunga", "le aayenge",
                "pouchhe debo", "niye asbo", "पहुंचा", "पहुँचा", "ले आऊंगा", "ले आएंगे", "मंडी", "गोदाम", "পৌঁছে দেব",
                "নিয়ে আসব", "মান্ডি", "গুদাম", "கொண்டு வருவேன்", "சந்தை", "ಮಂಡಿ", "ತಂದು ಕೊಡುತ್ತೇನೆ"));
        PICKUP_WORDS.put(Pickup.AT_FARM, List.of("farm", "field", "khet", "gaon", "village", "khamar", "jomi", "खेत",
                "गांव", "गाँव", "ক্ষেত", "খেত", "জমি", "গ্রাম", "வயல்", "பண்ணை", "கிராமம்", "ಹೊಲ", "ತೋಟ", "ಗ್ರಾಮ"));

        PAYMENT_WORDS.put(Payment.UPI, List.of("upi", "gpay", "google pay", "phonepe", "phone pe", "paytm", "online",
                "bank transfer", "neft", "imps", "यूपीआई", "ऑनलाइन", "ইউপিআই", "অনলাইন", "ஆன்லைன்", "யுபிஐ",
                "ಆನ್‌ಲೈನ್", "ಯುಪಿಐ"));
        PAYMENT_WORDS.put(Payment.PARTIAL_ADVANCE, List.of("partial", "part advance", "half advance", "some advance",
                "token", "aadha", "adha", "aadhi", "kuch advance", "kichu agrim", "आधा", "आधी", "कुछ अग्रिम", "आंशिक",
                "টোকেন", "আধা", "কিছু অগ্রিম", "பாதி", "ಅರ್ಧ"));
        PAYMENT_WORDS.put(Payment.ADVANCE, List.of("advance", "upfront", "agrim", "peshgi", "अग्रिम", "एडवांस", "পেশগি",
                "অগ্রিম", "முன்பணம்", "அட்வான்ஸ்", "ಮುಂಗಡ", "ಅಡ್ವಾನ್ಸ್"));
        PAYMENT_WORDS.put(Payment.DAYS_AFTER, List.of("day", "days", "week", "weeks", "credit", "din baad", "din me",
                "hafte", "hafta", "udhar", "din pore", "दिन", "हफ्ते", "हफ़्ते", "सप्ताह", "उधार", "দিন", "সপ্তাহ",
                "বাকি", "நாள்", "நாட்கள்", "வாரம்", "ದಿನ", "ವಾರ"));
        PAYMENT_WORDS.put(Payment.ON_DELIVERY, List.of("delivery", "on pickup", "on collection", "at pickup", "spot",
                "on the spot", "turant", "haathon haath", "hate hate", "डिलीवरी", "तुरंत", "हाथों हाथ", "मौके पर",
                "ডেলিভারি", "হাতে হাতে", "সঙ্গে সঙ্গে", "டெலிவரி", "உடனே", "ಡೆಲಿವರಿ", "ತಕ್ಷಣ"));
        PAYMENT_WORDS.put(Payment.CASH, List.of("cash", "nakad", "nagad", "nogod", "नकद", "नगद", "कैश", "নগদ", "ক্যাশ",
                "ரொக்கம்", "கேஷ்", "ನಗದು", "ಕ್ಯಾಶ್"));
    }

    /** The pickup kind; UNSPECIFIED when nothing was said, OTHER when unsure, null when the text is unsafe. */
    static Pickup pickup(String spoken) {
        if (spoken == null || spoken.isBlank()) return Pickup.UNSPECIFIED;
        if (unsafe(spoken)) return null;
        String t = norm(spoken);
        for (Map.Entry<Pickup, List<String>> e : PICKUP_WORDS.entrySet()) {
            if (anyWord(t, e.getValue())) return e.getKey();
        }
        return Pickup.OTHER;
    }

    /** The payment kind; UNSPECIFIED when nothing was said, OTHER when unsure, null when the text is unsafe. */
    static Payment payment(String spoken) {
        if (spoken == null || spoken.isBlank()) return Payment.UNSPECIFIED;
        if (unsafe(spoken)) return null;
        String t = norm(spoken);
        for (Map.Entry<Payment, List<String>> e : PAYMENT_WORDS.entrySet()) {
            if (anyWord(t, e.getValue())) return e.getKey();
        }
        return Payment.OTHER;
    }

    /** True for text holding a phone-like run of seven or more digits, an email address or a link. */
    static boolean unsafe(String s) {
        return s != null && (PHONE_LIKE.matcher(s).find() || EMAIL.matcher(s).find() || LINK.matcher(s).find());
    }

    /** The pickup and payment kinds read from the console's combined field; null kinds were unsafe text. */
    record Kinds(Pickup pickup, Payment payment) {}

    /** Splits "Monday at the farm; payment on delivery" and maps each part to its kind. The words are not kept. */
    static Kinds kinds(String pickupAndPayment) {
        String pickupPart = pickupAndPayment;
        String paymentPart = null;
        if (pickupAndPayment != null) {
            Matcher m = PAYMENT_PART.matcher(pickupAndPayment);
            if (m.find()) {
                paymentPart = m.group(1);
                pickupPart = pickupAndPayment.substring(0, m.start());
            }
        }
        return new Kinds(pickup(pickupPart), payment(paymentPart));
    }

    /**
     * Fills a stored deal card from the raw terms of a deal update. {@code pickupAndPayment} is the console's combined
     * field; it is read and dropped. Fields that cannot be stored safely are null.
     */
    static void fill(ObjectNode d, DealVocabulary vocab, String commodity, String market, String quantity,
                     BigDecimal price, String unit, String pickupAndPayment, String buyerId) {
        Kinds kinds = kinds(pickupAndPayment);
        fillClosed(d, vocab, commodity, market, quantity(quantity), price, unit, kinds.pickup(), kinds.payment(),
                buyerId);
    }

    /** Fills a stored deal card from values already reduced to closed kinds and a checked quantity. */
    static void fillClosed(ObjectNode d, DealVocabulary vocab, String commodity, String market, Quantity q,
                           BigDecimal price, String unit, Pickup pickup, Payment payment, String buyerId) {
        d.put("commodity", vocab.crop(commodity));
        d.putNull("variety"); // free words: never stored
        d.put("market", vocab.market(market));

        if (q != null) {
            d.put("quantity", q.label());
            d.put("quantityValue", q.value());
            d.put("quantityUnit", q.unit().wire);
        } else {
            d.putNull("quantity");
        }

        String priceUnit = priceUnit(unit);
        boolean priceOk = price != null && priceUnit != null && price.signum() > 0 && price.compareTo(MAX_PRICE) <= 0;
        if (priceOk) {
            d.put("price", DealTotal.plain(price));
            d.put("unit", priceUnit);
            d.put("currency", "INR");
            d.put("priceBasis", priceUnit.equals("total") ? "total" : "per_unit");
            d.put("basis", priceUnit.equals("total") ? "total" : "per " + priceUnit);
            d.put("total", DealTotal.of(q, price, priceUnit));
        } else {
            d.putNull("price");
            d.putNull("unit");
            d.putNull("total");
        }

        // A kind left out as unsafe is stored as null, so a later projection knows it was decided, not missing.
        if (pickup != null) d.put("pickupKind", pickup.wire());
        else d.putNull("pickupKind");
        if (payment != null) {
            d.put("paymentKind", payment.wire());
            d.put("payment", payment == Payment.UNSPECIFIED ? null : payment.label);
        } else {
            d.putNull("paymentKind");
        }
        d.put("pickup", pickupLabel(pickup, payment));

        Optional<Buyer> buyer = vocab.buyer(buyerId);
        if (buyer.isPresent()) {
            d.put("buyerId", buyer.get().id());
            d.put("buyerName", buyer.get().name());
        }
    }

    /** The fixed text the stored-call view shows, such as "At the farm; payment in cash", or null when nothing is known. */
    static String pickupLabel(Pickup pickup, Payment payment) {
        boolean hasPickup = pickup != null && pickup != Pickup.UNSPECIFIED;
        boolean hasPayment = payment != null && payment != Payment.UNSPECIFIED;
        if (!hasPickup && !hasPayment) return null;
        String p = hasPickup ? pickup.label : Pickup.UNSPECIFIED.label;
        return hasPayment ? p + "; payment " + payment.label : p;
    }

    /** "total", a listed unit's name, or null. */
    private static String priceUnit(String unit) {
        if (unit == null) return null;
        if (unit.strip().equalsIgnoreCase("total")) return "total";
        return unit(unit).map(u -> u.wire).orElse(null);
    }

    private static boolean anyWord(String text, List<String> words) {
        for (String w : words) {
            if (w.chars().allMatch(c -> c < 128)) {
                if (Pattern.compile("(?<![a-z])" + Pattern.quote(w) + "(?![a-z])").matcher(text).find()) return true;
            } else if (text.contains(stem(w))) {
                return true;
            }
        }
        return false;
    }

    /** A keyword without its final virama, so "வயல்" also matches "வயலில்" (a suffix replaces the virama). */
    private static String stem(String w) {
        if (w.length() < 3) return w;
        char last = w.charAt(w.length() - 1);
        boolean virama = last == '\u094D' || last == '\u09CD' || last == '\u0BCD' || last == '\u0CCD';
        return virama ? w.substring(0, w.length() - 1) : w;
    }

    private static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
    }

    /** Devanagari, Bengali, Tamil or Kannada digits as ASCII digits. */
    private static String asciiDigits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(c -> b.appendCodePoint(Character.isDigit(c) ? '0' + Character.digit(c, 10) : c));
        return b.toString();
    }

    private static final Map<String, Integer> SMALL = Map.ofEntries(
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5),
            Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10),
            Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("thirteen", 13), Map.entry("fourteen", 14),
            Map.entry("fifteen", 15), Map.entry("sixteen", 16), Map.entry("seventeen", 17), Map.entry("eighteen", 18),
            Map.entry("nineteen", 19), Map.entry("twenty", 20), Map.entry("thirty", 30), Map.entry("forty", 40),
            Map.entry("fifty", 50), Map.entry("sixty", 60), Map.entry("seventy", 70), Map.entry("eighty", 80),
            Map.entry("ninety", 90));
    private static final Map<String, Integer> LARGE = Map.of("thousand", 1_000, "lakh", 100_000, "lakhs", 100_000);

    /** "four thousand five hundred" as 4500, or null when the words are not a number. */
    static Long numberWords(String text) {
        long total = 0;
        long current = 0;
        boolean any = false;
        for (String w : text.toLowerCase(Locale.ROOT).split("[\\s-]+")) {
            if (w.isEmpty() || w.equals("and")) continue;
            any = true;
            if (SMALL.containsKey(w)) current += SMALL.get(w);
            else if (w.equals("hundred")) current = (current == 0 ? 1 : current) * 100;
            else if (LARGE.containsKey(w)) {
                total += (current == 0 ? 1 : current) * LARGE.get(w);
                current = 0;
            } else return null;
        }
        return any ? total + current : null;
    }
}
