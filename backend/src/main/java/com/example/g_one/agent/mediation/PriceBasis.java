package com.example.g_one.agent.mediation;

import com.example.g_one.agent.deal.ReplyGuard;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the price basis (per unit, total, or a price for a stated lot) and the currency from the speaker's own words,
 * independently of the model's extraction. A price whose basis or currency these words do not show is never assumed:
 * the caller holds it and asks. All matching runs on text whose numbers {@link NumberGuard} wrote as digits.
 */
final class PriceBasis {
    /**
     * The currency the words name. The policy, for a product whose callers are in India:
     * <ul>
     *   <li>FOREIGN: another currency is named anywhere (words in six languages, any currency symbol but ₹, or a rupee
     *       of another country such as "Pakistani rupees"), or a weak word (won, rand) sits beside the price. Never a
     *       rupee offer.</li>
     *   <li>UNRESOLVED: currency-like text that is not clearly rupees: Latin "taka", "tk", "BDT", "Bangladeshi", a word
     *       the code does not know right after the price ("2000 zorkmids"), or a three-letter code before it. A rupee
     *       offer then needs an explicit yes or "rupees"; a bare basis such as "per kg" does not settle it.</li>
     *   <li>RUPEE: a rupee word or ₹, and Bengali-script টাকা, which Bengali speakers in India say for rupees. Only when
     *       nothing FOREIGN or UNRESOLVED contradicts it.</li>
     *   <li>NONE: no currency text at all. The amount is held and the question names rupees, so a plain answer settles
     *       it.</li>
     * </ul>
     */
    enum Currency { RUPEE, FOREIGN, UNRESOLVED, NONE }

    /** A short reply to the basis or currency question. {@code basis} is null when the reply named none. */
    record Answer(String basis, boolean affirmed) {}

    private enum Kind { RATE, TOTAL, LOT }

    /** One basis expression: where it sits, and for a lot ("10 kilo ke") the quantity it names. */
    private record Expr(Kind kind, String unit, int start, int end, int lotStart, int lotEnd) {
        String basis() {
            return kind == Kind.TOTAL ? "total" : kind == Kind.RATE ? unit : "lot:" + unit;
        }
    }

    private static final String B = "(?<![\\p{L}\\p{M}])";
    private static final String E = "(?![\\p{L}\\p{M}])";
    private static final String NUM = "(?<![\\d.])(\\d+(?:\\.\\d+)?)(?![\\d.])";

