package com.example.g_one.agent.deal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads a turn spoken while a yes or no question is pending as two parts: the answer to that question, and whatever
 * else the caller said in the same breath. People rarely answer with a bare "haan": they say "haan, but unka naam kya
 * hai?", "yes, and what is the buyer's name", "haan theek hai, lekin kab aayega". Both parts count: the answer is
 * recorded, and the rest is handled as its own request.
 *
 * <p>Plain code, in English, Hindi, Hinglish, Bengali, Tamil and Kannada, on the {@link ReplyGuard} lexicon:
 * <ol>
 *   <li>A turn {@link ReplyGuard} reads as a plain yes or no is that answer, with nothing else to handle.</li>
 *   <li>Otherwise the turn is cut where the answer ends: after its leading yes or no words ("haan ji | unka naam kya
 *       hai"), or at the first comma, sentence end, question mark or joining word ("but", "lekin", "par", "aur", "and",
 *       "কিন্তু", "ஆனால்", "ಆದರೆ") when the clause before it reads as a yes or no ("haan main Suresh hoon, | bhav kya
 *       hai").</li>
 *   <li>The rest is checked for anything that changes the answer. A condition ("only if", "agar", "sirf", "যদি"), a
 *       "but" followed by a statement rather than a question ("haan par kisi ko mat batana"), doubt ("pata nahi"), the
 *       opposite answer ("haan... nahi nahi"), or a delay ("pehle batao", "first tell me") makes the answer
 *       {@link Answer#NONE}: never a yes. A question or a request after the answer ("but who is he?", "aur bhav batao")
 *       does not change it.</li>
 *   <li>Amounts or quantities stated outside a question ("theek hai lekin 1900 mein") are flagged as
 *       {@link Parsed#termsStated}: the deal confirmation, which must stay strict about money, treats them as a counter
 *       offer, never as a yes.</li>
 * </ol>
 * A turn with no answer words at all and no question ("aap yaad rakhiyega") is left {@link Parsed#decided undecided},
 * for the one small model call of {@link YesNoReader#readCompound}; without that call it is no answer.
 */
public final class CompoundReply {
    /** The answer to the pending question. */
    public enum Answer { YES, NO, NONE }

    /**
     * One reading.
     *
     * @param answer the answer to the pending question; never YES when {@code conditional}
     * @param conditional the answer came with a condition, a doubt, a delay or its opposite
     * @param termsStated amounts or quantities stated outside a question: a counter offer when a deal is being confirmed
     * @param remainder what else the caller said, to be handled as a request of its own; empty when nothing
     * @param decided false when only a model could tell whether this was an answer
     */
    public record Parsed(Answer answer, boolean conditional, boolean termsStated, String remainder, boolean decided) {
        public Parsed {
            remainder = remainder == null ? "" : remainder.strip();
            if (conditional && answer == Answer.YES) answer = Answer.NONE;
        }

        public boolean hasRemainder() {
            return !remainder.isEmpty();
        }

        /** The answer as a {@link ReplyGuard.Reading}: NONE is UNCLEAR. */
        public ReplyGuard.Reading reading() {
            return switch (answer) {
                case YES -> ReplyGuard.Reading.YES;
                case NO -> ReplyGuard.Reading.NO;
                case NONE -> ReplyGuard.Reading.UNCLEAR;
            };
        }

        static Parsed none(String remainder, boolean decided) {
            return new Parsed(Answer.NONE, false, false, remainder, decided);
        }
    }

    /** Words that begin the rest of a turn after its answer. */
    private static final Set<String> CONTRAST = words("but", "however", "though", "although", "lekin", "magar", "par",
            "parantu", "kintu", "tobe", "लेकिन", "मगर", "पर", "परंतु", "परन्तु", "কিন্তু", "তবে", "ஆனால்", "ஆனா",
            "ಆದರೆ", "ಆದ್ರೆ");
    private static final Set<String> AND = words("and", "also", "plus", "aur", "toh", "और", "तो", "এবং",
            "আর", "আরও", "மற்றும்", "ಮತ್ತು");
    /** A condition on the answer: never a yes. */
    private static final Set<String> CONDITION = words("if", "unless", "only", "provided", "except", "depends",
            "depending", "maybe", "perhaps", "probably", "agar", "sirf", "bashart", "basharte", "tabhi", "shayad",
            "अगर", "यदि", "सिर्फ", "सिर्फ़", "केवल", "बशर्ते", "तभी", "शायद", "যদি", "শুধু", "হয়তো", "jodi", "shudhu",
            "hoyto", "ಇದ್ದರೆ", "ಮಾತ್ರ", "ಬಹುಶಃ", "என்றால்", "மட்டும்", "ஒருவேளை");
    /** "Not yet": the answer waits until the rest is dealt with. */
    private static final Set<String> DEFER = words("first", "before", "later", "wait", "pehle", "pahle", "baad",
            "ruko", "rukiye", "पहले", "बाद", "रुको", "रुकिए", "আগে", "পরে", "দাঁড়ান", "முதலில்", "பிறகு", "ಮೊದಲು",
            "ನಂತರ");
    /** Asking G-One to say something: a follow-up request, not a condition. */
    private static final Set<String> REQUEST = words("tell", "explain", "repeat", "batao", "bataiye", "bataie",
            "bataye", "bata", "samjhao", "samjhaiye", "बताओ", "बताइए", "बताइये", "बताएं", "बता",
            "समझाओ", "समझाइए", "বলুন", "বলো", "বলেন", "சொல்லுங்கள்", "சொல்லு", "ಹೇಳಿ", "ಹೇಳು");
    /** Number words that state an amount or a quantity. */
    private static final Set<String> AMOUNT = words("hundred", "thousand", "lakh", "lakhs", "rupees", "rupee", "rs",
            "quintal", "quintals", "kilo", "kg", "ton", "tons", "sau", "hazaar", "hazar", "hajar", "lac", "rupaye",
            "rupay", "rupye", "सौ", "हज़ार", "हजार", "लाख", "रुपये", "रुपए", "रुपया", "क्विंटल", "किलो", "टन", "শো", "হাজার",
            "লাখ", "টাকা", "কুইন্টাল", "কেজি", "ஆயிரம்", "நூறு", "ரூபாய்", "ಸಾವಿರ", "ನೂರು", "ರೂಪಾಯಿ");
    private static final Pattern DIGIT = Pattern.compile("\\d|₹");
    /** Words (letters and vowel signs) of the original text, with where they are. */
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}'’]+|\\d+(?:[.,]\\d+)*");
    /** Where a clause ends: a comma, a sentence end or a question mark. */
    private static final Pattern BREAK = Pattern.compile("[,;:،।॥.!?？]+");
    /** The longest answer read as words before the rest begins ("haan ji bilkul theek hai bhai"). */
    private static final int MAX_LEAD_WORDS = 6;
    /** A first clause longer than this is saying something else, even with a yes in it. */
    private static final int MAX_LEAD_CLAUSE_WORDS = 8;

    private CompoundReply() {}

    /** Reads a turn by words alone. */
    public static Parsed parse(String text) {
        if (text == null || text.isBlank()) return Parsed.none("", true);
        ReplyGuard.Verdict whole = ReplyGuard.verdict(text);
        List<int[]> words = words(text);
        int clauseCut = clauseLead(text, words);
        int wordCut = wordLead(text, words);
        if (whole.reading() != ReplyGuard.Reading.UNCLEAR && !whole.vetoed()) {
            // A plain answer, unless it goes on to ask for something ("haan ji, mandi bhav bhi bataiye").
            int cut = clauseCut >= 0 ? clauseCut : wordCut;
            String rest = cut < 0 ? "" : stripLead(text.substring(cut));
            boolean request = ReplyGuard.tokens(rest).stream().anyMatch(REQUEST::contains);
            return new Parsed(answer(whole.reading()), false, amount(text), request ? rest : "", true);
        }
        if (clauseCut < 0 && wordCut < 0) return noLead(text, whole);
        // The answer: the first clause when it reads as one, else the leading answer words.
        int cut = clauseCut >= 0 ? clauseCut : wordCut;
        Answer answer = answer(ReplyGuard.read(text.substring(0, cut)));
        // What qualifies the answer is everything after its own words, including the rest of its clause
        // ("theek hai 1900 mein, chalega" names an amount inside the answer's clause).
        String qualifier = wordCut >= 0 ? text.substring(wordCut) : text;
        String rest = stripLead(text.substring(cut));
        List<String> clauses = clauses(qualifier);
        boolean conditional = false;
        boolean terms = false;
        List<String> asked = new ArrayList<>();
        boolean afterContrast = false;
        for (String clause : clauses) {
            List<String> tokens = ReplyGuard.tokens(clause);
            boolean contrast = !tokens.isEmpty() && CONTRAST.contains(tokens.get(0));
            afterContrast |= contrast;
            boolean question = ReplyGuard.asks(clause) || tokens.stream().anyMatch(REQUEST::contains);
            boolean condition = tokens.stream().anyMatch(CONDITION::contains) || ReplyGuard.doubtful(clause)
                    || tokens.stream().anyMatch(DEFER::contains);
            ReplyGuard.Reading said = ReplyGuard.answerOnly(clause) ? ReplyGuard.read(clause) : ReplyGuard.Reading.UNCLEAR;
            boolean opposite = said != ReplyGuard.Reading.UNCLEAR && answer(said) != answer;
            if (condition || opposite) conditional = true;
            if (!question && amount(clause)) terms = true;
            // "haan par kisi ko mat batana": a "but" that goes on to state something qualifies the answer.
            if ((contrast || afterContrast) && !question && !tokens.isEmpty() && said == ReplyGuard.Reading.UNCLEAR) {
                conditional = true;
            }
            if (question && !tokens.stream().anyMatch(CONDITION::contains) && !amount(clause)) asked.add(clause);
        }
        String remainder = conditional ? stripLead(String.join(" ", asked)) : rest;
        if (conditional && ReplyGuard.answerOnly(remainder)) remainder = "";
        return new Parsed(answer, conditional, terms, remainder, true);
    }

    /**
     * True when {@code text} may count as a yes: the words read as a plain yes, or as a yes followed by a question or a
     * request that does not change it. A model's yes has to pass this too.
     */
    public static boolean permitsYes(String text) {
        return ReplyGuard.permitsYes(text) || parse(text).answer() == Answer.YES;
    }

    /**
     * The strict reading for a deal's read-back: a yes with no condition and no amount or quantity stated outside a
     * question. "1900 mein theek hai" names a price, so it is a counter offer, never a confirmation.
     */
    public static boolean confirmsDeal(String text) {
        Parsed p = parse(text);
        return p.answer() == Answer.YES && !p.termsStated();
    }

    /** No answer at the start of the turn. A question is a request of its own; anything else may need a model. */
    private static Parsed noLead(String text, ReplyGuard.Verdict whole) {
        List<String> clauses = clauses(text);
        boolean laterAnswer = clauses.stream().skip(1).anyMatch(c -> {
            ReplyGuard.Verdict v = ReplyGuard.verdict(c);
            return v.reading() != ReplyGuard.Reading.UNCLEAR && !v.vetoed();
        });
        if (laterAnswer) return Parsed.none(text, false); // "who is he? haan connect karo": the model splits it
        // "haan?", "kya?", "kya matlab": nothing to answer but the question itself, which is asked again.
        if (ReplyGuard.answerOnly(text) || ReplyGuard.bareQuestion(text)) return Parsed.none("", true);
        List<String> tokens = ReplyGuard.tokens(text);
        boolean question = ReplyGuard.asks(text) || tokens.stream().anyMatch(REQUEST::contains);
        boolean condition = tokens.stream().anyMatch(CONDITION::contains) || ReplyGuard.doubtful(text);
        if (question) return new Parsed(Answer.NONE, condition, false, text, true);
        if (whole.vetoed() || condition) return new Parsed(Answer.NONE, true, amount(text), "", true);
        return Parsed.none("", false);
    }

    /** Where the first clause ends, when that clause reads as a plain yes or no; else -1. */
    private static int clauseLead(String text, List<int[]> words) {
        int end = -1;
        Matcher br = BREAK.matcher(text);
        if (br.find()) end = br.start();
        for (int i = 1; i < words.size(); i++) {
            int[] w = words.get(i);
            if (end >= 0 && w[0] >= end) break;
            String word = ReplyGuard.normalized(text.substring(w[0], w[1]));
            if (CONTRAST.contains(word) || AND.contains(word) || CONDITION.contains(word)) {
                end = w[0];
                break;
            }
        }
        if (end <= 0 || text.substring(end).strip().replaceAll("^[\\p{P}\\s]+", "").isEmpty()) return -1;
        String lead = text.substring(0, end);
        if (ReplyGuard.tokens(lead).size() > MAX_LEAD_CLAUSE_WORDS) return -1;
        ReplyGuard.Verdict v = ReplyGuard.verdict(lead);
        return v.reading() != ReplyGuard.Reading.UNCLEAR && !v.vetoed() ? end : -1;
    }

    /** The end of the longest run of yes or no words that opens the turn, when more follows; else -1. */
    private static int wordLead(String text, List<int[]> words) {
        int best = -1;
        for (int k = 1; k < words.size() && k <= MAX_LEAD_WORDS; k++) {
            String prefix = text.substring(0, words.get(k - 1)[1]);
            if (!ReplyGuard.answerOnly(prefix)) {
                // A phrase can need its next word ("theek | hai"), so keep looking one word further.
                if (k >= 2 && !ReplyGuard.answerOnly(text.substring(0, words.get(k - 2)[1]))) break;
                continue;
            }
            ReplyGuard.Verdict v = ReplyGuard.verdict(prefix);
            if (v.reading() != ReplyGuard.Reading.UNCLEAR && !v.vetoed()) best = words.get(k - 1)[1];
        }
        return best;
    }

    /** The turn in clauses: cut at punctuation and before every joining or condition word. */
    public static List<String> clauses(String text) {
        List<String> out = new ArrayList<>();
        for (String part : BREAK.split(text)) {
            List<int[]> words = words(part);
            int from = 0;
            for (int i = 1; i < words.size(); i++) {
                String word = ReplyGuard.normalized(part.substring(words.get(i)[0], words.get(i)[1]));
                if (CONTRAST.contains(word) || AND.contains(word)) {
                    add(out, part.substring(from, words.get(i)[0]));
                    from = words.get(i)[0];
                }
            }
            add(out, part.substring(from));
        }
        // Keep a trailing question mark with its clause: "unka naam kya hai?" asks even without a question word.
        if ((text.strip().endsWith("?") || text.strip().endsWith("？")) && !out.isEmpty()) {
            out.set(out.size() - 1, out.get(out.size() - 1) + "?");
        }
        return out;
    }

    private static void add(List<String> out, String clause) {
        if (!clause.isBlank() && !ReplyGuard.tokens(clause).isEmpty()) out.add(clause.strip());
    }

    /** The rest of a turn without the punctuation and joining words that only led into it. */
    private static String stripLead(String rest) {
        String out = rest.strip();
        while (true) {
            String before = out;
            out = out.replaceAll("^[\\p{P}\\s]+", "");
            Matcher m = WORD.matcher(out);
            if (m.lookingAt()) {
                String first = ReplyGuard.normalized(m.group());
                if ((CONTRAST.contains(first) || AND.contains(first)) && m.end() < out.length()) {
                    out = out.substring(m.end());
                }
            }
            if (out.equals(before)) return out.strip();
        }
    }

    private static boolean amount(String clause) {
        return DIGIT.matcher(clause).find() || ReplyGuard.tokens(clause).stream().anyMatch(AMOUNT::contains);
    }

    private static List<int[]> words(String text) {
        List<int[]> out = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            if (m.group().codePoints().anyMatch(Character::isLetter)) out.add(new int[] {m.start(), m.end()});
        }
        return out;
    }

    private static Answer answer(ReplyGuard.Reading r) {
        return switch (r) {
            case YES -> Answer.YES;
            case NO -> Answer.NO;
            case UNCLEAR -> Answer.NONE;
        };
    }

    private static Set<String> words(String... words) {
        return Arrays.stream(words).map(ReplyGuard::normalized).collect(Collectors.toUnmodifiableSet());
    }
}
