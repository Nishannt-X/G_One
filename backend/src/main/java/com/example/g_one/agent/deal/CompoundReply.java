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
    private static final Set<String> DEFER = words("first", "later", "wait", "pehle", "pahle", "ruko", "rukiye", "पहले",
            "रुको", "रुकिए", "আগে", "পরে", "দাঁড়ান", "முதலில்", "பிறகு", "ಮೊದಲು", "ನಂತರ");
    /** Asking G-One to say something: a follow-up request, not a condition. */
    private static final Set<String> REQUEST = words("tell", "explain", "repeat", "batao", "bataiye", "bataie",
            "bataye", "bata", "samjhao", "samjhaiye", "बताओ", "बताइए", "बताइये", "बताएं", "बता",
            "समझाओ", "समझाइए", "বলুন", "বলো", "বলেন", "சொல்லுங்கள்", "சொல்லு", "ಹೇಳಿ", "ಹೇಳು");
    /** Number words that state an amount or a quantity. */
    private static final Set<String> AMOUNT = words("hundred", "thousand", "lakh", "lakhs", "rupees", "rupee", "rs",
            "quintal", "quintals", "kilo", "kg", "ton", "tons", "sau", "hazaar", "hazar", "hajar", "lac", "rupaye",
            "rupay", "rupye", "सौ", "हज़ार", "हजार", "लाख", "रुपये", "रुपए", "रुपया", "क्विंटल", "किलो", "टन", "শো", "হাজার",
            "লাখ", "টাকা", "কুইন্টাল", "কেজি", "ஆயிரம்", "நூறு", "ரூபாய்", "ಸಾವಿರ", "ನೂರು", "ರೂಪಾಯಿ");
    /** Words that name a price or a currency without a number: a clause with one bears on the deal's terms. */
    private static final Set<String> PRICE = words("price", "rate", "bhav", "bhaav", "daam", "keemat", "kimat", "dollar",
            "dollars", "usd", "taka", "takas", "भाव", "दाम", "रेट", "कीमत", "দাম", "দর", "விலை", "ಬೆಲೆ", "ದರ");
    /**
     * A restriction on the answer: who may be told, what is left out, a limit. With a negation or a condition, a clause
     * beside the answer qualifies it, even when it also holds a request verb ("don't tell anyone", "kisi ko mat batana").
     */
    private static final Set<String> RESTRICT = words("nobody", "noone", "anyone", "anybody", "never", "without",
            "except", "unless", "only", "if", "kisi", "kisiko", "kisiki", "kisike", "bina", "bagair", "baghair", "किसी",
            "किसीको", "बिना", "बगैर", "কাউকে", "কাউকেই", "কারও", "কারো", "ছাড়া", "যাকে", "யாரிடமும்", "யாருக்கும்",
            "இல்லாமல்", "ಯಾರಿಗೂ", "ಯಾರಿಗು", "ಇಲ್ಲದೆ");
    /** Conjunctions that tie the answer to a time or an event ("jab 4500 do", "after you pay"): always a qualification. */
    private static final Set<String> TEMPORAL = words("while", "after", "before", "once", "until", "till", "whenever",
            "jab", "jabtak", "tab", "jis", "jaise", "baad", "जब", "तब", "जिस", "जैसे", "बाद", "যখন", "তখন", "পর", "போது",
            "பின்", "ஆனதும்", "ಆಗ", "ಮೇಲೆ");
    private static final List<String> TEMPORAL_PHRASES = List.of(" as long as ", " as soon as ", " so long as ",
            " provided that ");
    /** "when" and "where" ask only as "when will...", "where is..."; otherwise they join a condition. */
    private static final Set<String> WHEN_LIKE = words("when", "where", "ಯಾವಾಗ");
    private static final Set<String> AUX = words("will", "would", "is", "are", "was", "were", "do", "does", "did", "can",
            "could", "shall", "should", "has", "have", "had", "may", "might");
    /** English question words: they ask only at the start of their clause. */
    private static final Set<String> EN_WH = words("what", "who", "whom", "whose", "which", "why", "how");
    /** Question words of Hindi, Bengali, Tamil and Kannada, which never join a condition, wherever they stand. */
    private static final Set<String> INDIC_WH = words("kya", "kaun", "kaunsa", "kitna", "kitne", "kitni", "kab", "kahan",
            "kahaan", "kaise", "kyun", "kyon", "kyu", "क्या", "कौन", "कौनसा", "कितना", "कितने", "कितनी", "कब", "कहाँ", "कहां",
            "कैसे", "क्यों", "কী", "কি", "কে", "কত", "কখন", "কোথায়", "কেন", "কীভাবে", "কোন", "என்ன", "யார்", "எப்போது",
            "எவ்வளவு", "எங்கே", "ஏன்", "எப்படி", "எந்த", "ಏನು", "ಯಾರು", "ಎಷ್ಟು", "ಎಲ್ಲಿ", "ಯಾಕೆ", "ಹೇಗೆ", "ಯಾವ");
    /** Words that open a clause without saying anything: the assistant's name, an honorific, a filler. */
    private static final Set<String> LEAD_SKIP = words("g", "one", "gone", "ji", "sir", "bhai", "bhaiya", "didi", "madam",
            "sahab", "saab", "please", "hey", "so", "then", "acha", "accha", "achha");
    /** An emphatic "na" after a request ("batao na") asks; it does not refuse. Not the Bengali না, which can. */
    private static final Set<String> EMPHATIC = words("na", "naa", "ना");
    /** "Nothing else" beside a yes closes the conversation; its negation restricts nothing. */
    private static final Set<String> NOTHING_ELSE = Set.of("kuch nahi", "kuch nahin", "aur kuch nahi", "nothing else",
            "nothing more", "thats all", "that's all", "that is all", "bas", "कुछ नहीं", "और कुछ नहीं", "বস", "আর কিছু না",
            "আর কিছু নেই", "வேறு எதுவும் இல்லை", "ಬೇರೆ ಏನೂ ಇಲ್ಲ").stream().map(ReplyGuard::normalized)
            .collect(Collectors.toUnmodifiableSet());
    private static final Pattern DIGIT = Pattern.compile("\\p{Nd}|₹|\\$");
    /** Words (letters and vowel signs) of the original text, with where they are. */
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}'’]+|\\p{Nd}+(?:[.,]\\p{Nd}+)*");
    /** One stretch of a turn up to its punctuation, which says whether that stretch asked something. */
    private static final Pattern SEGMENT = Pattern.compile("([^,;:،।॥.!?？]+)([,;:،।॥.!?？]*)");
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
        boolean clear = whole.reading() != ReplyGuard.Reading.UNCLEAR && !whole.vetoed();
        // Nothing but the answer ("haan ji", "theek hai 4500" with the figure as its only extra).
        if (clear && ReplyGuard.answerOnly(text)) return new Parsed(answer(whole.reading()), false, terms(text), "", true);
        List<int[]> words = words(text);
        int clauseCut = clauseLead(text, words);
        int wordCut = wordLead(text, words);
        Answer answer;
        String qualifier;
        if (clauseCut < 0 && wordCut < 0) {
            if (!clear) return noLead(text, whole);
            answer = answer(whole.reading()); // the answer sits inside a longer clause: every word of it is read
            qualifier = text;
        } else {
            // The answer: the first clause when it reads as one, else the leading answer words. What qualifies it is
            // everything after its own words, including the rest of its clause ("theek hai 1900 mein, chalega").
            int cut = clauseCut >= 0 ? clauseCut : wordCut;
            answer = answer(ReplyGuard.read(text.substring(0, cut)));
            qualifier = wordCut >= 0 ? text.substring(wordCut) : text;
        }
        return assemble(answer, qualifier);
    }

    /** What one clause beside the answer does to it. */
    private enum Kind {
        /** Says nothing more: the answer again, a name, a filler, "nothing else". */
        SAME,
        /** Positively a question or a plain request to G-One: handled on its own, the answer stands. */
        FOLLOW_UP,
        /** A negation, restriction, condition, time clause, doubt or the opposite answer: never a yes. */
        QUALIFIER,
        /** Says something else; after a "but" it is a qualifier. */
        STATEMENT
    }

    private record Clause(Kind kind, boolean terms, boolean defer, boolean contrast) {}

    /** The answer, checked against every clause beside it. Only positively questions become the remainder. */
    private static Parsed assemble(Answer answer, String qualifier) {
        boolean conditional = false;
        boolean terms = false;
        boolean afterContrast = false;
        List<String> follow = new ArrayList<>();
        for (String clause : clauses(qualifier)) {
            Clause c = classify(clause, answer);
            afterContrast |= c.contrast();
            terms |= c.terms();
            Kind kind = c.kind() == Kind.STATEMENT && afterContrast ? Kind.QUALIFIER : c.kind();
            if (kind == Kind.QUALIFIER || c.defer()) conditional = true;
            if (kind == Kind.FOLLOW_UP) follow.add(stripLead(clause));
        }
        String remainder = String.join(" ", follow);
        if (ReplyGuard.answerOnly(remainder)) remainder = "";
        return new Parsed(answer, conditional, terms, remainder, true);
    }

    /**
     * One clause beside the answer. It is a follow-up only when it is positively a question (a question mark, an English
     * question word at its start, a Hindi, Bengali, Tamil or Kannada question word) or a plain request, with nothing in
     * it that could qualify the answer. Anything that could fails closed.
     */
    private static Clause classify(String clause, Answer answer) {
        List<String> t = new ArrayList<>(ReplyGuard.tokens(clause));
        boolean contrast = false;
        while (!t.isEmpty() && (CONTRAST.contains(t.get(0)) || AND.contains(t.get(0)) || LEAD_SKIP.contains(t.get(0)))) {
            contrast |= CONTRAST.contains(t.get(0));
            t.remove(0);
        }
        if (t.isEmpty()) return new Clause(Kind.SAME, false, false, contrast);
        boolean terms = terms(clause);
        String joined = String.join(" ", t);
        if (NOTHING_ELSE.contains(joined)) return new Clause(Kind.SAME, false, false, contrast);
        if (ReplyGuard.answerOnly(joined)) {
            ReplyGuard.Reading said = ReplyGuard.read(joined);
            boolean opposite = said != ReplyGuard.Reading.UNCLEAR && answer(said) != answer;
            return new Clause(opposite ? Kind.QUALIFIER : Kind.SAME, terms, false, contrast);
        }
        boolean marked = clause.strip().endsWith("?") || clause.strip().endsWith("？");
        if (t.size() >= 2 && EMPHATIC.contains(t.get(t.size() - 1)) && REQUEST.contains(t.get(t.size() - 2))) {
            t.remove(t.size() - 1);
        }
        String padded = " " + String.join(" ", t) + " ";
        boolean restricted = ReplyGuard.hasNoWord(t) || ReplyGuard.doubtful(clause)
                || t.stream().anyMatch(w -> RESTRICT.contains(w) || CONDITION.contains(w) || TEMPORAL.contains(w))
                || TEMPORAL_PHRASES.stream().anyMatch(padded::contains);
        boolean interrogativeWhen = false;
        for (int i = 0; i < t.size(); i++) {
            if (!WHEN_LIKE.contains(t.get(i))) continue;
            boolean asks = (i == 0 && i + 1 < t.size() && AUX.contains(t.get(i + 1)))
                    || (t.get(i).equals(ReplyGuard.normalized("ಯಾವಾಗ")) && marked);
            if (asks) interrogativeWhen = true;
            else restricted = true; // "when you pay 4500": a condition, not a question
        }
        if (restricted) return new Clause(Kind.QUALIFIER, terms, false, contrast);
        boolean defer = t.stream().anyMatch(DEFER::contains);
        boolean question = marked || interrogativeWhen || EN_WH.contains(t.get(0)) || t.stream().anyMatch(INDIC_WH::contains);
        boolean request = t.stream().anyMatch(REQUEST::contains);
        if (question || request) return new Clause(Kind.FOLLOW_UP, terms, defer, contrast);
        return new Clause(Kind.STATEMENT, terms, defer, contrast);
    }

    /**
     * True when {@code text} may count as a yes: the words read as a yes, alone or followed by a question or a request
     * that does not change it, or they hold no answer at all and nothing that vetoes one. A model's yes has to pass this.
     */
    public static boolean permitsYes(String text) {
        Parsed p = parse(text);
        if (p.answer() == Answer.YES) return true;
        // Words with no answer in them ("unse baat karao", "aap yaad rakhiyega") may still be a yes, read by a model or
        // as a request to connect; anything the words decided against, or that qualifies, never is.
        return !p.decided() && !p.conditional() && ReplyGuard.permitsYes(text);
    }

    /**
     * The strict reading for a deal's read-back: a yes with no condition and no amount or quantity stated outside a
     * question. "1900 mein theek hai" names a price, so it is a counter offer, never a confirmation.
     */
    public static boolean confirmsDeal(String text) {
        Parsed p = parse(text);
        return p.answer() == Answer.YES && !p.termsStated();
    }

    /**
     * The words of a turn before its {@code remainder}, without the punctuation and joining words between them: "yes"
     * from "yes, and G-One what is the pickup?". Empty when the remainder is not the end of the turn.
     */
    public static String answerOf(String text, String remainder) {
        int at = text.lastIndexOf(remainder);
        if (at <= 0) return "";
        String out = text.substring(0, at);
        while (true) {
            String before = out;
            out = out.replaceAll("[\\p{P}\\s]+$", "");
            int space = Math.max(out.lastIndexOf(' '), -1);
            String last = ReplyGuard.normalized(out.substring(space + 1));
            if (space > 0 && (CONTRAST.contains(last) || AND.contains(last))) out = out.substring(0, space);
            if (out.equals(before)) return out.strip();
        }
    }

    /**
     * True when every clause of {@code remainder} is positively a question or a plain request to G-One, with nothing
     * that could qualify an answer: "tell me his name" is, "don't tell anyone" and "when you pay 4500" are not.
     */
    public static boolean followUp(String remainder) {
        List<String> clauses = clauses(remainder == null ? "" : remainder);
        return !clauses.isEmpty() && clauses.stream().allMatch(c -> {
            Kind k = classify(c, Answer.NONE).kind();
            return k == Kind.FOLLOW_UP || k == Kind.SAME;
        }) && clauses.stream().anyMatch(c -> classify(c, Answer.NONE).kind() == Kind.FOLLOW_UP);
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
        Parsed read = assemble(Answer.NONE, text);
        if (read.hasRemainder()) {
            // The whole turn is the request when nothing in it qualifies; otherwise only its questions are.
            return new Parsed(Answer.NONE, read.conditional(), false, read.conditional() ? read.remainder() : text.strip(),
                    true);
        }
        if (whole.vetoed() || read.conditional()) return new Parsed(Answer.NONE, true, terms(text), "", true);
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

    /**
     * The turn in clauses: cut at punctuation and before every joining word. A clause that a question mark ended keeps
     * it, so "unka naam kya hai?" asks even without a question word.
     */
    public static List<String> clauses(String text) {
        List<String> out = new ArrayList<>();
        Matcher seg = SEGMENT.matcher(text);
        while (seg.find()) {
            String part = seg.group(1);
            boolean asked = seg.group(2).contains("?") || seg.group(2).contains("？");
            List<int[]> words = words(part);
            int from = 0;
            int before = out.size();
            for (int i = 1; i < words.size(); i++) {
                String word = ReplyGuard.normalized(part.substring(words.get(i)[0], words.get(i)[1]));
                if (CONTRAST.contains(word) || AND.contains(word)) {
                    add(out, part.substring(from, words.get(i)[0]));
                    from = words.get(i)[0];
                }
            }
            add(out, part.substring(from));
            if (asked && out.size() > before) out.set(out.size() - 1, out.get(out.size() - 1) + "?");
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

    /** An amount, a number word, a unit, a price word or a currency: the clause bears on the deal's terms. */
    private static boolean terms(String clause) {
        return amount(clause) || ReplyGuard.tokens(clause).stream().anyMatch(PRICE::contains);
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