    private static final String TOTAL = B + "(?:total|in all|in total|for everything|for all(?: of it)?|lump sum|sab ka|sabka"
            + "|sab milake|poore ka|pure ka|poore maal|ek saath|कुल|पूरे का|पूरे माल|सबका|सब मिलाकर|মোট|সব মিলিয়ে|மொத்தம்|ಒಟ್ಟು)" + E;
    private static final String UNIT = "(?:(?:quintal|qtl|kilogram|kilo|kg|tonne|ton|piece|dozen|bag|bori)s?|क्विंटल|कुंतल"
            + "|किलो|केजी|टन|বস্তা|কুইন্টাল|কিলো|কেজি|টন|குவிண்டால்|கிலோ|டன்|ಕ್ವಿಂಟಲ್|ಕ್ವಿಂಟಾಲ್|ಕಿಲೋ|ಕೆಜಿ|ಟನ್)";
    private static final String PER = "(?:per|each|a|an|prati|har|प्रति|हर|প্রতি|ஒவ்வொரு|ಪ್ರತಿ)";
    /** Tamil and Kannada say "for a kilo" with a case ending on the unit word itself. */
    private static final String DATIVE = "(?:கிலோவுக்கு|கிலோக்கு|குவிண்டாலுக்கு|டன்னுக்கு|ಕಿಲೋಗೆ|ಕೆಜಿಗೆ|ಕ್ವಿಂಟಲ್‌?ಗೆ"
            + "|ಕ್ವಿಂಟಾಲ್‌?ಗೆ|ಟನ್‌?ಗೆ|ಟನ್ನಿಗೆ)";
    private static final String RUPEE_LATIN = "(?:rupees?|rupaiya|rupaiye|rupaye|rupaya|rupay|rupya|rupiya|rupiye|rs|inr)";
    private static final String RUPEE_STEM = "(?:रुपये|रुपए|रुपया|रुपैया|रुपइया|रुपयों|টাকা|ரூபாய|ರೂಪಾಯ)";
    private static final String MONEY = "(?:₹|" + B + RUPEE_LATIN + "\\.?" + E + "|" + B + RUPEE_STEM + "[\\p{L}\\p{M}]*|" + B
            + "(?:रु|ரூ|ರೂ)\\.?" + E + ")";
    private static final Pattern MONEY_RE = Pattern.compile("(?iu)" + MONEY);
    private static final Pattern FOREIGN_RE = Pattern.compile("(?iu)(?:[\\p{Sc}&&[^₹]]|" + B + "(?:dollars?|dolar|usd|euros?|eur"
            + "|cents?|bucks?|pesos?|yen|jpy|yuan|renminbi|rmb|cny|dirhams?|dhs|aed|riyals?|rials?|ringgits?|myr|baht|thb"
            + "|rubles?|roubles?|liras?|lire|kroner|krone|krona|kronor|nok|sek|dkk|shekels?|ils|dinars?|francs?|chf|rupiah"
            + "|idr|pounds?|sterling|quid|gbp|sgd|aud|cad|nzd|hkd|zar|krw|pkr|npr|lkr|pakistani|nepali|nepalese"
            + "|sri\\s*lankan|lankan|mauritian)" + E + "|" + B + "(?:डॉलर|डालर|यूरो|पाउंड|पौंड|दिरहम|रियाल|पेसो|युआन|रूबल"
            + "|ডলার|ইউরো|পাউন্ড|দিরহাম|পেসো|டாலர|யூரோ|பவுண்ட|திர்ஹாம|ಡಾಲರ|ಯೂರೋ|ಪೌಂಡ|ದಿರ್ಹಾಮ)[\\p{L}\\p{M}]*)");
    /** Currency text that may not mean Indian rupees: the Latin spellings of Bangladesh's taka. */
    /** A rupee word, or currency text that may not be rupees: either one may sit between an amount and its basis. */
    private static final String CURRENCY_WORD = "(?:" + MONEY + "|" + B + "(?:takas?|tk|bdt)" + E + ")";
    private static final Pattern UNRESOLVED_RE = Pattern.compile("(?iu)" + B + "(?:takas?|tk|bdt|bangladeshi|bangladesh)" + E);
    /** Short words that name a currency only when they sit right beside the price. */
    private static final Set<String> WEAK_FOREIGN = Set.of("won", "rand", "rands");
    /** Latin words that may follow a price without naming a currency. Anything else there leaves the currency unresolved. */
    private static final Set<String> AFTER_PRICE = Set.of(("rupee rupees rupaiya rupaiye rupaye rupaya rupay rupya rupiya rupiye "
            + "rs inr per a an each total only for at to and or the is it its of in on from with plus more less max maximum "
            + "minimum min final fixed flat last best ok okay then now today tomorrow cash advance deal offer rate price bhav "
            + "bhaav daam dam sir ji bhai bhaiya hai hain ho h tak se ka ke ki me mein mai par pe de du dunga dungi doonga "
            + "denge dega degi dedo dena deta dete lunga lenge loonga lo le chalega chahiye milega hoga tha thi karo kar "
            + "kardo wala wale hi bhi sirf bas sab poora pura lakh lakhs lac hazaar hazar hajar thousand hundred crore crores "
            + "k l cr will would can could i we you my our your this that please am pm kg kgs kilo kilos kilogram kilograms "
            + "quintal quintals qtl qtls ton tons tonne tonnes bag bags bori boris piece pieces dozen dozens rice onion onions "
            + "wheat potato potatoes tomato tomatoes chawal pyaaz pyaz gehun aloo tamatar maal crop").split(" "));
    private static final Pattern CONDITIONAL = Pattern.compile("(?iu)(?<![\\p{L}])(?:if|unless|only|except|not|no|agar|sirf|nahi|nahin|mat|अगर|यदि|सिर्फ|नहीं|मत|যদি|শুধু|না|என்றால்|மட்டும்|ಇದ್ದರೆ|ಮಾತ್ರ)(?![\\p{L}])");

