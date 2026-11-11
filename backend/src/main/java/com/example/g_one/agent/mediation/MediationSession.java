package com.example.g_one.agent.mediation;

import com.example.g_one.agent.AgentBrain.Speaker;
import com.example.g_one.agent.caution.Caution;
import com.example.g_one.agent.closing.CallClosing;
import com.example.g_one.agent.closing.ClosingLines;
import com.example.g_one.agent.deal.DealDesk;
import com.example.g_one.agent.deal.Offer;
import com.example.g_one.agent.deal.ReplyGuard;
import com.example.g_one.agent.deal.Terms;
import com.example.g_one.agent.language.LanguageFlow;
import com.example.g_one.agent.language.LanguageLines;
import com.example.g_one.agent.llm.ReasoningText;
import com.example.g_one.agent.llm.SpokenNumbers;
import com.example.g_one.agent.tools.buyers.BuyerConnector.ConnectRequest;
import com.example.g_one.agent.tools.mandi.MandiAnswer;
import com.example.g_one.agent.tools.mandi.MandiCatalog;
import com.example.g_one.agent.tools.mandi.MandiQuote;
import com.example.g_one.agent.tools.mandi.MandiSnapshot;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.ConsoleEvent.WhisperKind;
import com.example.g_one.core.Language;
import com.example.g_one.core.Leg;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.PlaybackOutcome;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.SttListener;
import com.example.g_one.core.SttSession;
import com.example.g_one.core.TextToSpeech;
import com.example.g_one.core.Turn;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One call's mediation, from the buyer leg joining to the end of the call. Raw audio never crosses legs. Turns run one
 * at a time in arrival order. The buyer must say yes before anything is translated. Whispers are spoken only on the
 * farmer's {@link LegVoice}, so the buyer cannot hear one. Logs carry ids only.
 */
public final class MediationSession {
    private static final Language ENGLISH = new Language("en-IN", "English", "n/a");
    private static final Logger log = LoggerFactory.getLogger(MediationSession.class);
    private static final int RECENT_LINES = 4;
    /** An offer this far below the dated reference gets the stronger wording. */
    private static final BigDecimal FAR_BELOW = new BigDecimal("0.15");

    private enum Phase { ASKING, LIVE, OVER }

    private final CallContext farmer;
    private final CallId callId;
    private final MediationEngine.FarmerSide side;
    private final Leg buyer;
    private final SpeechToText stt;
    private final Executor executor;
    private final TurnReader reader;
    private final MediationLines lines;
    private final MandiQuote mandi;
    private final MandiCatalog catalog;
    private final ConnectRequest request;
    private final String farmerName;
    private final ConsoleBus bus;
    private final Language.Registry languages;
    private final Clock clock;
    private final LegVoice farmerVoice;
    private final LegVoice buyerVoice;
    private final DealDesk desk;
    /** Null for a buyer leg started with a fixed language. */
    private final LanguageFlow buyerLanguage;
    /** Starts as the call's language and follows the farmer's own changes. */
    private volatile Language farmerLanguage;
    private final AtomicLong seq = new AtomicLong();
    private final Map<String, String> localizedPhrases = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private final Deque<Runnable> lane = new ArrayDeque<>();
    private final Deque<String> recent = new ArrayDeque<>();
    private final Set<Caution> cautioned = EnumSet.noneOf(Caution.class);
    private boolean laneBusy;
    private Phase phase = Phase.ASKING;
    /** Set on the transport callback before its terminal step reaches the mediation lane. Guarded by {@link #lock}. */
    private boolean buyerLegEnded;
    private boolean askedTwice;
    private String buyerEndReason = "hangup";
    private BigDecimal lastWhispered;
    private SttSession buyerSession;
    /** Answers a party who speaks to G-One directly. */
    private final AskGone ask;
    /** What each leg said and heard, for those answers. Each party's view reads only their own leg. */
    private final CallLedger ledger = new CallLedger();

    MediationSession(CallContext farmer, MediationEngine.FarmerSide side, Leg buyer, SpeechToText stt, TextToSpeech tts,
                     Executor executor, TurnReader reader, MediationLines lines, MandiQuote mandi, MandiCatalog catalog,
                     ConnectRequest request, String farmerName, ConsoleBus bus, Language.Registry languages, Clock clock,
                     AskGone ask) {
        this.farmer = farmer;
        this.callId = farmer.callId();
        this.side = side;
        this.buyer = buyer;
        this.stt = stt;
        this.executor = executor;
        this.reader = reader;
        this.lines = lines;
        this.mandi = mandi;
        this.catalog = catalog;
        this.request = request;
        this.farmerName = farmerName;
        this.bus = bus;
        this.languages = languages;
        this.clock = clock;
        this.ask = ask;
        this.farmerVoice = new LegVoice(farmer.farmerLeg(), tts);
        this.buyerVoice = new LegVoice(buyer, tts);
        this.desk = new DealDesk(callId, clock, bus, new DeskOutput(), request == null ? null : request.quantity());
        this.farmerLanguage = farmer.language();
        this.buyerLanguage = languages != null && buyer.languagePending() ? new LanguageFlow(languages::find) : null;
    }

