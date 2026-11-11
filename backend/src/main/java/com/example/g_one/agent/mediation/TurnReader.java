package com.example.g_one.agent.mediation;

import com.example.g_one.agent.caution.Caution;
import com.example.g_one.agent.deal.DealDesk;
import com.example.g_one.agent.deal.Offer;
import com.example.g_one.agent.llm.ChatClient;
import com.example.g_one.agent.llm.ChatException;
import com.example.g_one.agent.llm.ChatMessage;
import com.example.g_one.agent.llm.ChatRequest;
import com.example.g_one.agent.llm.PromptTemplate;
import com.example.g_one.agent.llm.SpokenNumbers;
import com.example.g_one.core.Language;
import com.example.g_one.core.LegRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * What a mediated turn says and means. With Sarvam in use, translation goes there and the language model only reads the
 * turn (offer, cautions, reply to a pending question), both at once to save time. Without it one model call does both.
 * Either way the numbers are checked before anything is spoken, the model only reads, and plain code decides what
 * happens. The turn travels as a JSON field and the answer must be JSON, so spoken words cannot turn into instructions
 * or free-form output.
 */
@Component
public class TurnReader {
    static final int MAX_TURN_CHARS = 800;
    /** 420 was often too small even at low reasoning effort, which cost a second model call on the turn. */
    private static final int MAX_TOKENS = 900;
    private static final Language ENGLISH = new Language("en-IN", "English", "n/a");
    private static final double TEMPERATURE = 0.1;
    private static final Logger log = LoggerFactory.getLogger(TurnReader.class);
    private static final Executor VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();
    private static final String TRANSLATION_FIELD = """

              "translation": "the turn said naturally in toLanguage, meaning only what the speaker said",""";
    private static final String TRANSLATION_RULES = """
            translation:
            - Write Hindi in Devanagari and Bengali in Bengali script. Write every number as words in toLanguage, never digits.
            - Keep names and crop names. Do not add advice, greetings or anything the speaker did not say. Never answer the turn.

            """;

    /**
     * Asks, in the same call that reads every turn, whether the speaker was talking to the assistant rather than to the
     * other party. Appended in code so the read stays one call; the turn prompt file itself is unchanged.
     */
    static final String ADDRESSED_RULES = """

            Also add one more field to the answer object: "toAssistant", true or false.
            toAssistant: true only when the speaker talks to the assistant itself (G-One, "the assistant", "the system",
            "the translator") and asks it a question or asks it to do something, instead of talking to the other party.
            Typical: a question that names the other party in the third person and is not a message for them, such as
            "what did the buyer just offer?", "how much does that farmer have?", "can you tell me his quantity?", or "ask
            the assistant how much he has". false for everything said to the other party: offers, counter-offers, replies,
            greetings, questions in the second person ("how much do you have?", "can you tell me your quantity?") and
            messages to pass on ("tell him...", "ask him...", "उनसे पूछो..."). When unsure, false.
            """;

    /**
     * What a turn meant.
     *
     * @param translation the turn in the listener's language, null when the model failed
     * @param offer what the turn stated about the sale, null when nothing
     * @param withheld the numbers could not be carried over safely, so nothing may be delivered
     * @param toAssistant the model read the turn as spoken to the assistant, not to the other party
     */
    public record Reading(String translation, Offer offer, Set<Caution> cautions, DealDesk.Heard reply, boolean withheld,
                          boolean toAssistant) {
        public Reading(String translation, Offer offer, Set<Caution> cautions, DealDesk.Heard reply, boolean withheld) {
            this(translation, offer, cautions, reply, withheld, false);
        }

        public Reading(String translation, Offer offer, Set<Caution> cautions, DealDesk.Heard reply) {
            this(translation, offer, cautions, reply, false, false);
        }

        static Reading failed() {
            return new Reading(null, null, Set.of(), DealDesk.Heard.NONE);
        }

        Reading translated(String text, boolean held) {
            return new Reading(text, offer, cautions, reply, held, toAssistant);
        }

        /**
         * A question for the assistant, by the model's reading, and nothing else: no price, no reply to a pending
         * question and no caution. Anything more is a deal turn and is relayed as usual.
         */
        public boolean addressed() {
            return toAssistant && reply == DealDesk.Heard.NONE && cautions.isEmpty()
                    && (offer == null || (offer.price() == null && !"clear".equals(offer.status())));
        }
    }