    private static final Pattern TOTAL_RE = Pattern.compile("(?iu)" + TOTAL);
    private static final Pattern PER_UNIT = Pattern.compile("(?iu)" + B + "(?:" + PER + "\\s*|/\\s*)(" + UNIT + ")" + E);
    private static final Pattern UNIT_PER = Pattern.compile("(?iu)" + B + "(" + UNIT + ")\\s*(?:প্রতি|পিছু|prati|peechhe|pichhe|पीछे)" + E);
    private static final Pattern MONEY_UNIT = Pattern.compile("(?iu)" + MONEY + "\\s+(" + UNIT + ")" + E
            + "(?!\\s*(?:ka|ke|ki|का|के|की)" + E + ")");
    /** "kilo ke 2000" is a rate; "10 kilo ke 2000" and "for 10 kg" are a price for that lot. */
    private static final Pattern UNIT_OF = Pattern.compile("(?iu)(?:" + NUM + "\\s*)?" + B + "(" + UNIT + ")\\s*(?:ka|ke|ki|का|के|की)" + E
            + "(?=\\s*(?:" + MONEY + ")?\\s*\\d)");
    /** "kilo ke hisaab se": by the kilo. */
    private static final Pattern BY_UNIT = Pattern.compile("(?iu)" + B + "(" + UNIT + ")\\s*(?:ke|के)\\s*(?:hisaab|hisab|हिसाब)\\s*(?:se|से)" + E);
    /** A reply that is only a unit word ("kilo", "quintal ka") answers "total, per kilo or per quintal?". */
    private static final Pattern BARE_UNIT = Pattern.compile("(?iu)^(" + UNIT + ")(?:\\s*(?:ka|ke|ki|का|के|की))?$");
    private static final Pattern DATIVE_RE = Pattern.compile("(?iu)(?:" + NUM + "\\s*)?" + B + "(" + DATIVE + ")");
    private static final Pattern FOR_LOT = Pattern.compile("(?iu)" + B + "for\\s+" + NUM + "\\s*(" + UNIT + ")" + E);
    private static final Pattern NUMBER = Pattern.compile(NUM);
    private static final Pattern QUANTITY_AFTER = Pattern.compile("(?iu)^\\s*(" + UNIT + ")" + E);
    private static final Pattern FILLER = Pattern.compile("(?iu)" + B + "(?:in|of|the|it|its|is|that|thats|price|rate|bhav|bhaav"
            + "|daam|dam|me|mein|hi|में|ही|भाव|दाम|হিসেবে|দাম)" + E);

    private PriceBasis() {}

    /** The currency the words name anywhere, without looking at which number is the price. */
    static Currency currency(String text) {
        return currency(text, null, null);
    }

    /** The currency the words name, also reading the words right beside {@code price} (see {@link Currency}). */
    static Currency currency(String text, String code, BigDecimal price) {
        if (text == null) return Currency.NONE;
        if (FOREIGN_RE.matcher(text).find()) return Currency.FOREIGN;
        Currency beside = price == null ? Currency.NONE : besidePrice(NumberGuard.digitized(text, code), plain(price));
        if (beside == Currency.FOREIGN) return Currency.FOREIGN;
        if (beside == Currency.UNRESOLVED || UNRESOLVED_RE.matcher(text).find()) return Currency.UNRESOLVED;
        return MONEY_RE.matcher(text).find() ? Currency.RUPEE : Currency.NONE;
    }

    private static final Pattern WORD_AFTER = Pattern.compile("^\\s*([A-Za-z]+)");
    private static final Pattern WORD_BEFORE = Pattern.compile("([A-Za-z]+)\\s*$");