    /** The farmer's language changed; turns from now on are read and translated with it. */
    public void farmerLanguage(Language language) {
        farmerLanguage = language;
    }

    /** Publishes the leg, opens its speech-to-text session and asks the buyer. Returns quickly. */
    public void begin() {
        bus.publish(ConsoleEvent.LegJoined.of(buyer, clock.millis()));
        buyer.onEnded(this::buyerEnded);
        buyer.onAudio(this::buyerAudio);
        submit(this::openBuyer);
    }

    /** True once the buyer said yes and until mediation is over: farmer turns go here, not to the brain. */
    public boolean live() {
        synchronized (lock) {
            return phase == Phase.LIVE && !buyerLegEnded;
        }
    }

    /** The farmer started speaking. Barge-in stops playback on their own leg only. */
    public void farmerSpeechStart() {
        farmerVoice.bargeIn();
    }

    /** Accepts a finished farmer turn only while mediation owns it. False lets the caller answer it after release. */
    public boolean farmerTurn(Turn turn) {
        boolean start;
        synchronized (lock) {
            if (phase != Phase.LIVE || buyerLegEnded) return false;
            long n = seq.incrementAndGet();
            lane.addLast(() -> process(turn, n));
            start = !laneBusy;
            if (start) laneBusy = true;
        }
        if (start) submit(this::drain);
        return true;
    }

    /** The farmer's leg ended, for any reason. */
    public void farmerEnded() {
        boolean wasOver;
        synchronized (lock) {
            wasOver = phase == Phase.OVER;
            phase = Phase.OVER;
            buyerEndReason = "farmer_left";
        }
        farmerVoice.close();
        if (!wasOver) {
            buyerVoice.say(new LegVoice.Line(lines.get("buyerFarmerLeft", buyer.language().code()), buyer.language(), true,
                    o -> hangUpBuyer()));
        }
    }

    // ---- the buyer's yes ----

    private void openBuyer() {
        SttListener listener = new SttListener() {
            @Override
            public void onSpeechStart() {
                buyerVoice.bargeIn();
            }

            @Override
            public void onFinalTurn(Turn turn) {
                buyerTurn(turn);
            }

            @Override
            public void onError(Exception error) {
                log.warn("Speech-to-text reported an error on buyer leg {}", buyer.id());
            }
        };
        SttSession opened = null;
        try {
            opened = stt.open(buyer.id(), LegRole.BUYER, buyerLanguage != null ? Language.DETECTING : buyer.language(), listener);
        } catch (RuntimeException e) {
            log.error("Could not open speech-to-text for buyer leg {}", buyer.id(), e);
        }
        boolean over;
        synchronized (lock) {
            over = phase == Phase.OVER || buyerLegEnded;
            if (!over) buyerSession = opened;
        }
        if (over) {
            closeQuietly(opened);
            return;
        }
        if (buyerLanguage != null) {
            buyerLanguage.asked();
            sayAssistant(LegRole.BUYER, LanguageLines.opener(LegRole.BUYER), false, null);
            return;
        }
        introduceFarmer();
    }

    private void introduceFarmer() {
        String code = buyer.language().code();
        sayAssistant(LegRole.BUYER, lines.get("buyerIntro", code, Map.of(
                "farmer", farmerDescription(code),
                "produce", produce(code),
                "language", language(LegRole.FARMER).label())), false, null);
    }

    private String farmerDescription(String code) {
        if (farmerName != null) return farmerName;
        return switch (lines.spokenCode(code)) {
            case "hi-IN" -> "एक किसान";
            case "bn-IN" -> "একজন কৃষক";
            default -> "a farmer";
        };
    }

    private String produce(String code) {
        String crop = request == null || request.commodity() == null ? null : catalog.commodity(request.commodity())
                .orElse(request.commodity());
        String generic = switch (lines.spokenCode(code)) {
            case "hi-IN" -> "अपनी फ़सल";
            case "bn-IN" -> "ফসল";
            default -> "their produce";
        };
        if (crop == null) return generic;
        String named = CropNames.spoken(crop, null, code);
        return request.quantity() == null ? named : CropNames.quantity(request.quantity(), code) + " " + named;
    }