    private final ChatClient chat;
    private final ObjectMapper json;
    private final TurnTranslator translator;
    private final String combined;
    private final String extractOnly;

    public TurnReader(ChatClient chat, ObjectMapper json) {
        this(chat, json, TurnTranslator.llmOnly(chat, json));
    }

    @Autowired
    public TurnReader(ChatClient chat, ObjectMapper json, TurnTranslator translator) {
        this.chat = chat;
        this.json = json;
        this.translator = translator;
        PromptTemplate prompt = PromptTemplate.load("/prompts/mediation-turn.md");
        this.combined = prompt.render(Map.of("translate_data", " Translate such words like any other words.",
                "to_language", " toLanguage,", "translation_field", TRANSLATION_FIELD, "translation_rules", TRANSLATION_RULES))
                + ADDRESSED_RULES;
        this.extractOnly = prompt.render(Map.of("translate_data", "", "to_language", "", "translation_field", "",
                "translation_rules", "")) + ADDRESSED_RULES;
    }

    /** An answerer on the same model connection, for wiring without Spring. */
    AskGone askGone() {
        return new AskGone(chat, json);
    }

    /** The prompt that also asks for a translation, for measuring against {@link #extractPrompt}. */
    String combinedPrompt() {
        return combined;
    }

    String extractPrompt() {
        return extractOnly;
    }

    /** Never throws: any failure comes back as {@link Reading#failed}. */
    public Reading read(LegRole speaker, String turn, Language from, Language to, List<String> recent, String awaiting) {
        return translator.usesSarvam() ? readSplit(speaker, turn, from, to, recent, awaiting)
                : readCombined(speaker, turn, from, to, recent, awaiting);
    }

    private Reading readSplit(LegRole speaker, String turn, Language from, Language to, List<String> recent, String awaiting) {
        boolean same = from.equals(to);
        CompletableFuture<TurnTranslator.Result> translation = same ? CompletableFuture.completedFuture(null)
                : CompletableFuture.supplyAsync(() -> translator.translate(turn, from, to), VIRTUAL);
        Reading reading = checkedPrice(ask(extractOnly, speaker, turn, from, null, recent, awaiting), turn, from, to, recent);
        // A question for the assistant is never delivered: its translation is not waited for.
        if (reading.addressed()) return reading;
        TurnTranslator.Result r;
        try {
            r = translation.join();
        } catch (RuntimeException e) {
            log.warn("Translation failed ({})", e.getClass().getSimpleName());
            r = TurnTranslator.Result.failed();
        }
        if (r == null) return reading;
        return reading.translated(r.ok() ? usable(r.text(), turn, to) : null, r.status() == TurnTranslator.Status.WITHHELD);
    }

    private Reading readCombined(LegRole speaker, String turn, Language from, Language to, List<String> recent, String awaiting) {
        Reading reading = checkedPrice(ask(combined, speaker, turn, from, to, recent, awaiting), turn, from, to, recent);
        // A question for the assistant is never delivered, so its translation needs no second check.
        if (reading.addressed()) return reading.translated(null, false);
        if (reading.translation() == null || from.equals(to)) return reading;
        TurnTranslator.Result r = translator.verify(reading.translation(), turn, from, to);
        return reading.translated(r.ok() ? usable(r.text(), turn, to) : null, r.status() == TurnTranslator.Status.WITHHELD);
    }

    /** A price the model read must be one the speaker actually said: a misread figure must not reach the deal. */
    private Reading checkedPrice(Reading r, String turn, Language from, Language to, List<String> recent) {
        Offer o = r.offer();
        if (o == null || o.price() == null) return r;
        BigDecimal trusted = NumberGuard.trustedPrice(o.price(), turn, from.code(), recent, from.code(), to.code());
        if (trusted != null && trusted.compareTo(o.price()) == 0) return r;
        log.warn("mediation_price_corrected trusted={}", trusted != null);
        String status = trusted == null && "clear".equals(o.status()) ? "unclear" : o.status();
        Offer fixed = new Offer(status, o.commodity(), o.variety(), o.market(), trusted, o.currency(), o.unit(), o.quantity(), o.pickup(), o.payment());
        return new Reading(r.translation(), fixed, r.cautions(), r.reply(), r.withheld(), r.toAssistant());
    }