    private static Currency besidePrice(String d, String price) {
        Matcher m = Pattern.compile("(?<![\\d.])" + Pattern.quote(price) + "(?![\\d.])").matcher(d);
        Currency out = Currency.NONE;
        while (m.find()) {
            Matcher after = WORD_AFTER.matcher(d.substring(m.end()));
            Matcher before = WORD_BEFORE.matcher(d.substring(0, m.start()));
            String next = after.find() ? after.group(1) : null;
            String prev = before.find() ? before.group(1) : null;
            if ((next != null && WEAK_FOREIGN.contains(next.toLowerCase(Locale.ROOT)))
                    || (prev != null && WEAK_FOREIGN.contains(prev.toLowerCase(Locale.ROOT)))) {
                return Currency.FOREIGN;
            }
            if (next != null && !AFTER_PRICE.contains(next.toLowerCase(Locale.ROOT))) out = Currency.UNRESOLVED;
            if (prev != null && prev.matches("[A-Z]{3}") && !prev.equals("INR")) out = Currency.UNRESOLVED;
        }
        return out;
    }

    static boolean conditional(String text) {
        return text != null && (text.contains("?") || text.contains("？") || CONDITIONAL.matcher(text).find());
    }

    /** More than one basis, or a lot price beside a rate or total: the words contradict themselves. */
    static boolean conflicting(String text, String code) {
        return text != null && bases(scan(NumberGuard.digitized(text, code))).size() > 1;
    }

    /** The one basis the words state, "total" or a canonical unit, or null when there is none, several, or a lot price. */
    static String read(String text, String code) {
        if (text == null) return null;
        Set<String> bases = bases(scan(NumberGuard.digitized(text, code)));
        if (bases.size() != 1) return null;
        String basis = bases.iterator().next();
        return basis.startsWith("lot:") ? null : basis;
    }

    /** The basis the words tie to {@code price} itself, rather than to another number in the same turn, or null. */
    static String evidence(String text, String code, BigDecimal price) {
        String basis = read(text, code);
        if (basis == null || price == null) return null;
        return bindings(text, code).getOrDefault(plain(price), Set.of()).contains(bindingOf(basis)) ? basis : null;
    }