    private void buyerTurn(Turn heard) {
        Phase now;
        synchronized (lock) {
            now = phase;
        }
        if (now == Phase.OVER) return;
        Turn turn = heard;
        if (buyerLanguage != null) {
            LanguageFlow.Outcome outcome = buyerLanguage.onTurn(heard.text(), buyerVoice.speaking());
            switch (outcome.kind()) {
                case IGNORED -> {
                    return;
                }
                case REASK -> {
                    Turn asked = new Turn(heard.legId(), heard.role(), buyer.language(), heard.text(), heard.startedMs(), heard.endedMs());
                    publishHeard(asked);
                    side.note(Speaker.BUYER, asked.text());
                    enqueue(() -> sayFixed(LegRole.BUYER, LanguageLines.reask(), languages.find("hi-en").orElse(buyer.language())));
                    return;
                }
                case CHOSEN -> {
                    // What the buyer said is not an answer to anything yet: they have not heard who is calling.
                    Language chosen = outcome.language();
                    switchBuyer(outcome);
                    Turn answered = new Turn(heard.legId(), heard.role(), chosen, heard.text(), heard.startedMs(), heard.endedMs());
                    publishHeard(answered);
                    side.note(Speaker.BUYER, answered.text());
                    enqueue(() -> {
                        sayFixed(LegRole.BUYER, LanguageLines.confirmation(chosen.code()), chosen);
                        introduceFarmer();
                    });
                    return;
                }
                case SWITCHED -> {
                    switchBuyer(outcome);
                    turn = new Turn(heard.legId(), heard.role(), outcome.language(), heard.text(), heard.startedMs(), heard.endedMs());
                }
                case KEEP -> { }
            }
        }
        Turn current = turn;
        publishHeard(current);
        side.note(Speaker.BUYER, current.text());
        long n = seq.incrementAndGet();
        enqueue(() -> {
            if (askingBuyer()) {
                buyerAnswered(current);
            } else if (live()) {
                process(current, n);
            }
        });
    }

    private void switchBuyer(LanguageFlow.Outcome outcome) {
        String from = buyer.languagePending() ? Language.AUTO_CODE : buyer.language().code();
        buyer.switchLanguage(outcome.language());
        SttSession listening;
        synchronized (lock) {
            listening = buyerSession;
        }
        if (listening != null) {
            try {
                listening.retarget(outcome.language());
            } catch (RuntimeException e) {
                log.warn("Changing the speech-to-text language failed on buyer leg {}", buyer.id(), e);
            }
        }
        bus.publish(new ConsoleEvent.LanguageChanged(callId, clock.millis(), buyer.id(), LegRole.BUYER, from,
                outcome.language().code(), outcome.reason()));
    }

    private boolean askingBuyer() {
        synchronized (lock) {
            return phase == Phase.ASKING && !buyerLegEnded;
        }
    }

    private void buyerAnswered(Turn turn) {
        ReplyGuard.Reading reading = ReplyGuard.read(turn.text());
        String buyerCode = buyer.language().code();
        if (reading == ReplyGuard.Reading.YES) {
            Language farmerCode = language(LegRole.FARMER);
            String joined = rendered(lines.get("farmerBuyerJoined", farmerCode.code(),
                    Map.of("buyer", buyerName())), farmerCode);
            synchronized (lock) {
                // The buyer or farmer can end while the answer is being prepared. Never reopen an ended session.
                if (phase != Phase.ASKING || buyerLegEnded) return;
                // From here on the buyer is asked the end-of-call questions too, before any turn can reach mediation.
                side.closing().joined(LegRole.BUYER);
                // Queue under the same lock that admits buyerEnded. The voice is held before LIVE becomes visible, and
                // its joined line is queued before a departure or the resume callback can run.
                farmerVoice.hold();
                farmerVoice.say(new LegVoice.Line(joined, farmerCode, false, null));
                phase = Phase.LIVE;
                // Keep the farmer-side close atomic with LIVE visibility. A concurrent buyer hangup or farmer turn
                // cannot observe LIVE while intake is still free to start a reply that takeover would discard.
                side.takeOver(farmerVoice::resume);
            }
            bus.publish(new ConsoleEvent.AssistantSaid(callId, clock.millis(), farmer.farmerLeg().id(),
                    LegRole.FARMER, farmerCode.code(), joined));
            side.note(Speaker.ASSISTANT, joined);
            if (live()) sayAssistant(LegRole.BUYER, lines.get("buyerAccepted", buyerCode), false, null);
            return;
        }
        boolean retry;
        synchronized (lock) {
            if (phase != Phase.ASKING || buyerLegEnded) return;
            retry = reading == ReplyGuard.Reading.UNCLEAR && !askedTwice;
            if (retry) askedTwice = true;
        }
        if (retry) {
            sayAssistant(LegRole.BUYER, lines.get("buyerRetry", buyerCode), false, null);
            return;
        }
        endBuyerDeclined();
    }

    private void endBuyerDeclined() {
        synchronized (lock) {
            if (phase != Phase.ASKING || buyerLegEnded) return;
            phase = Phase.OVER;
            buyerEndReason = "buyer_declined";
        }
        side.outcome("buyer_declined", null);
        sayAssistant(LegRole.BUYER, lines.get("buyerDeclined", buyer.language().code()), true, o -> hangUpBuyer());
        side.takeOver(() -> sayAssistant(LegRole.FARMER, lines.get("farmerBuyerDeclined", language(LegRole.FARMER).code(),
                Map.of("buyer", buyerName())), false, o -> side.release()));
    }

