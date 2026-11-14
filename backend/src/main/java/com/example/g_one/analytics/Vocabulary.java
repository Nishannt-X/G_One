package com.example.g_one.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every string a stored call record may hold comes from a closed set here, never from speech or model output: a value
 * that is not on its list is stored as {@value #OTHER} (or left out). Tool names come from the registered tool beans,
 * languages from the language registry, buyers and crops from the directory and the mandi catalog
 * ({@link DealVocabulary}); the rest are the fixed words the pipeline itself writes. The one free value is the
 * farmer's stated name, kept only with the farmer's yes and only when it passes {@link #statedName}.
 */
public final class Vocabulary {
    public static final String OTHER = "other";

    /** Leg and call end reasons the pipeline and mediation write, and the writer's own. */
    static final Set<String> END_REASONS = Set.of("hangup", "assistant_hangup", "farmer_left", "buyer_declined",
            "deal_agreed", "stream_stopped", "error", CallRecordWriter.EVICTED, CallRecordWriter.SHUTDOWN);
    static final Set<String> HANDLERS = Set.of("model", "local", "fallback");
    static final Set<String> FAILURES = Set.of("rate_limited", "timeout", "upstream", "no_answer");
    static final Set<String> LANGUAGE_REASONS = Set.of("named", "detected", "run", "mix", "default");
    static final Set<String> OUTCOMES = Set.of("agreed", "no_deal", "info_only", "no_terms");
    static final Set<String> SOURCES = Set.of("twilio", "simulator");
    static final Set<String> IDENTITIES = Set.of("confirmed", "denied", "unchecked");
    static final Set<String> WHISPER_KINDS = Set.of("price_reference", "caution", "tip");
    static final Set<String> DEAL_STATES = Set.of("draft", "readback", "confirming", "agreed", "closed");
    static final Set<String> NOT_STORED_FARMER = Set.of("withheld", "forgotten", "said_no", "no_answer");
    static final Set<String> NOT_STORED_BUYER = Set.of("said_no", "no_answer", "no_leg");
    static final Set<String> ROLES = Set.of("farmer", "buyer");
    /** Profile fields a memory snapshot may name. */
    static final Set<String> FACT_FIELDS = Set.of("name", "village", "district", "state", "crops", "usualMandi",
            "language");
    /** The tools the application registers, for a vocabulary built without Spring (tests, tools). */
    static final List<String> BUNDLED_TOOLS = List.of("get_mandi_price", "get_weather", "find_buyers",
            "connect_buyer", "save_profile_fact", "forget_me", "confirm_name", "end_call", "scam_shield",
            "extract_quality_terms", "deduction_shield", "price_fairness", "estimate_freight");
    /** The default offered languages ({@code G_ONE_LANGUAGES}), for a vocabulary built without Spring. */
    static final List<String> BUNDLED_LANGUAGES = List.of("hi-IN", "en-IN", "hi-en", "bn-IN", "bn-en", "ta-IN",
            "kn-IN");
    private static final String AUTO = "auto";

    private final DealVocabulary deals;
    private final Set<String> tools;
    private final Set<String> languages;

    /**
     * @param tools the registered tools' names
     * @param languages the offered language codes ("auto", still choosing, is always allowed)
     */
    public Vocabulary(DealVocabulary deals, Collection<String> tools, Collection<String> languages) {
        this.deals = Objects.requireNonNull(deals, "deals");
        this.tools = Set.copyOf(tools);
        Set<String> langs = new LinkedHashSet<>(languages);
        langs.add(AUTO);
        this.languages = Set.copyOf(langs);
    }

    static Vocabulary bundled(ObjectMapper json) {
        return new Vocabulary(DealVocabulary.bundled(json), BUNDLED_TOOLS, BUNDLED_LANGUAGES);
    }

    DealVocabulary deals() {
        return deals;
    }

    String tool(String name) {
        return closed(tools, name);
    }

    boolean knownTool(String name) {
        return name != null && tools.contains(name);
    }

    String language(String code) {
        return closed(languages, code);
    }

    static String endReason(String s) {
        return closed(END_REASONS, s);
    }

    static String handler(String s) {
        return closed(HANDLERS, s);
    }

    static String failure(String s) {
        return closed(FAILURES, s);
    }

    static String languageReason(String s) {
        return closed(LANGUAGE_REASONS, s);
    }

    /** An unknown outcome reads as "no terms": the stored-call view only knows the four. */
    static String outcome(String s) {
        return s != null && OUTCOMES.contains(s) ? s : "no_terms";
    }

    /** The value when it is on the list, else {@value #OTHER}. */
    static String closed(Set<String> set, String s) {
        return s != null && set.contains(s) ? s : OTHER;
    }

    /** The value when it is on the list, else null (the field is left out). */
    static String closedOrNull(Set<String> set, String s) {
        return s != null && set.contains(s) ? s : null;
    }

    // ---- the farmer's stated name ----------------------------------------------------------------------------------

    /** Letters of any script (with their marks), spaces, dots, hyphens and apostrophes; nothing else. */
    private static final Pattern NAME_CHARS = Pattern.compile("[\\p{L}\\p{M} .'’-]+");
    static final int MAX_NAME_TOKENS = 4;
    static final int MAX_TOKEN_CHARS = 20;
    /** Number words in English, romanised Hindi, Hindi, Bengali, Tamil and Kannada: a name never holds one. */
    private static final Set<String> NUMBER_WORDS = Set.of(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
            "twenty", "thirty", "forty", "fifty", "hundred", "thousand", "lakh", "lakhs", "crore", "million",
            "ek", "do", "teen", "char", "chaar", "panch", "paanch", "chhe", "chhah", "saat", "aath", "nau", "das",
            "sau", "hazaar", "hazar", "karod",
            "शून्य", "एक", "दो", "तीन", "चार", "पांच", "पाँच", "छह", "छः", "सात", "आठ", "नौ", "दस", "सौ", "हज़ार",
            "हजार", "लाख", "करोड़",
            "শূন্য", "এক", "দুই", "তিন", "চার", "পাঁচ", "ছয়", "সাত", "আট", "নয়", "দশ", "শত", "হাজার", "লাখ", "কোটি",
            "பூஜ்யம்", "ஒன்று", "இரண்டு", "மூன்று", "நான்கு", "ஐந்து", "ஆறு", "ஏழு", "எட்டு", "ஒன்பது", "பத்து",
            "நூறு", "ஆயிரம்", "லட்சம்",
            "ಸೊನ್ನೆ", "ಒಂದು", "ಎರಡು", "ಮೂರು", "ನಾಲ್ಕು", "ಐದು", "ಆರು", "ಏಳು", "ಎಂಟು", "ಒಂಬತ್ತು", "ಹತ್ತು", "ನೂರು",
            "ಸಾವಿರ", "ಲಕ್ಷ");

    /**
     * The farmer's stated name when it is plainly a name: one to four tokens of letters (any script), each at most
     * twenty characters, joined by spaces, dots, hyphens or apostrophes; no digit of any script, no number word, no
     * email address or link. Anything else is left out.
     */
    static Optional<String> statedName(String spoken) {
        if (spoken == null) return Optional.empty();
        String s = Normalizer.normalize(spoken, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
        if (s.isEmpty() || !NAME_CHARS.matcher(s).matches() || DealCard.unsafe(s)) return Optional.empty();
        if (s.codePoints().anyMatch(Character::isDigit)) return Optional.empty();
        String[] tokens = s.split(" ");
        if (tokens.length > MAX_NAME_TOKENS) return Optional.empty();
        for (String t : tokens) {
            if (t.codePointCount(0, t.length()) > MAX_TOKEN_CHARS) return Optional.empty();
            if (!t.codePoints().anyMatch(Character::isLetter)) return Optional.empty();
            for (String part : t.split("[.'’-]+")) {
                if (NUMBER_WORDS.contains(part.toLowerCase(Locale.ROOT))) return Optional.empty();
            }
        }
        return Optional.of(s);
    }
}