    /**
     * Whether a translation states the same things about its numbers as the source: the price may stay bare, carry
     * rupees or carry its own basis, every other figure keeps its unit and is never made money, and nothing is a
     * foreign currency. A translation that swaps the price and the quantity fails here even when it names no basis.
     */
    static boolean faithful(String source, String fromCode, String translation, String toCode, BigDecimal price, String basis) {
        if (translation == null) return true;
        if (currency(translation, toCode, price) != Currency.NONE && currency(translation, toCode, price) != Currency.RUPEE) {
            return false;
        }
        if (!NumberGuard.check(source, fromCode, translation, toCode).ok()) return false;
        Map<String, Set<String>> from = bindings(source, fromCode);
        String p = plain(price);
        Set<String> priceOk = Set.of("none", "money", bindingOf(basis));
        for (Map.Entry<String, Set<String>> said : bindings(translation, toCode).entrySet()) {
            Set<String> before = from.getOrDefault(said.getKey(), Set.of());
            for (String b : said.getValue()) {
                if (said.getKey().equals(p)) {
                    if (!priceOk.contains(b)) return false;
                } else if (b.startsWith("qty:")) {
                    if (before.stream().anyMatch(x -> !x.equals("none") && !x.equals(b))) return false;
                } else if (!b.equals("none") && !before.contains(b)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * A short reply to the basis or currency question: a basis ("per kg", "total", "kilo ke"), rupees, a plain yes, or a
     * mix of these, and nothing else. Null for anything longer, a new amount, a condition or another currency.
     */
    static Answer answer(String text, String code) {
        if (text == null || conditional(text) || currency(text) == Currency.FOREIGN) return null;
        String d = NumberGuard.digitized(text, code);
        if (NUMBER.matcher(d).find()) return null;
        List<Expr> exprs = scan(d);
        Set<String> bases = bases(exprs);
        if (bases.size() > 1 || bases.stream().anyMatch(b -> b.startsWith("lot:"))) return null;
        StringBuilder rest = new StringBuilder(d);
        for (int i = exprs.size() - 1; i >= 0; i--) rest.replace(exprs.get(i).start(), exprs.get(i).end(), " ");
        boolean rupees = MONEY_RE.matcher(rest).find();
        String left = FILLER.matcher(MONEY_RE.matcher(rest).replaceAll(" ")).replaceAll(" ")
                .replaceAll("[\\p{Punct}।\\s]+", " ").strip();
        String basis = bases.isEmpty() ? null : bases.iterator().next();
        Matcher bare = BARE_UNIT.matcher(left);
        if (basis == null && bare.matches()) {
            basis = canonical(bare.group(1));
            left = "";
        }
        boolean yes = !left.isEmpty() && ReplyGuard.read(left) == ReplyGuard.Reading.YES;
        if (!left.isEmpty() && !yes) return null;
        if (basis == null && !rupees && !yes) return null;
        return new Answer(basis, rupees || yes);
    }

    /** Whether the words state a price: a number tied to rupees, a rate, a total or a lot. */
    static boolean priceStatement(String text, String code) {
        if (text == null) return false;
        return bindings(text, code).values().stream().flatMap(Set::stream)
                .anyMatch(b -> b.equals("money") || b.equals("total") || b.startsWith("rate:") || b.startsWith("lot:"));
    }

    /** Whether the words state a quantity: a number tied to a unit, such as "5 kg" or "दस क्विंटल". */
    static boolean quantityStatement(String text, String code) {
        if (text == null) return false;
        return bindings(text, code).values().stream().flatMap(Set::stream).anyMatch(b -> b.startsWith("qty:"));
    }

    /** Every number in the text and what its neighbours make it: money, a rate, a total, a lot, a quantity, or none. */
    private static Map<String, Set<String>> bindings(String text, String code) {
        String d = NumberGuard.digitized(text, code);
        List<Expr> exprs = scan(d);
        Map<String, Set<String>> out = new HashMap<>();
        Matcher m = NUMBER.matcher(d);
        while (m.find()) {
            out.computeIfAbsent(m.group(1), k -> new HashSet<>()).add(binding(d, m.start(1), m.end(1), exprs));
        }
        return out;
    }

    private static String binding(String d, int start, int end, List<Expr> exprs) {
        for (Expr x : exprs) {
            if (x.lotStart() == start && x.lotEnd() == end) return "qty:" + x.unit();
        }
        String after = d.substring(end);
        String before = d.substring(0, start);
        if (startsWith(after, "\\s*" + FOREIGN_RE.pattern()) || endsWith(before, FOREIGN_RE.pattern() + "\\s*")) return "foreign";
        for (Expr x : exprs) {
            if (x.start() >= end && d.substring(end, x.start()).matches("(?iu)\\s*(?:" + CURRENCY_WORD + ")?\\s*(?:[,-]\\s*)?")) {
                return bindingOf(x.basis());
            }
            if (x.end() <= start && x.lotStart() < 0
                    && d.substring(x.end(), start).matches("(?iu)\\s*(?:of|:|=|-)?\\s*(?:" + MONEY + ")?\\s*")) {
                return bindingOf(x.basis());
            }
            if (x.end() <= start && x.lotStart() >= 0 && d.substring(x.end(), start).matches("(?iu)\\s*(?:" + MONEY + ")?\\s*")) {
                return bindingOf(x.basis());
            }
        }
        if (startsWith(after, "\\s*" + MONEY) || endsWith(before, MONEY + "\\s*")) return "money";
        Matcher q = QUANTITY_AFTER.matcher(after);
        if (q.find()) return "qty:" + canonical(q.group(1));
        return "none";
    }

    private static String bindingOf(String basis) {
        return basis == null ? "none" : basis.equals("total") ? "total" : basis.startsWith("lot:") ? basis : "rate:" + basis;
    }

    private static List<Expr> scan(String d) {
        List<Expr> out = new ArrayList<>();
        Matcher m = BY_UNIT.matcher(d);
        while (m.find()) add(out, new Expr(Kind.RATE, canonical(m.group(1)), m.start(), m.end(), -1, -1));
        m = UNIT_OF.matcher(d);
        while (m.find()) add(out, lotOrRate(m.group(1), canonical(m.group(2)), m.start(), m.end(), m.start(1), m.end(1)));
        m = DATIVE_RE.matcher(d);
        while (m.find()) add(out, lotOrRate(m.group(1), canonical(m.group(2)), m.start(), m.end(), m.start(1), m.end(1)));
        m = FOR_LOT.matcher(d);
        while (m.find()) add(out, lotOrRate(m.group(1), canonical(m.group(2)), m.start(), m.end(), m.start(1), m.end(1)));
        m = PER_UNIT.matcher(d);
        while (m.find()) add(out, new Expr(Kind.RATE, canonical(m.group(1)), m.start(), m.end(), -1, -1));
        m = UNIT_PER.matcher(d);
        while (m.find()) add(out, new Expr(Kind.RATE, canonical(m.group(1)), m.start(), m.end(), -1, -1));
        m = MONEY_UNIT.matcher(d);
        while (m.find()) add(out, new Expr(Kind.RATE, canonical(m.group(1)), m.start(), m.end(), -1, -1));
        m = TOTAL_RE.matcher(d);
        while (m.find()) add(out, new Expr(Kind.TOTAL, null, m.start(), m.end(), -1, -1));
        out.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return out;
    }

    private static Expr lotOrRate(String number, String unit, int start, int end, int numStart, int numEnd) {
        if (number == null || new BigDecimal(number).compareTo(BigDecimal.ONE) == 0) {
            return new Expr(Kind.RATE, unit, number == null ? start : numEnd, end, -1, -1);
        }
        return new Expr(Kind.LOT, unit, start, end, numStart, numEnd);
    }

    /** The first expression found for a stretch of text wins; a later pattern never re-reads the same words. */
    private static void add(List<Expr> out, Expr x) {
        for (Expr o : out) {
            if (x.start() < o.end() && o.start() < x.end()) return;
        }
        out.add(x);
    }

    private static Set<String> bases(List<Expr> exprs) {
        Set<String> out = new HashSet<>();
        for (Expr x : exprs) out.add(x.basis());
        return out;
    }

    private static boolean startsWith(String s, String regex) {
        return Pattern.compile("(?iu)^(?:" + regex + ")").matcher(s).find();
    }

    private static boolean endsWith(String s, String regex) {
        return Pattern.compile("(?iu)(?:" + regex + ")$").matcher(s).find();
    }

    private static String plain(BigDecimal price) {
        return price.stripTrailingZeros().toPlainString();
    }

    private static String canonical(String text) {
        String t = text.toLowerCase(Locale.ROOT).replace("‌", "");
        if (t.matches("kgs|kilos|kilograms|quintals|qtls|tonnes|tons|pieces|dozens|bags|boris")) t = t.substring(0, t.length() - 1);
        if (t.matches("(?u)kg|kilo|kilogram|किलो|केजी|কিলো|কেজি|கிலோ|ಕಿಲೋ|ಕೆಜಿ|கிலோவுக்கு|கிலோக்கு|ಕಿಲೋಗೆ|ಕೆಜಿಗೆ")) return "kg";
        if (t.matches("(?u)quintal|qtl|क्विंटल|कुंतल|কুইন্টাল|குவிண்டால்|ಕ್ವಿಂಟಲ್|ಕ್ವಿಂಟಾಲ್|குவிண்டாலுக்கு|ಕ್ವಿಂಟಲ್ಗೆ|ಕ್ವಿಂಟಾಲ್ಗೆ")) {
            return "quintal";
        }
        if (t.matches("(?u)tonne|ton|टन|টন|டன்|ಟನ್|டன்னுக்கு|ಟನ್ಗೆ|ಟನ್ನಿಗೆ")) return "tonne";
        if (t.matches("(?u)bori|বস্তা")) return "bag";
        return t;
    }
}