    private String buyerName() {
        return request == null ? "The buyer" : request.buyer().name();
    }

    // ---- a turn during mediation ----

    private void process(Turn turn, long n) {
        desk.tick();
        if (closingTurn(turn)) return;
        LegRole speaker = turn.role();
        LegRole listener = speaker.other();
        Language to = language(listener);
        var awaiting = desk.awaiting(speaker);
        ReplyGuard.Reading quick = ReplyGuard.read(turn.text());
        if (awaiting.filter(a -> a == DealDesk.Awaiting.CONFIRM).isPresent() && quick != ReplyGuard.Reading.UNCLEAR) {
            desk.heard(speaker, quick == ReplyGuard.Reading.YES ? DealDesk.Heard.YES : DealDesk.Heard.NO, n, turn.startedMs());
            return; // a plain yes or no to the read-back is answered by the read-back flow, and needs no model
        }

        boolean sameLanguage = language(speaker).equals(to);
        boolean farmerSecret = speaker == LegRole.FARMER && ScamPatterns.farmerSharedSecret(turn.text());
        if (farmerSecret) {
            whisperCaution("cautionShareWithheld");
            oneMoment();
            return; // never sent to the model, and never translated for the buyer
        }

        // A yes or no to a pending question is an answer to the deal, never a question for the assistant.
        boolean replying = awaiting.isPresent() && quick != ReplyGuard.Reading.UNCLEAR;
        if (!replying && AddressDetector.addressed(turn.text(), speaker)) {
            aside(turn, "name");
            return; // called by name: answered without reading or translating the turn, so one model call in all
        }

        TurnReader.Reading read = reader.read(speaker, turn.text(), language(speaker), to, recent(),
                awaiting.map(a -> a.name().toLowerCase(Locale.ROOT)).orElse("none"));

        Set<Caution> cautions = EnumSet.noneOf(Caution.class);
        cautions.addAll(ScamPatterns.supported(read.cautions(), turn.text()));
        if (speaker == LegRole.BUYER) cautions.addAll(ScamPatterns.buyer(turn.text()));
        if (!replying && cautions.isEmpty() && read.addressed()) {
            aside(turn, "model");
            return; // spoken to the assistant without its name: never translated, remembered or read as deal terms
        }
        // A line that earned a caution stays out of the context, so it cannot colour how the next turn is read.
        if (cautions.isEmpty()) remember(turn.text());

        if (speaker == LegRole.FARMER && cautions.contains(Caution.SECRET_SHARE)) {
            whisperCaution("cautionShareWithheld");
            oneMoment();
            return;
        }

        DealDesk.Heard reply = guarded(read.reply(), quick, turn.text(), awaiting.isPresent());
        if (awaiting.filter(a -> a == DealDesk.Awaiting.CONFIRM).isPresent()
                && (reply == DealDesk.Heard.YES || reply == DealDesk.Heard.NO || reply == DealDesk.Heard.UNCLEAR)) {
            desk.heard(speaker, reply, n, turn.startedMs());
            return;
        }

        if (speaker == LegRole.BUYER) {
            Set<Caution> fresh = freshCautions(cautions);
            boolean warned = false;
            if (fresh.contains(Caution.SECRET_REQUEST)) warned |= whisperCaution("cautionSecret");
            if (fresh.contains(Caution.ADVANCE_PAYMENT)) warned |= whisperCaution("cautionAdvance");
            if (fresh.contains(Caution.THREAT)) warned |= whisperCaution("cautionThreat");
            if (warned) oneMoment();
        }

        // Cleaned once here, so the console, the transcript and the voice all get the same text without model thinking.
        String spoken = withoutThinking(read.translation());
        if (spoken == null && sameLanguage) spoken = withoutThinking(turn.text());
        if (read.withheld()) {
            sayAssistant(speaker, lines.get("unsafeTranslation", language(speaker).code()), false, null);
        } else if (spoken == null) {
            sayAssistant(speaker, lines.get("repeat", language(speaker).code()), false, null);
        } else {
            translate(turn, listener, to, spoken);
        }

        if (speaker == LegRole.BUYER && read.offer() != null && "clear".equals(read.offer().status())) {
            priceWhisper(read.offer());
        }
        desk.terms(read.offer(), speaker, n);
        // Only a plain spoken no rejects an offer: "too low, I want more" is haggling and the offer stays open.
        if (awaiting.filter(a -> a == DealDesk.Awaiting.ACCEPT).isPresent() && speaker == LegRole.FARMER
                && (reply == DealDesk.Heard.YES || quick == ReplyGuard.Reading.NO)) {
            desk.heard(speaker, reply, n, turn.startedMs());
        } else if (reply == DealDesk.Heard.CHANGED) {
            desk.heard(speaker, reply, n, turn.startedMs());
        }
    }