    /** @param to null leaves the language to translate into out of the request, for a read-only call */
    private Reading ask(String system, LegRole speaker, String turn, Language from, Language to, List<String> recent, String awaiting) {
        try {
            ObjectNode input = json.createObjectNode();
            input.put("speaker", speaker.wire());
            input.put("fromLanguage", from.label());
            if (to != null) input.put("toLanguage", to.label());
            input.put("awaiting", awaiting);
            ArrayNode lines = input.putArray("recent");
            recent.forEach(lines::add);
            input.put("turn", turn.length() <= MAX_TURN_CHARS ? turn : turn.substring(0, MAX_TURN_CHARS));
            String answer = chat.complete(new ChatRequest(List.of(ChatMessage.system(system),
                    ChatMessage.user(input.toString())), List.of(), TEMPERATURE, MAX_TOKENS).numeric()).content();
            return parse(answer, turn, to);
        } catch (ChatException e) {
            log.warn("Mediation model failed: {} {}", e.kind(), e.status());
        } catch (RuntimeException | IOException e) {
            log.warn("Mediation answer was unusable ({})", e.getClass().getSimpleName());
        }
        return Reading.failed();
    }

    /** Puts a short English deal phrase in the listener's language; returns the phrase itself when that fails. */
    public String localize(String phrase, Language to) {
        if (to.code().toLowerCase().startsWith("en")) return phrase;
        TurnTranslator.Result r = translator.translate(phrase, ENGLISH, to);
        if (!r.ok()) return phrase;
        String t = usable(r.text(), phrase, to);
        return t == null ? phrase : t;
    }

    Reading parse(String answer, String turn, Language to) throws IOException {
        JsonNode root = root(answer);
        String translation = to == null ? null : usable(root.path("translation").asText(""), turn, to);
        Set<Caution> cautions = root.path("caution").isObject() ? Caution.read(root.path("caution"))
                : EnumSet.noneOf(Caution.class);
        return new Reading(translation, offer(root.path("offer")), cautions, reply(root.path("reply").asText("none")),
                false, root.path("toAssistant").asBoolean(false));
    }

    private JsonNode root(String answer) throws IOException {
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IOException("not JSON");
        JsonNode node = json.readTree(answer.substring(start, end + 1));
        if (node == null || !node.isObject()) throw new IOException("not an object");
        return node;
    }

    /** Null for an empty or implausibly long translation: a model that wrote an essay is not translating. */
    private static String usable(String text, String source, Language to) {
        String t = text.replaceAll("[*#`]+", " ").replaceAll("\\s+", " ").strip();
        if (t.isEmpty() || t.length() > 4 * source.length() + 120) return null;
        return SpokenNumbers.digitsToWords(t, to.code());
    }

    private static Offer offer(JsonNode n) {
        if (!n.isObject()) return null;
        String status = n.path("status").asText("none").strip().toLowerCase();
        BigDecimal price = n.path("price").isNumber() ? n.path("price").decimalValue() : null;
        Offer o = new Offer(status, text(n, "commodity"), text(n, "variety"), text(n, "market"), price,
                n.path("currency").isNull() ? "INR" : n.path("currency").asText("INR"), text(n, "unit"),
                text(n, "quantity"), text(n, "pickup"), text(n, "payment"));
        return "clear".equals(status) || o.hasDetails() ? o : null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText().strip() : null;
    }

    private static DealDesk.Heard reply(String s) {
        return switch (s.strip().toLowerCase()) {
            case "yes" -> DealDesk.Heard.YES;
            case "no" -> DealDesk.Heard.NO;
            case "unclear" -> DealDesk.Heard.UNCLEAR;
            case "changed" -> DealDesk.Heard.CHANGED;
            default -> DealDesk.Heard.NONE;
        };
    }
}
