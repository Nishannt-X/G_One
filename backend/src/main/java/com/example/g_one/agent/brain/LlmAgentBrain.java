package com.example.g_one.agent.brain;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.agent.deal.ReplyGuard;
import com.example.g_one.agent.deal.YesNoReader;
import com.example.g_one.agent.llm.ChatClient;
import com.example.g_one.agent.llm.ChatException;
import com.example.g_one.agent.llm.ChatMessage;
import com.example.g_one.agent.llm.ChatReply;
import com.example.g_one.agent.llm.ChatRequest;
import com.example.g_one.agent.llm.FallbackLines;
import com.example.g_one.agent.llm.FixedLines;
import com.example.g_one.agent.llm.PromptTemplate;
import com.example.g_one.agent.llm.SpokenEnglish;
import com.example.g_one.agent.llm.SpokenNumbers;
import com.example.g_one.agent.llm.ToolCall;
import com.example.g_one.agent.llm.ToolSpec;
import com.example.g_one.agent.mediation.CropNames;
import com.example.g_one.agent.tools.EndCallTool;
import com.example.g_one.agent.tools.buyers.BuyerConsent;
import com.example.g_one.agent.tools.mandi.MandiAnswer;
import com.example.g_one.agent.tools.mandi.MandiPriceTool;
import com.example.g_one.agent.tools.memory.ConfirmNameTool;
import com.example.g_one.agent.tools.memory.MemoryBlock;
import com.example.g_one.agent.tools.memory.MemoryGate;
import com.example.g_one.agent.tools.memory.SaveProfileFactTool;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.Language;
import com.example.g_one.core.Tool;
import com.example.g_one.core.ToolResult;
import com.example.g_one.core.Turn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The agent brain: an OpenAI-compatible model with tool calling behind {@link AgentBrain}.
 *
 * <ul>
 *   <li>{@link #greet} speaks fixed lines (docs/call-flow.md, variant B): a new farmer hears the welcome and "how can I
 *       help", a withheld caller ID hears the welcome, that nothing will be remembered and "how can I help", and a returning farmer
 *       hears only the name check. No model call, so the greeting cannot fail on the model.
 *       {@link #greetAfterLanguageChoice} is the same without the welcome, which the English-first opener already
 *       gave.</li>
 *   <li>{@link #respond} renders the system prompt (language, today, memory rules for the call's state), adds one data
 *       message (stored memory and the digest of older lines, as JSON: data, not instructions), replays the history,
 *       and runs the tool loop: at most {@value #MAX_MODEL_CALLS} model calls per turn, the last one without tools so
 *       the model has to speak. Each tool call is published as {@link ConsoleEvent.ToolCalled}.</li>
 * </ul>
 *
 * <p>Nothing stored ever goes into the system prompt. The returning farmer's memory stays out of the request until they
 * say yes to the name check (only the name to check is in the data message until then): the model records
 * the answer with {@code confirm_name}, offered only while the check waits. That per-call state lives in
 * {@link MemoryGate}, keyed by call id; call {@link #callEnded} at hang-up (idle entries expire anyway).
 *
 * <p>The reply language follows the script of the model's answer ({@link ReplyLanguage}), so a farmer who switches to
 * English gets English. Neither method throws. Questions with one truthful answer never reach a model, and when no model
 * can answer the brain falls back to {@link LocalReplies}, then to one honest line that varies and gives a next step. Logs
 * never contain the prompt, the farmer's words or the API key.
 */
@Component
public class LlmAgentBrain implements AgentBrain {
    /** Model calls per farmer turn, the last one without tools. */
    public static final int MAX_MODEL_CALLS = 4;
    /** A turn longer than this is cut before it reaches the model: no real spoken turn is this long. */
    public static final int MAX_TURN_CHARS = 800;
    /** No further model call starts once a turn has taken this long; the farmer hears a fallback line instead of silence. */
    public static final long TURN_BUDGET_MS = 20_000;
    /** Earlier lines replayed word for word; older ones are folded into a {@link CallDigest} to bound tokens. */
    public static final int MAX_HISTORY_LINES = 6;
    private static final Logger log = LoggerFactory.getLogger(LlmAgentBrain.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    /** Markdown the model sometimes adds; Timbre would read it out. */
    private static final Pattern MARKUP = Pattern.compile("[*#`]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final String SENTENCE_ENDS = "।.?!॥";
    /** Added to the memory rules while a first-time caller's name is unknown and not yet asked. */
    public static final String NAME_ASK = "The name is unknown: answer this turn in at most two sentences, then ask their name "
            + "once in a third short question, so they can be recognised next time.";
    private static final int MAX_SENTENCES = 3;
    private static final int MAX_MEMOS = 2_000;
    private static final Pattern MONEY_WORD = Pattern.compile("(?i)₹|रुपय|रुपए|rupee|টাকা");
    private static final Pattern AMOUNT = Pattern.compile("(?i)₹|(\\d|हज़ार|हजार|लाख|सौ|thousand|lakh|hundred|হাজার|লাখ|শো)[^।.?!]{0,16}(रुपय|रुपए|rupee|টাকা)");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[।.?!॥])\\s+");
    /** A phone number offered, given or promised: "मेरे पास उनका नंबर है", "I have the number", "फ़ोन नंबर". */
    private static final Pattern PHONE_TALK = Pattern.compile("(?iu)(फ़ोन|फोन|मोबाइल|संपर्क|कांटेक्ट|कॉन्टैक्ट|phone|mobile|contact"
            + "|ফোন|মোবাইল|போன்|ಫೋನ್)\\s*(नंबर|नम्बर|number|no\\b|নম্বর|எண்|ನಂಬರ್)|(नंबर|नम्बर|number|নম্বর)\\s*(है|हैं|दे|बता|लिख|is|দিচ্ছি|আছে)"
            + "|(उनका|उसका|इनका|खरीदार का|their|his|her|the buyer'?s)\\s+(नंबर|नम्बर|number)");
    /** A question about calling or connecting a buyer. */
    private static final Pattern CALL_QUESTION = Pattern.compile("(?iu)कॉल|फ़ोन|फोन|जोड़|जोड|मिला|बात करा|\\bcall|connect|phone"
            + "|\\bdial|ফোন|কল|জুড়ে|কথা বলিয়ে|அழை|ಕರೆ");
    /** A quantity in a turn: a number or a unit. */
    private static final Pattern QUANTITY = Pattern.compile("(?iu)\\d|क्विंटल|quintal|किलो|kilo|\\bkg\\b|टन|\\btons?\\b|কুইন্টাল|কেজি"
            + "|तीन|चार|पाँच|पांच|दस|बीस|पचास|सौ|\\b(one|two|three|five|ten|twenty|fifty|hundred|teen|das|bees|pachas|sau)\\b");
    /** Six or more digits in a row (spaces and dashes allowed): what a phone number looks like. */
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d(?:[\\s-]?\\d){5,}");
    /** Advice to go elsewhere instead of being helped: directories, websites, apps. */
    private static final Pattern ELSEWHERE = Pattern.compile("(?iu)निर्देशिका|डायरेक्टरी|directory|वेबसाइट|website|ऑनलाइन|online"
            + "|\\bapp\\b|ऐप|এপ|ওয়েবসাইট|অনলাইন|ডিরেক্টরি");
    /** Where the first clause of an answer ends: "हाँ, ...", "नहीं। ...". */
    private static final Pattern LEAD = Pattern.compile("[,،।.!?;]\\s*");
    /** Languages with price wording in code. */
    private static final Set<String> WORDED = Set.of("hi", "en", "bn");
    private static final Set<String> NUMERIC_TOOLS = Set.of(ToolDiet.MANDI, ToolDiet.WEATHER, ToolDiet.FIND, ToolDiet.CONNECT);

    private final ChatClient chat;
    private final Map<String, Tool> tools;
    private final MemoryGate gate;
    private final FixedLines lines;
    private final FallbackLines fallback;
    private final Language.Registry languages;
    private final ConsoleBus bus;
    private final ObjectMapper json;
    private final Clock clock;
    private final BuyerConsent consent;
    private final PromptTemplate system = PromptTemplate.load(PromptTemplate.ASSISTANT_SYSTEM);
    private final LocalReplies local;
    private final WelcomeBack welcomeBack;
    private final YesNoReader yesNo;
    /** The offline demo scripts every reply, including what follows a price. */
    private final boolean scripted;
    /** Bounded, so a missed hang-up cannot grow it for ever. */
    private final Map<CallId, CallMemo> memos = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CallId, CallMemo> eldest) {
            return size() > MAX_MEMOS;
        }
    };

    @Autowired
    public LlmAgentBrain(ChatClient chat, List<Tool> tools, MemoryGate gate, FixedLines lines, FallbackLines fallback,
                         Language.Registry languages, ConsoleBus bus, ObjectMapper json, BuyerConsent consent) {
        this(chat, tools, gate, lines, fallback, languages, bus, json, Clock.system(INDIA), consent);
    }

    public LlmAgentBrain(ChatClient chat, List<Tool> tools, MemoryGate gate, FixedLines lines, FallbackLines fallback,
                         Language.Registry languages, ConsoleBus bus, ObjectMapper json, Clock clock) {
        this(chat, tools, gate, lines, fallback, languages, bus, json, clock, new BuyerConsent());
    }

    public LlmAgentBrain(ChatClient chat, List<Tool> tools, MemoryGate gate, FixedLines lines, FallbackLines fallback,
                         Language.Registry languages, ConsoleBus bus, ObjectMapper json, Clock clock,
                         BuyerConsent consent) {
        this.consent = consent;
        this.local = new LocalReplies(json);
        this.welcomeBack = new WelcomeBack(json);
        this.yesNo = new YesNoReader(chat);
        this.scripted = chat.isScripted();
        this.chat = chat;
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool t : tools) {
            if (byName.putIfAbsent(t.name(), t) != null) {
                throw new IllegalStateException("Two tools are named " + t.name());
            }
        }
        this.tools = Map.copyOf(byName);
        this.gate = gate;
        this.lines = lines;
        this.fallback = fallback;
        this.languages = languages;
        this.bus = bus;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Reply greet(CallContext ctx) {
        return greeting(ctx, true);
    }

    @Override
    public Reply greetAfterLanguageChoice(CallContext ctx) {
        return greeting(ctx, false);
    }

    /** {@code welcome} false: the English-first opener already introduced G-One, so the welcome line is not repeated. */
    private Reply greeting(CallContext ctx, boolean welcome) {
        try {
            MemoryGate.State state = gate.state(ctx);
            FixedLines.Lines l = lines.forLanguage(ctx.language().code());
            Language lang = spokenIn(l.code(), ctx.language());
            String hello = welcome ? l.welcome() + " " : "";
            String text = switch (state) {
                case WITHHELD -> hello + l.withheld() + " " + l.howCanIHelp();
                case NAME_CHECK -> l.nameCheck(MemoryBlock.rememberedName(ctx.memory()).orElseThrow());
                // No memory notice: whether to remember the call is asked at its end, with a yes or no.
                case NEW, UNNAMED -> hello + l.howCanIHelp();
                // Not reached at the start of a call (these states follow a name check or a forget), kept non-blank.
                case CONFIRMED, OTHER_SPEAKER, FORGOTTEN -> l.welcome();
            };
            return new Reply(text, lang, false);
        } catch (RuntimeException e) {
            log.warn("Greeting failed on call {} ({})", ctx.callId(), e.getClass().getSimpleName());
            List<String> lines = fallback.busy(ctx.language().code());
            return new Reply(lines.get(0), spokenIn(fallback.busyKey(ctx.language().code()), ctx.language()), false);
        }
    }

    @Override
    public Reply respond(CallContext ctx, List<Line> history, Turn latest) {
        Progress turn = new Progress();
        long started = clock.millis();
        List<Line> past = history == null ? List.of() : history;
        CallMemo memo = memo(ctx.callId());
        Reply reply;
        try {
            if (latest != null && !latest.text().isBlank()) consentHeard(ctx, past, latest);
            reply = direct(ctx, past, latest, memo);
            if (reply != null) {
                turn.handler = "local";
            } else {
                noteCrop(latest, memo);
                MemoryGate.State state = gate.state(ctx);
                if (state == MemoryGate.State.NAME_CHECK) reply = nameCheck(ctx, past, latest, memo, turn);
                else if (memo.awaitingName) reply = nameAnswer(ctx, past, latest, memo, turn);
                if (reply == null) reply = guided(ctx, past, latest, memo, turn);
                if (reply == null) {
                    reply = converse(ctx, past, latest, turn, memo);
                    if (reply == null) {
                        turn.failure = "no_answer";
                        reply = degrade(ctx, past, latest, turn, memo);
                    }
                }
            }
        } catch (ChatException e) {
            turn.failure = failureOf(e);
            log.warn("Language model failed on call {}: {} {}", ctx.callId(), e.kind(), e.status());
            reply = degrade(ctx, past, latest, turn, memo);
        } catch (RuntimeException e) {
            turn.failure = "upstream";
            log.warn("Agent brain failed on call {} ({})", ctx.callId(), e.getClass().getSimpleName());
            reply = degrade(ctx, past, latest, turn, memo);
        }
        long latencyMs = Math.max(0, clock.millis() - started);
        log.info("brain_turn call={} handler={} model={} calls={} prompt_tokens={} completion_tokens={} latency_ms={} failure={}",
                ctx.callId(), turn.handler, turn.model, turn.calls, turn.prompt, turn.completion, latencyMs, turn.failure);
        bus.publish(new ConsoleEvent.TurnMeasured(ctx.callId(), clock.millis(), turn.handler, turn.model, turn.prompt,
                turn.completion, turn.calls, latencyMs, turn.failure));
        return reply;
    }

    @Override
    public ReplyGuard.Reading readYesNo(CallId callId, String question, String reply) {
        return yesNo.read(question, reply);
    }

    /**
     * Records whether this turn is the farmer's yes to calling a suggested buyer. Words decide first; only when buyers
     * were suggested and the last line asked something does an unclear reply get the layered reading.
     */
    private void consentHeard(CallContext ctx, List<Line> past, Turn latest) {
        String text = latest.text();
        boolean yes = ReplyGuard.isConsent(text);
        if (!yes && !consent.suggested(ctx.callId()).isEmpty() && !aboutBusiness(latest)) {
            // Only an answer to "shall I call them?" is worth the small model call, never a new request.
            String asked = lastQuestion(past);
            if (CALL_QUESTION.matcher(asked).find()) yes = yesNo.read(asked, text) == ReplyGuard.Reading.YES;
        }
        consent.heard(ctx.callId(), text, yes);
    }

    /** What the assistant said last on this call, or empty. */
    private static String lastAssistantLine(List<Line> past) {
        for (int i = past.size() - 1; i >= 0; i--) {
            Line l = past.get(i);
            if (l != null && l.speaker() == Speaker.ASSISTANT && !l.text().isBlank()) return l.text();
        }
        return "";
    }

    private static String failureOf(ChatException e) {
        return switch (e.kind()) {
            case RATE_LIMITED -> "rate_limited";
            case UNREACHABLE -> "timeout";
            default -> "upstream";
        };
    }

    /** Questions with one truthful answer, answered without a model. Null when the model should take the turn. */
    private Reply direct(CallContext ctx, List<Line> past, Turn latest, CallMemo memo) {
        if (latest == null || latest.text().isBlank() || TurnIntent.noWords(latest.text())) return didNotHear(ctx, memo);
        Set<TurnIntent.Kind> intents = TurnIntent.of(latest.text());
        Language lang = ReplyLanguage.ofTurn(latest.text(), ctx.language(), languages);
        if (intents.contains(TurnIntent.Kind.NAME_SOURCE)) {
            return local(local.nameSource(lang.code(), gate.state(ctx), rememberedName(ctx), memo.name()), lang);
        }
        if (intents.contains(TurnIntent.Kind.IDENTITY)) return local(local.identity(lang.code()), lang);
        if (intents.contains(TurnIntent.Kind.REPEAT)) {
            for (int i = past.size() - 1; i >= 0; i--) {
                Line l = past.get(i);
                if (l != null && l.speaker() == Speaker.ASSISTANT && !l.text().isBlank()) {
                    return new Reply(l.text(), ReplyLanguage.of(l.text(), ctx.language(), languages), false);
                }
            }
            return didNotHear(ctx, memo);
        }
        return null;
    }

    // ---- the returning farmer's name check, and a caller who is someone else ----

    /**
     * The answer to the name check, decided in code where it is clear, so a no is never lost on a model that skipped
     * {@code confirm_name}. A no, a denial ("मैं वो नहीं हूँ", "wrong number") or a different name ("नहीं, मैं राम हूँ",
     * "मेरा नाम अनिकेत है") makes the caller someone else for the rest of the call. A yes unlocks memory. A request heard
     * before the answer ("मुझे भुट्टा बेचना है") is held and taken up once the check is settled; the check is asked
     * again once, then an unclear caller counts as someone else (fail closed). Null when the model should read the turn.
     */
    private Reply nameCheck(CallContext ctx, List<Line> past, Turn latest, CallMemo memo, Progress turn) {
        String text = latest.text();
        String remembered = rememberedName(ctx);
        if (remembered == null) return null;
        Language lang = ReplyLanguage.ofTurn(text, ctx.language(), languages);
        Optional<String> given = TurnIntent.introducedName(text);
        boolean otherName = given.isPresent() && NameMatch.differs(given.get(), remembered);
        boolean business = aboutBusiness(latest) || TurnIntent.wantsConnection(text);
        ReplyGuard.Reading answer = leadAnswer(text);
        if (answer == ReplyGuard.Reading.UNCLEAR && !business && !otherName && !TurnIntent.deniesIdentity(text)) {
            answer = yesNo.read(lastAssistantLine(past), text);
        }
        if (answer == ReplyGuard.Reading.NO || TurnIntent.deniesIdentity(text) || otherName) {
            turn.handler = "local";
            // "नहीं, मैं रमेश हूँ": after a no, the name they give is theirs, unless it is plainly the remembered one.
            Optional<String> theirs = given.filter(n -> !NameMatch.same(n, remembered));
            return otherSpeaker(ctx, past, latest, memo, theirs, business, lang, turn);
        }
        if (answer == ReplyGuard.Reading.YES) {
            answerNameCheck(ctx, true);
            if (!plainYes(latest)) return null; // the rest of the turn is a request: the model takes it with memory
            turn.handler = "local";
            if (memo.held == null) return welcomeBack(ctx, lang);
            String welcome = local.greeting(lang.code(), remembered);
            return continueHeld(ctx, past, latest, memo, welcome.substring(0, firstSentenceEnd(welcome)), lang, turn);
        }
        if (!business) return null;
        if (memo.held == null) memo.held = text;
        turn.handler = "local";
        if (memo.nameChecksAsked++ == 0) return local(local.line(lang.code(), "nameCheckFirst", remembered), lang);
        return otherSpeaker(ctx, past, latest, memo, Optional.empty(), false, lang, turn);
    }

    /** The reading of an answer, or of its first clause when the rest goes on ("हाँ, मैं सुरेश हूँ। प्याज़ का क्या हुआ?"). */
    private static ReplyGuard.Reading leadAnswer(String text) {
        ReplyGuard.Verdict whole = ReplyGuard.verdict(text);
        if (whole.reading() != ReplyGuard.Reading.UNCLEAR) return whole.reading();
        String[] parts = LEAD.split(text.strip(), 2);
        if (parts.length < 2 || parts[0].isBlank()) return ReplyGuard.Reading.UNCLEAR;
        ReplyGuard.Reading lead = ReplyGuard.read(parts[0]);
        if (lead == ReplyGuard.Reading.YES && ReplyGuard.read(parts[1]) == ReplyGuard.Reading.NO) return ReplyGuard.Reading.UNCLEAR;
        return lead;
    }

    /** Records the name check answer through {@code confirm_name}, so the console shows it like any tool call. */
    private void answerNameCheck(CallContext ctx, boolean yes) {
        run(new ToolCall("name_check", ConfirmNameTool.NAME, json.createObjectNode().put("confirmed", yes).toString()), ctx);
    }

    /**
     * The caller is not the remembered farmer. Memory stays hidden for the rest of the call and the console stops
     * showing the remembered profile as theirs. Their own name is taken if they gave it, else asked once; then the
     * request they already made is taken up.
     */
    private Reply otherSpeaker(CallContext ctx, List<Line> past, Turn latest, CallMemo memo, Optional<String> given,
                               boolean business, Language lang, Progress turn) {
        if (gate.state(ctx) == MemoryGate.State.NAME_CHECK) answerNameCheck(ctx, false);
        notOurFarmer(ctx, memo);
        if (business && memo.held == null) memo.held = latest.text();
        if (given.isPresent()) {
            memo.facts.put("name", given.get());
            return continueHeld(ctx, past, latest, memo, local.line(lang.code(), "thanksName", given.get()), lang, turn);
        }
        memo.askedName = true;
        memo.awaitingName = true;
        return local(local.line(lang.code(), "askNameOther"), lang);
    }

    /** Once per call: the console's memory panel is cleared, because the remembered profile is not this caller's. */
    private void notOurFarmer(CallContext ctx, CallMemo memo) {
        if (memo.memoryHidden) return;
        memo.memoryHidden = true;
        ctx.memory().callerKey().ifPresent(key -> bus.publish(
                new ConsoleEvent.MemoryLoaded(ctx.callId(), clock.millis(), key, false, List.of(), List.of())));
    }

    /**
     * The answer to the one name question asked of someone who is not the remembered farmer: "अनिकेत", "मेरा नाम अनिकेत
     * है". A turn that is not a name is answered as usual and the name is not asked again.
     */
    private Reply nameAnswer(CallContext ctx, List<Line> past, Turn latest, CallMemo memo, Progress turn) {
        memo.awaitingName = false;
        Optional<String> name = TurnIntent.introducedName(latest.text());
        if (name.isEmpty()) name = TurnIntent.shortName(latest.text());
        if (name.isEmpty()) return null;
        memo.facts.put("name", name.get());
        Language lang = ReplyLanguage.ofTurn(latest.text(), ctx.language(), languages);
        String thanks = local.line(lang.code(), "thanksName", name.get());
        if (aboutBusiness(latest) || TurnIntent.wantsConnection(latest.text())) {
            // "मेरा नाम अनिकेत है और मैं भुट्टा बेचना चाहता हूँ": the sale in this turn is the request now.
            memo.held = null;
            Reply r = answerOrStep(ctx, past, latest, memo, turn, lang);
            return new Reply(thanks + " " + withoutGreetingBy(r.text(), name.get()), r.language(), r.endCall());
        }
        return continueHeld(ctx, past, latest, memo, thanks, lang, turn);
    }

    /** {@code prefix}, then the request held from before the name was settled, or "how can I help". */
    private Reply continueHeld(CallContext ctx, List<Line> past, Turn latest, CallMemo memo, String prefix, Language lang,
                               Progress turn) {
        String held = memo.held;
        memo.held = null;
        if (held == null) return local(prefix + " " + local.line(lang.code(), "followUp"), lang);
        List<Line> history = new ArrayList<>(past);
        history.add(new Line(Speaker.FARMER, latest.text()));
        Turn request = new Turn(latest.legId(), latest.role(), latest.language(), held, latest.startedMs(), latest.endedMs());
        memo.held = held; // kept for the in-context step if no model answers
        Reply r = answerOrStep(ctx, history, request, memo, turn, lang);
        memo.held = null;
        return new Reply(prefix + " " + withoutGreetingBy(r.text(), memo.name()), r.language(), r.endCall());
    }

    /** The model's answer to {@code request}, or the next step of the sale from code when no model answers. */
    private Reply answerOrStep(CallContext ctx, List<Line> history, Turn request, CallMemo memo, Progress turn, Language lang) {
        Reply r = null;
        try {
            r = converse(ctx, history, request, turn, memo);
            if (r != null) turn.handler = "model";
        } catch (ChatException e) {
            turn.failure = failureOf(e);
            log.warn("Language model failed on call {}: {} {}", ctx.callId(), e.kind(), e.status());
        }
        if (r != null) return r;
        if (turn.failure.isEmpty()) turn.failure = "no_answer";
        Reply step = offline(ctx, history, request, memo);
        return step != null ? step : sellStep(ctx, lang, memo);
    }

    /** The reply without a first sentence that only greets or thanks {@code name} again. */
    private static String withoutGreetingBy(String text, String name) {
        if (name == null || text == null) return text;
        int end = firstSentenceEnd(text);
        String first = text.substring(0, end);
        if (end < text.length() && first.contains(name) && first.split("\\s+").length <= 5) return text.substring(end).strip();
        return text;
    }

    private static int firstSentenceEnd(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (SENTENCE_ENDS.indexOf(text.charAt(i)) >= 0) return i + 1;
        }
        return text.length();
    }

    /** The crop named on this turn becomes this call's crop: it wins over anything remembered. */
    private void noteCrop(Turn latest, CallMemo memo) {
        if (latest == null || !(tools.get(ToolDiet.MANDI) instanceof MandiPriceTool m)) return;
        m.cropIn(latest.text()).ifPresent(c -> {
            memo.crop = c;
            memo.facts.put("crop", c);
        });
        if (QUANTITY.matcher(latest.text()).find()) memo.quantityHeard = true;
    }

    /**
     * "Connect me to someone" with no crop on this call yet: the one thing a buyer search needs is asked from code, so
     * the model cannot offer a phone number or send the farmer elsewhere. Null otherwise.
     */
    private Reply guided(CallContext ctx, List<Line> past, Turn latest, CallMemo memo, Progress turn) {
        if (!TurnIntent.wantsConnection(latest.text()) || memo.crop != null || !consent.suggested(ctx.callId()).isEmpty()) {
            return null;
        }
        if (!tools.containsKey(ToolDiet.FIND)) return null;
        turn.handler = "local";
        return sellStep(ctx, ReplyLanguage.ofTurn(latest.text(), ctx.language(), languages), memo);
    }

    private Reply didNotHear(CallContext ctx, CallMemo memo) {
        List<String> lines = local.lines(ctx.language().code(), "didNotHear");
        return local(memo.unlike(lines), ctx.language());
    }

    /** A line written for {@code lang}, voiced in it unless the resource fell back to English for that language. */
    private Reply local(String text, Language lang) {
        return new Reply(text, spokenIn(local.key(lang.code()), lang), false);
    }

    /**
     * Right after a yes to the name check: the name plus the last crop or the newest open item, in fixed wording, so the
     * "it remembers me" moment needs no model. Languages without such lines get the plain named greeting.
     */
    private Reply welcomeBack(CallContext ctx, Language lang) {
        return welcomeBack.line(lang.code(), ctx.memory())
                .map(w -> new Reply(w.text(), spokenIn(w.code(), lang), false))
                .orElseGet(() -> local(local.greeting(lang.code(), rememberedName(ctx)), lang));
    }

    /** A turn that only answers yes (maybe with a greeting or thanks): nothing else to answer after the name check. */
    private boolean plainYes(Turn latest) {
        Set<TurnIntent.Kind> intents = EnumSet.copyOf(withNone(TurnIntent.of(latest.text())));
        if (!intents.contains(TurnIntent.Kind.YES)) return false;
        intents.removeAll(EnumSet.of(TurnIntent.Kind.YES, TurnIntent.Kind.GREETING, TurnIntent.Kind.THANKS));
        return intents.isEmpty() && !aboutBusiness(latest);
    }

    private String rememberedName(CallContext ctx) {
        return MemoryBlock.rememberedName(ctx.memory()).orElse(null);
    }

    /** No model answered. In order: a common turn answered from code, then one honest line and a next step. */
    private Reply degrade(CallContext ctx, List<Line> past, Turn latest, Progress turn, CallMemo memo) {
        if (turn.endCall) return goodbye(ctx.language());
        Reply offline = latest == null ? null : offline(ctx, past, latest, memo);
        if (offline != null) {
            turn.handler = "local";
            return offline;
        }
        turn.handler = "fallback";
        memo.failures++;
        Language lang = latest == null ? ctx.language() : ReplyLanguage.ofTurn(latest.text(), ctx.language(), languages);
        List<String> lines = memo.failures >= 2 ? fallback.busyAgain(lang.code()) : fallback.busy(lang.code());
        return new Reply(memo.unlike(lines), spokenIn(fallback.busyKey(lang.code()), lang), false);
    }

    /**
     * A turn answered from code when no model could answer, kept in the conversation: a question still waiting (the name
     * check, the caller's name) is asked again, a sale or a "connect me" goes to its next step, and only then a common
     * turn gets its fixed line. Null when nothing fits.
     */
    private Reply offline(CallContext ctx, List<Line> past, Turn latest, CallMemo memo) {
        Set<TurnIntent.Kind> intents = TurnIntent.of(latest.text());
        Language lang = ReplyLanguage.ofTurn(latest.text(), ctx.language(), languages);
        String code = lang.code();
        MemoryGate.State state = gate.state(ctx);
        if (state == MemoryGate.State.NAME_CHECK && rememberedName(ctx) != null) {
            return local(local.line(code, "askNameAgain", rememberedName(ctx)), lang);
        }
        if (memo.awaitingName) return local(local.line(code, "askNameOther"), lang);
        if (intents.contains(TurnIntent.Kind.BYE)) return goodbye(lang);
        if (intents.contains(TurnIntent.Kind.THANKS)) return local(local.line(code, "thanks"), lang);
        if (intents.contains(TurnIntent.Kind.GREETING)) {
            return local(local.greeting(code, state.usesMemory() ? rememberedName(ctx) : memo.name()), lang);
        }
        if (tools.get(ToolDiet.MANDI) instanceof MandiPriceTool m && intents.contains(TurnIntent.Kind.PRICE)) {
            Optional<String> crop = m.cropIn(latest.text());
            return local(crop.isPresent() ? local.mandi(m.answer(crop.get()), code) : local.line(code, "askCrop"), lang);
        }
        if (intents.contains(TurnIntent.Kind.SELL) || intents.contains(TurnIntent.Kind.CONNECT) || memo.held != null) {
            return sellStep(ctx, lang, memo);
        }
        String asked = lastQuestion(past);
        if (!asked.isEmpty() && !fallback.isFallbackLine(asked)) {
            return local(local.line(code, "sorryAgain") + " " + asked, lang);
        }
        if (intents.contains(TurnIntent.Kind.YES)) return local(local.line(code, "yes"), lang);
        if (intents.contains(TurnIntent.Kind.NO)) return local(local.line(code, "no"), lang);
        return null;
    }

    /**
     * The next step of a sale, from code: which crop, or how much of the crop named on this call. When buyers were
     * already suggested, the farmer is asked again whether to call them.
     */
    private Reply sellStep(CallContext ctx, Language lang, CallMemo memo) {
        String code = lang.code();
        String crop = memo.crop;
        if (crop == null) return local(local.line(code, "sellAskCrop"), lang);
        // Crop and quantity known: only a buyer search is left, and that needs the model.
        if (memo.quantityHeard) return local(local.line(code, "sellBusy"), lang);
        return local(local.line(code, "sellAskQuantity").replace("{crop}", CropNames.spoken(crop, null, local.key(code))), lang);
    }

    /** The last question the assistant asked on this call (its last sentence, when that is a question), or empty. */
    private static String lastQuestion(List<Line> past) {
        String line = lastAssistantLine(past).strip();
        if (!line.endsWith("?")) return "";
        String[] sentences = SENTENCE_BREAK.split(line);
        return sentences[sentences.length - 1].strip();
    }

    /** Drops this call's per-call state. The pipeline calls it at hang-up, after reading {@link #memoryState}. */
    public void callEnded(CallId callId) {
        gate.end(callId);
        consent.end(callId);
        memos.remove(callId);
    }

    /**
     * What this call did with memory, for whoever writes the gist at hang-up: {@code OTHER_SPEAKER} means the gist
     * must be flagged and the profile left alone; {@code FORGOTTEN} and {@code WITHHELD} mean nothing is written.
     * Empty for an unknown call.
     */
    public Optional<MemoryGate.State> memoryState(CallId callId) {
        return gate.peek(callId);
    }

    /** True while the returning farmer's name check is unanswered. */
    @Override
    public boolean awaitingIdentity(CallId callId) {
        return gate.peek(callId).filter(s -> s == MemoryGate.State.NAME_CHECK).isPresent();
    }

    /** What happened during one {@link #respond}, kept outside the try so a late failure still ends the call. */
    private static final class Progress {
        boolean endCall;
        String handler = "model";
        String model = "";
        String failure = "";
        int prompt;
        int completion;
        int calls;

        void add(ChatReply.Usage u) {
            calls++;
            prompt += u.promptTokens();
            completion += u.completionTokens();
            model = u.model();
        }
    }

    /** The model's spoken reply, or null when it gave none. Throws {@link ChatException} when no model could answer. */
    private Reply converse(CallContext ctx, List<Line> past, Turn latest, Progress turn, CallMemo memo) {
        long started = clock.millis();
        MemoryGate.State state = gate.state(ctx);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt(ctx, state, memo, latest)));
        int firstReplayed = Math.max(0, past.size() - MAX_HISTORY_LINES);
        ObjectNode digest = CallDigest.of(past.subList(0, firstReplayed), memo.facts);
        boolean hasData = addData(messages, state, ctx, digest);
        String hidden = state.usesMemory() || state == MemoryGate.State.NAME_CHECK ? null : rememberedName(ctx);
        for (Line line : past.subList(firstReplayed, past.size())) {
            if (line == null || line.text().isBlank()) continue;
            // Someone else: the name check that named the remembered farmer is not replayed, so it is not asked again.
            if (hidden != null && line.speaker() == Speaker.ASSISTANT && mentions(line.text(), hidden)) continue;
            messages.add(switch (line.speaker()) {
                case FARMER -> ChatMessage.user(capped(line.text()));
                case BUYER -> ChatMessage.user("The buyer said: " + capped(line.text()));
                case ASSISTANT -> ChatMessage.assistant(line.text());
            });
        }
        messages.add(ChatMessage.user(capped(latest.text())));

        Set<String> called = new HashSet<>();
        boolean toolAnswered = false;
        saveStatedName(ctx, past, latest, memo, called);
        Language expected = ReplyLanguage.ofTurn(latest.text(), ctx.language(), languages);
        for (int call = 1; call <= MAX_MODEL_CALLS; call++) {
            if (clock.millis() - started > TURN_BUDGET_MS) {
                log.warn("Agent brain ran out of its time budget on call {}", ctx.callId());
                break;
            }
            List<ToolSpec> offered = call < MAX_MODEL_CALLS ? specs(ctx, past, latest, called) : List.of();
            ChatRequest request = ChatRequest.of(messages, offered);
            if (numeric(past, latest, called)) request = request.numeric();
            ChatReply reply = chat.complete(request);
            turn.add(reply.usage());
            if (!reply.wantsTools()) {
                String text = spoken(reply);
                if (text.isEmpty()) break;
                boolean modelAskedName = TurnIntent.asksName(speakable(reply.content()));
                text = inRightLanguage(text, expected, messages, turn);
                text = withoutUngroundedAmounts(text, expected, past, latest, called);
                text = guarded(text, ctx, gate.state(ctx), expected, memo,
                        aboutBusiness(latest) || TurnIntent.wantsConnection(latest.text()));
                memo.failures = 0;
                memo.modelTurns++;
                Language lang = ReplyLanguage.of(text, ctx.language(), languages);
                text = withNameQuestion(text, lang, ctx, state, memo, latest, called, toolAnswered, turn.endCall,
                        modelAskedName);
                if (TurnIntent.asksName(text)) memo.askedName = true;
                return new Reply(SpokenNumbers.digitsToWords(text.replace("₹", " "), lang.code()).strip(), lang, turn.endCall);
            }
            if (offered.isEmpty()) break; // asked for tools on the last call: no more rounds
            messages.add(ChatMessage.assistant(reply));
            for (ToolCall tc : reply.toolCalls()) {
                called.add(tc.name());
                ToolResult result = run(tc, ctx);
                if (SaveProfileFactTool.NAME.equals(tc.name()) && result.ok()) memo.remember(tc, json);
                if ((ToolDiet.MANDI.equals(tc.name()) || ToolDiet.WEATHER.equals(tc.name())) && result.ok()) toolAnswered = true;
                if (ToolDiet.MANDI.equals(tc.name()) && result.ok() && reply.toolCalls().size() == 1) {
                    Reply price = priceReply(tc, expected, memo);
                    if (price != null) {
                        memo.failures = 0;
                        memo.modelTurns++;
                        if (nameStillWanted(ctx, state, memo)) {
                            memo.askedName = true;
                            String afterPrice = local.line(expected.code(), "afterPrice");
                            String answer = price.text();
                            if (answer.endsWith(afterPrice)) answer = answer.substring(0, answer.length() - afterPrice.length()).strip();
                            return new Reply(answer + " " + local.line(price.language().code(), "askName"), price.language(),
                                    price.endCall());
                        }
                        return price;
                    }
                }
                if (EndCallTool.NAME.equals(tc.name()) && result.ok()) turn.endCall = true;
                messages.add(ChatMessage.tool(tc.id(), result.content()));
            }
            MemoryGate.State after = gate.state(ctx);
            if (state == MemoryGate.State.NAME_CHECK && after == MemoryGate.State.CONFIRMED && plainYes(latest)) {
                // A bare "yes": the welcome back is fixed wording from memory, with no second model call.
                memo.failures = 0;
                memo.modelTurns++;
                return welcomeBack(ctx, expected);
            }
            if (after == MemoryGate.State.OTHER_SPEAKER && state != after) notOurFarmer(ctx, memo);
            if (after != state) {
                // A confirmed name check unlocks memory; any other change hides it again. Both prompt parts follow.
                state = after;
                messages.set(0, ChatMessage.system(systemPrompt(ctx, state, memo, latest)));
                if (hasData) messages.remove(1);
                hasData = addData(messages, state, ctx, digest);
            }
        }
        log.warn("Agent brain gave no spoken answer within {} model calls on call {}", MAX_MODEL_CALLS, ctx.callId());
        return null;
    }

    /** A first-time caller whose name is still unknown and was not asked on this call, outside a name check. */
    private boolean nameStillWanted(CallContext ctx, MemoryGate.State state, CallMemo memo) {
        return !memo.askedName && memo.name() == null && state.maySave() && state != MemoryGate.State.NAME_CHECK
                && !(state.usesMemory() && rememberedName(ctx) != null);
    }

    /**
     * The reply with the fixed name question added, so a first-time caller is asked their name after the first completed
     * business answer even when the model ignored {@link #NAME_ASK}. A completed business answer is a price or weather
     * question answered with at least one statement, or any answer built on a price or weather tool result. Nothing is
     * added when the reply already asks it, on a turn that offers or places a buyer call, confirms a name or ends the
     * call, when the model only asked something back, or in a language with no fixed name question (the prompt still
     * asks the model to). A trailing question after such an answer gives way to the name question, so the farmer hears
     * one question.
     */
    private String withNameQuestion(String text, Language lang, CallContext ctx, MemoryGate.State state, CallMemo memo,
                                    Turn latest, Set<String> called, boolean toolAnswered, boolean endCall,
                                    boolean modelAskedName) {
        if (!nameStillWanted(ctx, state, memo) || TurnIntent.asksName(text) || endCall) return text;
        if (called.contains(ToolDiet.FIND) || called.contains(ToolDiet.CONNECT) || called.contains(ToolDiet.CONFIRM)) return text;
        String key = local.key(lang.code());
        if (!FixedLines.prefix(key).equals(FixedLines.prefix(lang.code()))) return text;
        String body = cappedSentences(text, MAX_SENTENCES - 1).strip();
        if (!modelAskedName) {
            Set<TurnIntent.Kind> intents = TurnIntent.of(latest.text());
            boolean infoAsked = intents.contains(TurnIntent.Kind.PRICE) || intents.contains(TurnIntent.Kind.WEATHER);
            if (!toolAnswered && !infoAsked) return text;
            if (endsWithQuestion(body)) {
                // A reply that is only a question asks for what the answer still needs; the name can wait.
                body = withoutLastSentence(body);
                if (body.isEmpty()) return text;
            }
        }
        return body + " " + local.line(lang.code(), "askName");
    }

    private static boolean endsWithQuestion(String text) {
        return text.endsWith("?");
    }

    /** The text up to and including the end of its second-to-last sentence; empty for a single sentence. */
    private static String withoutLastSentence(String text) {
        for (int i = text.length() - 2; i >= 0; i--) {
            if (SENTENCE_ENDS.indexOf(text.charAt(i)) >= 0 && Character.isWhitespace(text.charAt(i + 1))) {
                return text.substring(0, i + 1).strip();
            }
        }
        return "";
    }

    /**
     * A price is spoken from code, not reworded by a model: the date and the source cannot be dropped, the number cannot
     * change, and the turn needs no second model call. Tamil and Kannada have no such wording and stay with the model.
     */
    private Reply priceReply(ToolCall call, Language lang, CallMemo memo) {
        if (!scripted && tools.get(ToolDiet.MANDI) instanceof MandiPriceTool m && WORDED.contains(FixedLines.prefix(lang.code()))) {
            JsonNode args = call.arguments(json);
            String market = args.path("market").isTextual() ? args.path("market").asText() : null;
            MandiAnswer answer = m.answer(args.path("commodity").asText(""), market);
            if (answer instanceof MandiAnswer.NoReference n && memo != null) {
                String crop = n.crop() == null ? "" : n.crop();
                if (!memo.noPriceSaid.add(crop.toLowerCase(java.util.Locale.ROOT))) {
                    // Said once already: no second "no dated price", the useful next step instead.
                    String spoken = crop.isBlank() ? "" : CropNames.spoken(crop, null, local.key(lang.code()));
                    String line = crop.isBlank() ? local.line(lang.code(), "sellAskCrop")
                            : local.line(lang.code(), "noPriceAgain").replace("{crop}", spoken);
                    return local(line, lang);
                }
            }
            return local(local.mandi(answer, lang.code()) + " " + local.line(lang.code(), "afterPrice"), lang);
        }
        return null;
    }

    /** A name stated outright is saved by code, because smaller models often skip the tool. */
    private void saveStatedName(CallContext ctx, List<Line> past, Turn latest, CallMemo memo, Set<String> called) {
        MemoryGate.State state = gate.state(ctx);
        if (state == MemoryGate.State.OTHER_SPEAKER || state == MemoryGate.State.WITHHELD) {
            // Nothing may be stored, but the caller is still greeted by the name they gave on this call.
            TurnIntent.statedName(latest.text()).ifPresent(n -> memo.facts.put("name", n));
            return;
        }
        if (!tools.containsKey(ToolDiet.SAVE) || !state.maySave()) return;
        Optional<String> name = TurnIntent.statedName(latest.text());
        if (name.isEmpty() && !past.isEmpty()) {
            Line previous = past.get(past.size() - 1);
            if (previous.speaker() == Speaker.ASSISTANT && TurnIntent.asksName(previous.text())) {
                name = TurnIntent.shortName(latest.text());
            }
        }
        if (name.isEmpty()) return;
        ToolCall save = new ToolCall("stated_name", ToolDiet.SAVE,
                json.createObjectNode().put("field", "name").put("value", name.get()).toString());
        if (run(save, ctx).ok()) {
            memo.remember(save, json);
            called.add(ToolDiet.SAVE);
        }
    }

    /** "Hinglish, written in Devanagari script": naming the script stops smaller models replying in English letters. */
    private static String written(Language lang) {
        String script = switch (FixedLines.prefix(lang.code())) {
            case "hi" -> "Devanagari";
            case "bn" -> "Bengali";
            case "ta" -> "Tamil";
            case "kn" -> "Kannada";
            default -> "";
        };
        return script.isEmpty() ? lang.label() : lang.label() + ", written in " + script + " script";
    }

    /** One corrective call when the reply came back in the wrong language; if that fails the first reply stands. */
    private String inRightLanguage(String text, Language expected, List<ChatMessage> messages, Progress turn) {
        if (!ReplyLanguage.wrongFor(text, expected)) return text;
        log.info("brain_language_retry expected={}", expected.code());
        List<ChatMessage> again = new ArrayList<>(messages);
        again.add(ChatMessage.assistant(text));
        again.add(ChatMessage.system("Say that again in " + written(expected) + " only, same meaning, at most three sentences."));
        try {
            ChatReply reply = chat.complete(ChatRequest.of(again, List.of()));
            turn.add(reply.usage());
            String fixed = spoken(reply);
            return fixed.isEmpty() ? text : fixed;
        } catch (ChatException e) {
            return text;
        }
    }

    /** A farmer with a crop, a price, a sale or the weather on their mind is helped first; their name can wait. */
    private boolean aboutBusiness(Turn latest) {
        Set<TurnIntent.Kind> intents = TurnIntent.of(latest.text());
        return intents.contains(TurnIntent.Kind.PRICE) || intents.contains(TurnIntent.Kind.SELL) || intents.contains(TurnIntent.Kind.WEATHER)
                || (tools.get(ToolDiet.MANDI) instanceof MandiPriceTool m && m.cropIn(latest.text()).isPresent());
    }

    /**
     * The last checks on a model's words. Sentences are dropped that: name the remembered farmer to someone who said
     * they are not that farmer; offer, give or promise a phone number (G-One connects buyers on the call and never
     * speaks a number); or send the farmer elsewhere (an online directory, a website, an app) instead of helping. When
     * nothing is left, the next step of the conversation is said instead.
     */
    private String guarded(String text, CallContext ctx, MemoryGate.State state, Language lang, CallMemo memo, boolean sale) {
        String hidden = state.usesMemory() || state == MemoryGate.State.NAME_CHECK ? null : rememberedName(ctx);
        StringBuilder kept = new StringBuilder();
        boolean numberTalk = false;
        boolean dropped = false;
        for (String sentence : SENTENCE_BREAK.split(text)) {
            boolean phone = PHONE_TALK.matcher(sentence).find() || LONG_DIGITS.matcher(sentence).find();
            if ((hidden != null && mentions(sentence, hidden)) || phone || ELSEWHERE.matcher(sentence).find()) {
                numberTalk |= phone;
                dropped = true;
                continue;
            }
            kept.append(kept.isEmpty() ? "" : " ").append(sentence);
        }
        if (!dropped) return text;
        log.info("brain_guard dropped sentences on call {} (phone={})", ctx.callId(), numberTalk);
        String code = lang.code();
        String out = kept.toString().strip();
        if (numberTalk) out = local.line(code, "noNumbers") + (out.isEmpty() ? "" : " " + out);
        if (out.isEmpty() || (sale && !out.endsWith("?"))) {
            String next = sale && consent.suggested(ctx.callId()).isEmpty() ? sellStep(ctx, lang, memo).text() : "";
            out = (out.isEmpty() ? "" : out + " ") + next;
        }
        return out.isBlank() ? local.line(code, "followUp") : capped3(out.strip());
    }

    /** True when {@code text} contains a word of the remembered name (three letters or more). */
    private static boolean mentions(String text, String name) {
        for (String w : name.split("[^\\p{L}\\p{M}]+")) {
            if (w.codePointCount(0, w.length()) >= 3 && text.contains(w)) return true;
        }
        return false;
    }

    /**
     * Drops sentences that state a rupee amount nothing on this call supports. Models recite scheme amounts confidently and
     * wrongly; a farmer may act on them. Amounts the farmer or a tool brought into the call stay.
     */
    private String withoutUngroundedAmounts(String text, Language lang, List<Line> past, Turn latest, Set<String> called) {
        if (!AMOUNT.matcher(text).find() || called.stream().anyMatch(NUMERIC_TOOLS::contains)) return text;
        if (DIGIT.matcher(latest.text()).find() || MONEY_WORD.matcher(latest.text()).find()) return text;
        if (past.stream().anyMatch(l -> l != null && MONEY_WORD.matcher(l.text()).find())) return text;
        StringBuilder kept = new StringBuilder();
        for (String sentence : SENTENCE_BREAK.split(text)) {
            if (!AMOUNT.matcher(sentence).find()) kept.append(kept.isEmpty() ? "" : " ").append(sentence);
        }
        String note = local.line(lang.code(), "confirmAmount");
        return capped3(kept.isEmpty() ? note : kept + " " + note);
    }

    /**
     * Puts the data message (stored memory for this state plus the digest of earlier lines) right after the system
     * prompt, as a user message: everything in it came from callers, so it never rides in the system role. True when
     * there is one.
     */
    private static boolean addData(List<ChatMessage> messages, MemoryGate.State state, CallContext ctx, ObjectNode digest) {
        Optional<String> data = MemoryBlock.dataMessage(state, ctx.memory(), digest);
        data.ifPresent(d -> messages.add(1, ChatMessage.user(d)));
        return data.isPresent();
    }

    private String systemPrompt(CallContext ctx, MemoryGate.State state, CallMemo memo, Turn latest) {
        String memory = MemoryBlock.forPrompt(state);
        boolean nameKnown = memo.name() != null || (state.usesMemory() && rememberedName(ctx) != null);
        if (!nameKnown && state.maySave()) {
            if (memo.askedName) memory += " The name is still unknown; you already asked, do not ask again.";
            else memory += " " + NAME_ASK;
        }
        if (state == MemoryGate.State.OTHER_SPEAKER) {
            memory += nameKnown ? " Their own name from this call is statedThisCall.name; use only that one."
                    : " Their name is unknown; do not ask for it again.";
        }
        return system.render(Map.of(
                "language", ctx.language().label(),
                "lastturn", written(ReplyLanguage.ofTurn(latest.text(), ctx.language(), languages)),
                "today", SpokenEnglish.date(LocalDate.now(clock.withZone(INDIA))),
                "memory", memory));
    }

    /** True when this turn may quote figures, so it must stay off models that get numbers wrong. */
    private boolean numeric(List<Line> past, Turn latest, Set<String> called) {
        if (DIGIT.matcher(latest.text()).find()) return true;
        if (called.stream().anyMatch(NUMERIC_TOOLS::contains)) return true;
        if (past.stream().anyMatch(l -> l != null && l.speaker() == Speaker.BUYER)) return true;
        Set<TurnIntent.Kind> intents = TurnIntent.of(latest.text());
        return intents.contains(TurnIntent.Kind.PRICE) || intents.contains(TurnIntent.Kind.SELL)
                || tools.get(ToolDiet.MANDI) instanceof MandiPriceTool m && m.cropIn(latest.text()).isPresent();
    }

    /** The tools this turn can plausibly use, without the ones already called this turn. */
    private List<ToolSpec> specs(CallContext ctx, List<Line> past, Turn latest, Set<String> called) {
        Set<TurnIntent.Kind> intents = EnumSet.copyOf(withNone(TurnIntent.of(latest.text())));
        // A follow-up such as "and at Nashik?" carries the previous farmer turn's subject.
        for (int i = past.size() - 1; i >= 0; i--) {
            Line l = past.get(i);
            if (l != null && l.speaker() == Speaker.FARMER) {
                intents.addAll(TurnIntent.of(l.text()));
                break;
            }
        }
        boolean crop = tools.get(ToolDiet.MANDI) instanceof MandiPriceTool m && m.cropIn(latest.text()).isPresent();
        Set<String> names = ToolDiet.relevant(intents, crop, TurnIntent.unreadable(latest.text()), gate.state(ctx),
                !consent.suggested(ctx.callId()).isEmpty());
        names.removeAll(called);
        return ToolDiet.specs(tools, names, json);
    }

    private static Set<TurnIntent.Kind> withNone(Set<TurnIntent.Kind> intents) {
        return intents.isEmpty() ? EnumSet.noneOf(TurnIntent.Kind.class) : intents;
    }

    private static boolean offered(String tool, MemoryGate.State state) {
        return !ConfirmNameTool.NAME.equals(tool) || state == MemoryGate.State.NAME_CHECK;
    }

    private ToolResult run(ToolCall call, CallContext ctx) {
        long started = clock.millis();
        JsonNode args;
        ToolResult result;
        try {
            args = call.arguments(json);
        } catch (IllegalArgumentException e) {
            args = json.createObjectNode();
            result = ToolResult.error("The arguments were not a JSON object.");
            publish(ctx, call, args, result, started);
            return result;
        }
        Tool tool = tools.get(call.name());
        if (tool == null || !offered(call.name(), gate.state(ctx))) {
            result = ToolResult.error("No such tool is available now.");
        } else {
            try {
                result = tool.invoke(args, ctx);
                if (result == null) result = ToolResult.error("The tool gave no result.");
            } catch (RuntimeException e) {
                log.warn("Tool {} failed on call {} ({})", call.name(), ctx.callId(), e.getClass().getSimpleName());
                result = ToolResult.error("The tool failed. Apologise briefly and offer to try again.");
            }
        }
        publish(ctx, call, args, result, started);
        return result;
    }

    private void publish(CallContext ctx, ToolCall call, JsonNode args, ToolResult result, long started) {
        bus.publish(new ConsoleEvent.ToolCalled(ctx.callId(), started, call.name(), args, result.ok(),
                result.content(), Math.max(0, clock.millis() - started)));
    }

    private static String capped(String text) {
        return text.length() <= MAX_TURN_CHARS ? text : text.substring(0, MAX_TURN_CHARS);
    }

    /** The reply ready to speak: one line, no markup, a cut-off answer back to its last whole sentence, at most three sentences. */
    private static String spoken(ChatReply reply) {
        String text = speakable(reply.content());
        if ("length".equals(reply.finishReason())) {
            int end = lastSentenceEnd(text);
            if (end > 0) text = text.substring(0, end);
        }
        return capped3(text);
    }

    private static String speakable(String content) {
        return content == null ? "" : SPACES.matcher(MARKUP.matcher(content).replaceAll(" ")).replaceAll(" ").strip();
    }

    private static int lastSentenceEnd(String text) {
        int end = -1;
        for (int i = 0; i < text.length(); i++) {
            if (SENTENCE_ENDS.indexOf(text.charAt(i)) >= 0) end = i + 1;
        }
        return end;
    }

    /** Voice replies stay short even if the model runs on. */
    private static String capped3(String text) {
        return cappedSentences(text, MAX_SENTENCES);
    }

    private static String cappedSentences(String text, int maxSentences) {
        int seen = 0;
        for (int i = 0; i < text.length(); i++) {
            if (SENTENCE_ENDS.indexOf(text.charAt(i)) >= 0 && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1))) && ++seen == maxSentences) {
                return text.substring(0, i + 1);
            }
        }
        return text;
    }

    private CallMemo memo(CallId id) {
        synchronized (memos) {
            return memos.computeIfAbsent(id, k -> new CallMemo());
        }
    }

    /** What the brain remembers within one call, beyond what the pipeline replays. */
    private static final class CallMemo {
        final Map<String, String> facts = new java.util.concurrent.ConcurrentHashMap<>();
        volatile boolean askedName;
        /** The caller is someone else and was asked their name once; the next turn may answer it. */
        volatile boolean awaitingName;
        /** A request made before the name check was settled, taken up right after. */
        volatile String held;
        /** The crop named on this call, which wins over the remembered one. */
        volatile String crop;
        volatile int nameChecksAsked;
        volatile boolean quantityHeard;
        /** The console no longer shows the remembered profile as this caller's. */
        volatile boolean memoryHidden;
        /** Crops this call already heard "no dated price" for. */
        final Set<String> noPriceSaid = java.util.concurrent.ConcurrentHashMap.newKeySet();
        volatile int failures;
        volatile int modelTurns;
        private volatile String lastLine = "";
        private volatile int next;

        String name() {
            return facts.get("name");
        }

        void remember(ToolCall call, ObjectMapper json) {
            try {
                JsonNode a = call.arguments(json);
                String field = a.path("field").asText("");
                String value = a.path("value").asText("");
                if (!field.isBlank() && !value.isBlank()) facts.put(field, value);
            } catch (IllegalArgumentException e) {
                // the tool already refused bad arguments
            }
        }

        /** The next of {@code lines} that is not the last line this call heard from this list. */
        synchronized String unlike(List<String> lines) {
            String line = lines.get(next++ % lines.size());
            if (line.equals(lastLine) && lines.size() > 1) line = lines.get(next++ % lines.size());
            lastLine = line;
            return line;
        }
    }

    private Reply goodbye(Language current) {
        FixedLines.Lines l = lines.forLanguage(current.code());
        return new Reply(l.goodbye(), spokenIn(l.code(), current), true);
    }

    /** The language to voice a fixed line written for {@code lineCode}: the current one when it shares the prefix. */
    private Language spokenIn(String lineCode, Language current) {
        String prefix = FixedLines.prefix(lineCode);
        if (prefix.equals(FixedLines.prefix(current.code()))) return current;
        return ReplyLanguage.byPrefix(prefix, languages).orElse(current);
    }
}