    /** A model's yes counts only when the words pass the guard; the code's own reading comes first. */
    private static DealDesk.Heard guarded(DealDesk.Heard model, ReplyGuard.Reading quick, String text, boolean awaited) {
        if (!awaited) return DealDesk.Heard.NONE;
        if (quick == ReplyGuard.Reading.YES) return DealDesk.Heard.YES;
        if (quick == ReplyGuard.Reading.NO) return DealDesk.Heard.NO;
        if (model == DealDesk.Heard.YES) return ReplyGuard.permitsYes(text) ? DealDesk.Heard.YES : DealDesk.Heard.UNCLEAR;
        return model;
    }

    private void translate(Turn turn, LegRole listener, Language to, String spoken) {
        Leg target = listener == LegRole.FARMER ? farmer.farmerLeg() : buyer;
        voice(listener).say(new LegVoice.Line(spoken, to, false, null));
        ledger.relayed(turn.role(), ScamPatterns.masked(turn.text()), spoken);
        bus.publish(new ConsoleEvent.TurnTranslated(callId, clock.millis(), turn.legId(), turn.role(), listener,
                turn.language().code(), to.code(), spoken, turn.startedMs()));
        side.note(Speaker.ASSISTANT, spoken);
        log.debug("Translated a turn for leg {}", target.id());
    }

    // ---- a question to G-One: answered on the asker's leg only ----

    /**
     * Answers a turn spoken to the assistant. Nothing of it reaches the other party: no translation, no line on their
     * leg, no deal terms, no place in the turn context. The answer queues on the asker's own voice behind whatever that
     * leg is hearing, unprotected so the asker can interrupt it; the other leg is never touched.
     */
    private void aside(Turn turn, String detectedBy) {
        LegRole asker = turn.role();
        Language lang = language(asker);
        long started = clock.millis();
        DealDesk.Card card = desk.card();
        AskGone.Answer answer = ask.answer(asker, turn.text(), lang, digest(asker, card), card);
        String text = withoutThinking(answer.text());
        if (text == null) text = AskLines.fromCard(card, lang.code());
        ledger.aside(asker, ScamPatterns.masked(turn.text()), text);
        Leg leg = asker == LegRole.FARMER ? farmer.farmerLeg() : buyer;
        voice(asker).say(new LegVoice.Line(text, lang, false, null));
        bus.publish(new ConsoleEvent.AssistantSaid(callId, clock.millis(), leg.id(), asker, lang.code(), text));
        side.note(Speaker.ASSISTANT, text);
        log.info("ask_gone call={} asker={} detected={} source={} ms={}", callId, asker.wire(), detectedBy,
                answer.source(), clock.millis() - started);
    }

    /** The asker's whitelisted view of the call: the buyer's never holds a whisper, the market reference or memory. */
    private ObjectNode digest(LegRole asker, DealDesk.Card card) {
        String crop = request == null || request.commodity() == null ? null
                : catalog.commodity(request.commodity()).orElse(request.commodity());
        if (asker == LegRole.BUYER) return AskDigest.buyerView(card, crop, farmerName, ledger.lines(LegRole.BUYER));
        AskDigest.Buyer who = request == null ? null
                : new AskDigest.Buyer(request.buyer().name(), request.buyer().type(), request.buyer().district());
        return AskDigest.farmerView(card, crop, who, reference(card, crop), ledger.lines(LegRole.FARMER),
                language(LegRole.FARMER).code());
    }

    /** The dated market reference for the crop in play, or null. A local lookup: no network, no model. */
    private AskDigest.Reference reference(DealDesk.Card card, String crop) {
        String commodity = card.commodity() != null ? card.commodity() : crop;
        if (commodity == null) return null;
        try {
            if (mandi.quote(commodity, card.market()) instanceof MandiAnswer.Found found
                    && "INR".equals(found.snapshot().currency())) {
                MandiSnapshot s = found.snapshot();
                return new AskDigest.Reference(s.sourceName(), s.market(), s.commodity(), s.variety(), s.price(), s.unit(),
                        s.priceDate());
            }
        } catch (RuntimeException e) {
            log.warn("Mandi lookup failed on call {} ({})", callId, e.getClass().getSimpleName());
        }
        return null;
    }

    // ---- whispers: the farmer's voice only ----

    /** One whisper per incident: a kind the buyer keeps repeating is told once, and a turn without it ends the incident. */
    private Set<Caution> freshCautions(Set<Caution> now) {
        Set<Caution> fresh = EnumSet.copyOf(now);
        synchronized (lock) {
            fresh.removeAll(cautioned);
            cautioned.clear();
            cautioned.addAll(now);
        }
        return fresh;
    }

    private boolean whisperCaution(String key) {
        whisper(WhisperKind.CAUTION, lines.get(key, language(LegRole.FARMER).code()), true);
        return true;
    }

