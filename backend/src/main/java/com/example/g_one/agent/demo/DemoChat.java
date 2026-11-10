package com.example.g_one.agent.demo;

import com.example.g_one.agent.brain.LlmAgentBrain;
import com.example.g_one.agent.brain.TurnIntent;
import com.example.g_one.agent.deal.ReplyGuard;
import com.example.g_one.agent.llm.ChatClient;
import com.example.g_one.agent.llm.ChatMessage;
import com.example.g_one.agent.llm.ChatReply;
import com.example.g_one.agent.llm.ChatRequest;
import com.example.g_one.agent.llm.SpokenNumbers;
import com.example.g_one.agent.llm.ToolCall;
import com.example.g_one.agent.llm.ToolSpec;
import com.example.g_one.agent.tools.buyers.Buyer;
import com.example.g_one.agent.tools.buyers.BuyerDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * A scripted stand-in for the language model, so the whole demo runs offline. It answers the same prompts through the
 * same code path as a real model: it picks tools by keyword, and it knows the lines in {@link DemoScript}. A line it
 * does not know is passed through untranslated, so the demo never pretends to translate what it cannot.
 *
 * <p>Like a real model it asks a first-time caller's name once the system prompt says the name is unknown
 * ({@link LlmAgentBrain#NAME_ASK}), on a reply that is not itself a question. A name the farmer states is saved by the
 * brain's own code, so the next call from the same number gets the name check.
 */
@Component
@ConditionalOnProperty(name = "g-one.demo.enabled", havingValue = "true")
public class DemoChat implements ChatClient.Scripted {
    private static final String MEDIATION_PROMPT_START = "You help a farmer and a buyer talk";
    private static final Pattern DEVANAGARI = Pattern.compile("\\p{InDevanagari}");
    private static final Pattern PICKUP = Pattern.compile("pickup\\s+(.+?)(?:,|\\.|\\s+payment|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAYMENT = Pattern.compile("payment\\s+(.+?)(?:,|\\.|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGITS = Pattern.compile("\\d[\\d,]*");
    private static final Pattern QUANTITY = Pattern.compile("(\\d+)\\s*(?:quintal|qtl|क्विंटल)", Pattern.CASE_INSENSITIVE);

    private final BuyerDirectory directory;
    private final ObjectMapper json;
    private final AtomicLong toolIds = new AtomicLong();

    public DemoChat(BuyerDirectory directory, ObjectMapper json) {
        this.directory = directory;
        this.json = json;
    }

    @Override
    public ChatReply complete(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        String system = messages.get(0).content();
        if (system != null && system.startsWith(MEDIATION_PROMPT_START)) return mediation(messages.get(messages.size() - 1));
        ChatMessage last = messages.get(messages.size() - 1);
        boolean hindi = !system.contains("on this call is English");
        boolean askName = system.contains(LlmAgentBrain.NAME_ASK);
        if ("tool".equals(last.role())) return text(afterTool(messages, last, hindi, askName));
        String turn = last.content() == null ? "" : last.content();
        if (DEVANAGARI.matcher(turn).find()) hindi = true;
        return intake(request, turn, hindi, askName);
    }

    /**
     * The name question after a plain answer, once the brain says the name is unknown. Only after a price or a
     * declined call: never on top of a yes/no question, a call being placed or a goodbye.
     */
    private static String withNameAsk(String text, boolean askName, boolean hindi) {
        if (!askName || text.isBlank()) return text;
        return text.strip() + (hindi ? " और आपका नाम क्या है?" : " And may I know your name?");
    }

    // ---- the assistant's own conversation ----

    private ChatReply intake(ChatRequest request, String turn, boolean hindi, boolean askName) {
        String t = turn.toLowerCase(Locale.ROOT);
        if (offered(request, "confirm_name")) {
            ReplyGuard.Reading r = ReplyGuard.read(turn);
            if (r != ReplyGuard.Reading.UNCLEAR) {
                return tool("confirm_name", json.createObjectNode().put("confirmed", r == ReplyGuard.Reading.YES));
            }
        }
        if (matches(t, "bhool jao", "भूल जाओ", "forget me", "ভুলে যাও", "மறந்துவிடு", "ಮರೆತುಬಿಡಿ") && offered(request, "forget_me")) {
            return tool("forget_me", json.createObjectNode());
        }
        Optional<String> name = TurnIntent.statedName(turn);
        if (name.isEmpty() && request.messages().stream().filter(m -> "assistant".equals(m.role()) && m.content() != null)
                .reduce((a, b) -> b).map(m -> TurnIntent.asksName(m.content())).orElse(false)) {
            name = TurnIntent.shortName(turn);
        }
        if (name.isPresent()) {
            Optional<Buyer> pendingBuyer = buyerNamedBeforeNameQuestion(request.messages());
            if (pendingBuyer.isPresent()) {
                String buyerName = pendingBuyer.get().name();
                return text(hindi ? "धन्यवाद " + name.get() + " जी, आपका नाम नोट कर लिया। क्या मैं अभी " + buyerName
                                + " को फ़ोन लगाऊँ?"
                        : "Thank you, " + name.get() + " ji, I have noted your name. Shall I call " + buyerName + " now?");
            }
            return text(hindi ? "धन्यवाद " + name.get() + " जी, आपका नाम नोट कर लिया। प्याज़ का भाव बताऊँ या ख़रीदार ढूँढूँ?"
                    : "Thank you, " + name.get() + " ji, I have noted your name. Shall I tell you the onion price or find a buyer?");
        }
        Optional<Buyer> asked = buyerJustOffered(request.messages());
        if (asked.isPresent() && ReplyGuard.isConsent(turn) && offered(request, "connect_buyer")) {
            return tool("connect_buyer", json.createObjectNode().put("buyerId", asked.get().id()));
        }
        if (asked.isPresent()) {
            return text(withNameAsk(hindi ? "ठीक है, अभी फ़ोन नहीं लगाती।" : "All right, I won't call them now.", askName, hindi)
                    + (askName ? "" : hindi ? " और कुछ बताइए।" : " Anything else?"));
        }
        if (matches(t, "bech", "बेच", "sell", "khareedar", "ख़रीदार", "खरीदार", "buyer") && offered(request, "find_buyers")) {
            ObjectNode args = json.createObjectNode().put("commodity", "onion").put("district", "Nashik");
            Long q = quantity(turn);
            if (q != null) args.put("quantity", q);
            return tool("find_buyers", args);
        }
        if (matches(t, "bhav", "bhaav", "rate", "price", "भाव", "दाम") && offered(request, "get_mandi_price")) {
            return tool("get_mandi_price", json.createObjectNode().put("commodity", "onion"));
        }
        if (matches(t, "bye", "alvida", "अलविदा", "goodbye") && offered(request, "end_call")) {
            return tool("end_call", json.createObjectNode());
        }
        return text(withNameAsk(hindi ? "मैं प्याज़ का भाव बता सकती हूँ या ख़रीदार ढूँढ सकती हूँ। क्या करना है?"
                : "I can tell you the onion price or find you a buyer. Which would you like?", askName, hindi));
    }

    private String afterTool(List<ChatMessage> messages, ChatMessage last, boolean hindi, boolean askName) {
        String tool = toolNameFor(messages, last.toolCallId());
        String result = last.content();
        return switch (tool) {
            case "find_buyers" -> askName ? withNameAsk(buyersLine(result, hindi, true), true, hindi) : buyersLine(result, hindi, false);
            case "connect_buyer" -> result.startsWith("Calling")
                    ? (hindi ? "फ़ोन लगा रही हूँ। लाइन पर बने रहिए।" : "Calling them now. Please stay on the line.")
                    : (hindi ? "माफ़ कीजिए, फ़ोन नहीं लगा पाई।" : "Sorry, I could not place that call.");
            case "confirm_name" -> hindi ? "धन्यवाद। बताइए, आज क्या बेचना है?" : "Thank you. What would you like to sell today?";
            case "forget_me" -> result.contains("exactly:") ? result.substring(result.indexOf("exactly:") + 8).strip() : result;
            case "end_call" -> hindi ? "धन्यवाद। नमस्ते।" : "Thank you. Goodbye.";
            case "save_profile_fact" -> withNameAsk(hindi ? "ठीक है, मैंने नोट कर लिया।" : "All right, I have noted that.", askName, hindi);
            case "get_mandi_price" -> withNameAsk(result, askName, hindi);
            default -> withNameAsk(result, askName, hindi);
        };
    }

    private String buyersLine(String result, boolean hindi, boolean nameFirst) {
        try {
            JsonNode buyers = json.readTree(result).path("buyers");
            if (!buyers.isArray() || buyers.isEmpty()) return hindi ? "सूची में अभी कोई ख़रीदार नहीं है।" : "The directory has no buyer for that.";
            JsonNode first = buyers.get(0);
            String name = first.path("name").asText();
            String place = first.path("place").asText();
            String range = first.path("listedRange").asText();
            if (nameFirst) {
                return hindi ? place + " के " + name + " प्याज़ ख़रीदते हैं, हमारी सूची में उनका भाव "
                        + hindiRange(first.path("buyerId").asText()) + " रुपये प्रति क्विंटल लिखा है।"
                        : name + " in " + place + " buy onions, listed at " + range + ".";
            }
            return hindi
                    ? place + " के " + name + " प्याज़ ख़रीदते हैं। हमारी सूची में उनका भाव " + hindiRange(first.path("buyerId").asText())
                            + " रुपये प्रति क्विंटल लिखा है। क्या मैं अभी " + name
                            + " को फ़ोन लगाऊँ? हाँ या ना बोलिए।"
                    : name + " in " + place + " buy onions. Our list shows " + range + ". Shall I call " + name
                            + " now? Please say yes or no.";
        } catch (IOException e) {
            return result;
        }
    }

    /** Hindi words, so the line stays Devanagari and the reply language stays Hindi. */
    private String hindiRange(String buyerId) {
        return directory.find(buyerId).flatMap(b -> b.crop("Onion"))
                .map(c -> SpokenNumbers.words(c.minPrice(), "hi-IN") + " से " + SpokenNumbers.words(c.maxPrice(), "hi-IN"))
                .orElse("");
    }

    private Optional<Buyer> buyerJustOffered(List<ChatMessage> messages) {
        for (int i = messages.size() - 2; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (!"assistant".equals(m.role()) || m.content() == null) continue;
            String said = m.content();
            if (!said.contains("फ़ोन लगाऊँ") && !said.contains("Shall I call")) return Optional.empty();
            return directory.all().stream().filter(b -> said.contains(b.name())).findFirst();
        }
        return Optional.empty();
    }

    private Optional<Buyer> buyerNamedBeforeNameQuestion(List<ChatMessage> messages) {
        for (int i = messages.size() - 2; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (!"assistant".equals(m.role()) || m.content() == null) continue;
            return TurnIntent.asksName(m.content())
                    ? directory.all().stream().filter(b -> m.content().contains(b.name())).findFirst()
                    : Optional.empty();
        }
        return Optional.empty();
    }

    private static boolean offered(ChatRequest request, String tool) {
        return request.tools().stream().map(ToolSpec::name).anyMatch(tool::equals);
    }

    private static String toolNameFor(List<ChatMessage> messages, String id) {
        return messages.stream().flatMap(m -> m.toolCalls().stream()).filter(c -> c.id().equals(id)).map(ToolCall::name)
                .findFirst().orElse("");
    }

    // ---- mediation ----

    private ChatReply mediation(ChatMessage input) {
        JsonNode in;
        try {
            in = json.readTree(input.content());
        } catch (IOException e) {
            return text("{}");
        }
        String turn = in.path("turn").asText("");
        String speaker = in.path("speaker").asText("");
        ObjectNode out = json.createObjectNode();
        out.put("translation", DemoScript.translate(turn, in.path("toLanguage").asText("")));
        ObjectNode offer = out.putObject("offer");
        readOffer(turn, speaker, offer);
        out.putObject("caution").put("status", "none").put("secretRequest", false).put("secretShare", false)
                .put("advancePayment", false);
        out.put("reply", reply(turn, in.path("awaiting").asText("none")));
        return text(out.toString());
    }

    private void readOffer(String turn, String speaker, ObjectNode offer) {
        Long price = "buyer".equals(speaker) ? price(turn) : null;
        Long quantity = quantity(turn);
        String pickup = group(PICKUP, turn);
        String payment = group(PAYMENT, turn);
        offer.put("status", price != null ? "clear" : "none");
        if (price != null) {
            offer.put("commodity", "Onion").put("variety", "Red").put("price", price).put("currency", "INR").put("unit", "quintal");
        }
        if (quantity != null) offer.put("quantity", quantity + " quintal");
        if (pickup != null) offer.put("pickup", pickup);
        if (payment != null) offer.put("payment", payment);
    }

    private static String reply(String turn, String awaiting) {
        if ("none".equals(awaiting)) return "none";
        return switch (ReplyGuard.read(turn)) {
            case YES -> "yes";
            case NO -> "no";
            case UNCLEAR -> "accept".equals(awaiting) ? "none" : "unclear";
        };
    }

    /** A price in rupees: digits, or number words in Hindi or English, at least one hundred. */
    private static Long price(String turn) {
        if (!matches(turn.toLowerCase(Locale.ROOT), "per quintal", "प्रति क्विंटल", "rupees", "रुपये", "rupaye")) return null;
        Matcher m = DIGITS.matcher(turn);
        while (m.find()) {
            long v = Long.parseLong(m.group().replace(",", ""));
            if (v >= 100) return v;
        }
        return wordNumber(turn);
    }

    private static Long quantity(String turn) {
        Matcher m = QUANTITY.matcher(turn);
        if (m.find()) return Long.parseLong(m.group(1));
        String lower = turn.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Long> e : Map.of("बीस", 20L, "bees", 20L, "twenty", 20L).entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    /** The longest number word found, from one hundred to ten thousand in steps of fifty. */
    private static Long wordNumber(String turn) {
        String lower = turn.toLowerCase(Locale.ROOT);
        return Stream.iterate(100L, n -> n <= 10_000, n -> n + 50)
                .flatMap(n -> Stream.of("en-IN", "hi-IN").map(code -> Map.entry(SpokenNumbers.words(n, code), n)))
                .filter(e -> lower.contains(e.getKey()))
                .max((a, b) -> Integer.compare(a.getKey().length(), b.getKey().length()))
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    private static String group(Pattern p, String turn) {
        Matcher m = p.matcher(turn);
        return m.find() ? m.group(1).strip() : null;
    }

    private static boolean matches(String text, String... words) {
        return Stream.of(words).anyMatch(text::contains);
    }

    private ChatReply tool(String name, JsonNode args) {
        return new ChatReply("", List.of(new ToolCall("demo_" + toolIds.incrementAndGet(), name, args.toString())), "tool_calls");
    }

    private static ChatReply text(String content) {
        return new ChatReply(content, List.of(), "stop");
    }
}