    private void whisper(WhisperKind kind, String line, boolean isProtected) {
        Language spoken = language(LegRole.FARMER);
        String text = rendered(line, spoken);
        farmerVoice.say(new LegVoice.Line(text, spoken, isProtected, null));
        ledger.whisper(text);
        bus.publish(new ConsoleEvent.WhisperSent(callId, clock.millis(), farmer.farmerLeg().id(), kind, spoken.code(), text));
    }

    /** The farmer hears a whisper at a turn boundary; meanwhile the buyer hears this. */
    private void oneMoment() {
        Language lang = buyer.language();
        buyerVoice.say(new LegVoice.Line(rendered(lines.get("oneMoment", lang.code()), lang), lang, false, null));
    }

    private void priceWhisper(Offer offer) {
        BigDecimal perQuintal = perQuintal(offer);
        if (perQuintal == null || perQuintal.signum() <= 0) return;
        synchronized (lock) {
            if (lastWhispered != null && lastWhispered.compareTo(perQuintal) == 0) return;
            lastWhispered = perQuintal;
        }
        MandiAnswer answer;
        try {
            answer = mandi.quote(offer.commodity(), offer.market());
        } catch (RuntimeException e) {
            log.warn("Mandi lookup failed on call {} ({})", callId, e.getClass().getSimpleName());
            return;
        }
        if (!(answer instanceof MandiAnswer.Found found)) return;
        MandiSnapshot s = found.snapshot();
        if (!"INR".equals(s.currency()) || !"quintal".equalsIgnoreCase(s.unit())) return;
        BigDecimal reference = s.price();
        BigDecimal difference = reference.subtract(perQuintal);
        int compare = difference.compareTo(BigDecimal.ZERO);
        String key = compare == 0 ? "priceNear" : compare < 0 ? "priceAbove"
                : difference.divide(reference, 4, RoundingMode.HALF_UP).compareTo(FAR_BELOW) >= 0 ? "priceFarBelow" : "priceBelow";
        String code = lineCode(language(LegRole.FARMER));
        whisper(WhisperKind.PRICE_REFERENCE, lines.get(key, code, Map.of(
                "source", s.sourceName(),
                "date", SpokenNumbers.date(s.priceDate(), code),
                "market", s.market(),
                "crop", CropNames.spoken(s.commodity(), s.variety(), code),
                "reference", SpokenNumbers.words(reference, code),
                "difference", SpokenNumbers.words(difference.abs(), code))), false);
    }

    /** Rupees per quintal, or null for a unit that cannot be compared. */
    private static BigDecimal perQuintal(Offer offer) {
        if (offer.price() == null || offer.unit() == null) return null;
        return switch (Offer.unit(offer.unit())) {
            case "quintal" -> offer.price();
            case "kg", "kilo", "kilogram" -> offer.price().multiply(BigDecimal.valueOf(100));
            case "tonne", "ton" -> offer.price().divide(BigDecimal.TEN, 2, RoundingMode.HALF_UP);
            default -> null;
        };
    }

    // ---- the deal's voice ----

    private final class DeskOutput implements DealDesk.Output {
        @Override
        public void prepare(Terms terms) {
            for (LegRole role : LegRole.values()) readbackText(terms, language(role));
        }

        @Override
        public void readback(LegRole role, Terms terms, Consumer<Boolean> played) {
            Language lang = language(role);
            String text = readbackText(terms, lang);
            voice(role).say(new LegVoice.Line(text, lang, true, outcome -> played.accept(outcome == PlaybackOutcome.PLAYED)));
            ledger.assistant(role, text);
        }

        @Override
        public void say(LegRole role, String lineKey) {
            sayAssistant(role, lines.get(lineKey, language(role).code()), false, null);
        }

        @Override
        public void agreed(Terms terms) {
            MediationSession.this.agreed(terms);
        }

        @Override
        public void after(Duration delay, Runnable tick) {
            try {
                CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS, executor).execute(tick);
            } catch (RejectedExecutionException e) {
                log.debug("Deal timer not set on call {}: shutting down", callId);
            }
        }
    }

    private String readbackText(Terms t, Language lang) {
        String code = lineCode(lang);
        String notStated = lines.get("notStated", code);
        Language wording = lines.hasOwnLines(lang.code()) ? lang : ENGLISH;
        return rendered(lines.get("readback", code, Map.of(
                "quantity", t.quantity() == null ? notStated : CropNames.quantity(t.quantity(), code),
                "crop", CropNames.spoken(t.commodity(), t.variety(), code),
                "price", SpokenNumbers.words(t.price(), code),
                "unit", CropNames.unit(t.unit(), code),
                "pickup", t.pickup() == null ? notStated : localized(t.pickup(), wording),
                "payment", t.payment() == null ? notStated : localized(t.payment(), wording))), lang);
    }

    /** A language without its own written lines is built in English, figures included, and then translated whole. */
    private String lineCode(Language lang) {
        return lines.hasOwnLines(lang.code()) ? lang.code() : ENGLISH.code();
    }

    /** The fixed line itself where it is written in this language, otherwise its translation, English if that fails. */
    private String rendered(String line, Language lang) {
        if (lines.hasOwnLines(lang.code())) return line;
        return localizedPhrases.computeIfAbsent(lang.code() + "|" + line, k -> {
            String t = withoutThinking(reader.localize(line, lang));
            return t == null ? line : t;
        });
    }

    /** Cached, so the model is asked once, outside the deal lock, and both read-back steps say the same words. */
    private String localized(String phrase, Language lang) {
        // The phrase came from the model's reading of a turn, and English returns it untranslated: clean both ends.
        String source = withoutThinking(phrase);
        if (source == null) return lines.get("notStated", lineCode(lang));
        return localizedPhrases.computeIfAbsent(lang.code() + "|" + source, k -> {
            String t = withoutThinking(reader.localize(source, lang));
            return t == null ? source : t;
        });
    }

    /**
     * Model or translator output with any reasoning removed, including tags that only appeared once the JSON was
     * decoded (a unicode-escaped {@code <think>} in the JSON string). Null when nothing clean is left, so the caller treats it as no answer.
     */
    static String withoutThinking(String text) {
        if (text == null) return null;
        String clean = ReasoningText.strip(text);
        return clean.isEmpty() || ReasoningText.hasTag(clean) ? null : clean;
    }

    private void agreed(Terms terms) {
        synchronized (lock) {
            buyerEndReason = "deal_agreed";
        }
        side.outcome("deal_confirmed", DealDesk.summary(terms, buyerName()));
        // Both said yes to the terms: ask both whether to end the call, then whether G-One may remember it.
        closingStep(side.closing().start());
    }

    // ---- the end of the call, asked of both parties (agent/closing) ----

    /** A turn answering the end-of-call check, or a short "bye" outside any read-back. True when handled here. */
    private boolean closingTurn(Turn turn) {
        CallClosing closing = side.closing();
        LegRole speaker = turn.role();
        if (closing.active()) {
            closingStep(closing.heard(speaker, turn.text()));
            return true;
        }
        boolean deskIdle = desk.awaiting(LegRole.FARMER).isEmpty() && desk.awaiting(LegRole.BUYER).isEmpty();
        if (deskIdle && closing.mayAsk(speaker) && CallClosing.saysDone(turn.text())) {
            closingStep(closing.start());
            return true;
        }
        closing.turnTaken(speaker);
        return false;
    }

    private void closingStep(CallClosing.Step step) {
        if (step.finished()) {
            synchronized (lock) {
                phase = Phase.OVER;
            }
            // The buyer's leg ends first, then the farmer hears the goodbye and the call ends.
            sayClosing(LegRole.BUYER, ClosingLines.goodbye(language(LegRole.BUYER).code()), o -> {
                hangUpBuyer();
                sayClosing(LegRole.FARMER, ClosingLines.goodbye(language(LegRole.FARMER).code()), x -> side.hangUp());
            });
            return;
        }
        if (step.resumed()) {
            for (LegRole role : LegRole.values()) sayClosing(role, ClosingLines.carryOn(language(role).code()), null);
            return;
        }
        for (CallClosing.Ask ask : step.asks()) {
            sayClosing(ask.role(), ClosingLines.ask(ask.question(), language(ask.role()).code()),
                    o -> waitForAnswer(ask.role(), ask.token()));
        }
    }

    private void waitForAnswer(LegRole role, long token) {
        try {
            CompletableFuture.delayedExecutor(CallClosing.ANSWER_WAIT_MS, TimeUnit.MILLISECONDS, executor)
                    .execute(() -> enqueue(() -> closingStep(side.closing().timeout(role, token))));
        } catch (RejectedExecutionException e) {
            log.debug("No answer timer on call {}: shutting down", callId);
        }
    }

    /** A fixed closing line, already written in the leg's language. */
    private void sayClosing(LegRole role, String text, Consumer<PlaybackOutcome> done) {
        Language lang = language(role);
        Leg leg = role == LegRole.FARMER ? farmer.farmerLeg() : buyer;
        voice(role).say(new LegVoice.Line(text, lang, false, done));
        ledger.assistant(role, text);
        bus.publish(new ConsoleEvent.AssistantSaid(callId, clock.millis(), leg.id(), role, lang.code(), text));
        side.note(Speaker.ASSISTANT, text);
    }

    // ---- helpers ----

    private void sayAssistant(LegRole role, String line, boolean isProtected, Consumer<PlaybackOutcome> done) {
        String text = rendered(line, language(role));
        Language lang = language(role);
        Leg leg = role == LegRole.FARMER ? farmer.farmerLeg() : buyer;
        voice(role).say(new LegVoice.Line(text, lang, isProtected, done));
        ledger.assistant(role, text);
        bus.publish(new ConsoleEvent.AssistantSaid(callId, clock.millis(), leg.id(), role, lang.code(), text));
        side.note(Speaker.ASSISTANT, text);
    }

    /** A line that is already in {@code language}, so nothing translates it. */
    private void sayFixed(LegRole role, String text, Language language) {
        Leg leg = role == LegRole.FARMER ? farmer.farmerLeg() : buyer;
        voice(role).say(new LegVoice.Line(text, language, false, null));
        ledger.assistant(role, text);
        bus.publish(new ConsoleEvent.AssistantSaid(callId, clock.millis(), leg.id(), role, language.code(), text));
        side.note(Speaker.ASSISTANT, text);
    }

    private LegVoice voice(LegRole role) {
        return role == LegRole.FARMER ? farmerVoice : buyerVoice;
    }

    private Language language(LegRole role) {
        return role == LegRole.FARMER ? farmerLanguage : buyer.language();
    }

    private void publishHeard(Turn turn) {
        bus.publish(ConsoleEvent.TurnHeard.of(callId, turn, clock.millis()));
    }

    private List<String> recent() {
        synchronized (lock) {
            return new ArrayList<>(recent);
        }
    }

    private void remember(String text) {
        synchronized (lock) {
            recent.addLast(text);
            while (recent.size() > RECENT_LINES) recent.removeFirst();
        }
    }

    private void buyerAudio(AudioFrame frame) {
        SttSession s;
        synchronized (lock) {
            s = buyerSession;
        }
        if (s == null) return;
        try {
            s.feed(frame);
        } catch (RuntimeException e) {
            log.debug("Feeding audio failed on buyer leg {}", buyer.id());
        }
    }

    private void hangUpBuyer() {
        try {
            buyer.hangup();
        } catch (RuntimeException e) {
            log.warn("Hanging up buyer leg {} failed", buyer.id(), e);
        }
    }

    private void buyerEnded() {
        String reason;
        SttSession toClose;
        synchronized (lock) {
            if (buyerLegEnded) return;
            buyerLegEnded = true;
            reason = buyerEndReason;
            toClose = buyerSession;
            buyerSession = null;
        }
        buyerVoice.close();
        closeQuietly(toClose);
        bus.publish(new ConsoleEvent.LegEnded(callId, clock.millis(), buyer.id(), LegRole.BUYER, reason));
        // Acceptance and departure must queue their farmer lines in one order. The transport cleanup above stays prompt.
        enqueue(this::buyerDeparted);
    }

    /**
     * The buyer's leg ended: the mediation lane's last step for it. A buyer who leaves mid-check counts as no answer
     * ({@link CallClosing#left}); the farmer may still be asked, or, when every answer is in, hears the goodbye instead
     * of the buyer-left line. Farmer lines wait for any intake reply still playing, and the assistant gets the farmer
     * back only after the last of them.
     */
    private void buyerDeparted() {
        Phase before;
        synchronized (lock) {
            before = phase;
            phase = Phase.OVER;
        }
        CallClosing.Step left = side.closing().left(LegRole.BUYER);
        if (before == Phase.OVER) return; // a decline, the farmer's hang-up or the goodbye already said the last line
        String farmerCode = language(LegRole.FARMER).code();
        if (left.finished()) {
            side.takeOver(() -> sayClosing(LegRole.FARMER, ClosingLines.goodbye(farmerCode), x -> side.hangUp()));
            return;
        }
        List<CallClosing.Ask> asks = left.asks();
        side.takeOver(() -> {
            if (asks.isEmpty()) {
                sayAssistant(LegRole.FARMER, lines.get("farmerBuyerLeft", farmerCode), false, o -> side.release());
                return;
            }
            sayAssistant(LegRole.FARMER, lines.get("farmerBuyerLeft", farmerCode), false, null);
            for (int k = 0; k < asks.size(); k++) {
                CallClosing.Ask ask = asks.get(k);
                boolean last = k == asks.size() - 1;
                sayClosing(ask.role(), ClosingLines.ask(ask.question(), language(ask.role()).code()), o -> {
                    waitForAnswer(ask.role(), ask.token());
                    if (last) side.release();
                });
            }
        });
    }

    private void closeQuietly(SttSession s) {
        if (s == null) return;
        try {
            s.close();
        } catch (RuntimeException e) {
            log.warn("Closing speech-to-text failed on buyer leg {}", buyer.id(), e);
        }
    }

    /** Turns run one at a time, in order, off the speech-to-text thread. */
    private void enqueue(Runnable task) {
        boolean start;
        synchronized (lock) {
            lane.addLast(task);
            start = !laneBusy;
            if (start) laneBusy = true;
        }
        if (start) submit(this::drain);
    }

    private void drain() {
        while (true) {
            Runnable next;
            synchronized (lock) {
                next = lane.pollFirst();
                if (next == null) {
                    laneBusy = false;
                    return;
                }
            }
            try {
                next.run();
            } catch (RuntimeException e) {
                log.error("A mediation step failed on call {}", callId, e);
            }
        }
    }

    private void submit(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("A mediation step was not run for call {}: the executor is shut down", callId);
        }
    }
}
