package com.example.g_one.agent.pipeline;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.agent.AgentBrain.Line;
import com.example.g_one.agent.AgentBrain.Reply;
import com.example.g_one.agent.AgentBrain.Speaker;
import com.example.g_one.agent.closing.CallClosing;
import com.example.g_one.agent.closing.ClosingLines;
import com.example.g_one.agent.deal.ReplyGuard;
import com.example.g_one.agent.language.LanguageFlow;
import com.example.g_one.agent.language.LanguageLines;
import com.example.g_one.agent.llm.ReasoningText;
import com.example.g_one.agent.mediation.MediationEngine;
import com.example.g_one.agent.mediation.MediationSession;
import com.example.g_one.agent.mediation.ScamPatterns;
import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.Language;
import com.example.g_one.core.Leg;
import com.example.g_one.core.LegRegistry;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.PlaybackOutcome;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.SttListener;
import com.example.g_one.core.SttSession;
import com.example.g_one.core.TextToSpeech;
import com.example.g_one.core.TextToSpeech.Synthesis;
import com.example.g_one.core.Turn;
import com.example.g_one.speech.turn.Phrases;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs each farmer leg end to end: load memory, greet, listen, think, speak, stop for barge-in, and hand the transcript
 * to the {@link CallEndHandler}s at hang-up. Buyer legs are ignored until mediation (Phase 2).
 *
 * <p>How one farmer leg works:
 * <ol>
 *   <li>When the leg is announced, publish {@code call_started} and {@code leg_joined}, then on the executor: load
 *       memory by caller key ({@link FarmerMemory#withheld()} without one, and then no {@code memory_loaded}), open the
 *       speech-to-text session and speak the brain's greeting.</li>
 *   <li>The leg's audio goes to the session. Each finished turn is published ({@code turn_heard}) and answered by the
 *       brain on the executor; the reply is published ({@code assistant_said}), synthesised at 8 kHz and played.</li>
 *   <li>Only the newest turn's reply reaches the farmer. Every new turn, barge-in or hang-up moves the leg to a new
 *       "generation"; a reply, a synthesis frame or a pending hang-up from an older generation is dropped.</li>
 *   <li>Barge-in: speech start while the assistant is speaking (synthesising or still playing) cancels the synthesis
 *       and clears the leg's queued audio.</li>
 *   <li>A reply with {@link Reply#endCall()} hangs up once its audio has been played, unless the farmer speaks
 *       first.</li>
 *   <li>A leg with no language yet hears one English question instead of the greeting. The answer sets the language
 *       ({@link LanguageFlow}), the speech-to-text session follows it, a short confirmation is spoken, then the greeting
 *       in that language, then the reply to what they said if it was more than the language's name. Later turns can
 *       change the language again; that is silent, and shows on the console.</li>
 *   <li>When the leg ends: close the session, cancel speech, publish {@code leg_ended} and {@code call_ended}, and run
 *       the end handlers on the executor.</li>
 * </ol>
 *
 * <p>Threading: leg, speech-to-text and synthesis callbacks only update state and hand slow work (memory, brain, end
 * handlers) to the executor, so they never block. Per-leg state is guarded by one lock that is never held while calling
 * out to the leg, a session, a synthesis, the brain or the console bus. Exceptions from any collaborator are logged and
 * never stop the call. Logs carry ids only, never what was said.
 */
public final class CallPipeline {
    private static final Logger log = LoggerFactory.getLogger(CallPipeline.class);

    static final String HINDI_APOLOGY = "माफ़ कीजिए, कुछ गड़बड़ हो गई। कृपया फिर से बोलिए।";
    static final String ENGLISH_APOLOGY = "Sorry, something went wrong. Please say that again.";
    /**
     * How long after the assistant stops speaking a turn is still checked for being its own echo. Prisma sends a final
     * transcript only after the speech ends, so an echo picked up during the last words arrives a second or two later.
     */
    static final long ECHO_TAIL_MS = 3_000;

    private final AgentBrain brain;
    private final SpeechToText speechToText;
    private final TextToSpeech textToSpeech;
    private final MemoryStore memory;
    private final ConsoleBus bus;
    private final List<CallEndHandler> endHandlers;
    private final Executor executor;
    private final LongSupplier clock;
    private final MediationEngine mediation;
    private final Language.Registry languages;
    private final Map<CallId, FarmerLeg> calls = new ConcurrentHashMap<>();

    /**
     * @param endHandlers run in order at each farmer hang-up; may be empty
     * @param executor runs memory loads, brain calls and end handlers; must not run tasks on the submitting thread
     *     (tests use a manual executor that runs them when told)
     * @param clock epoch milliseconds for console events and call times
     */
    public CallPipeline(AgentBrain brain, SpeechToText speechToText, TextToSpeech textToSpeech, MemoryStore memory,
                        ConsoleBus bus, List<CallEndHandler> endHandlers, Executor executor, LongSupplier clock) {
        this(brain, speechToText, textToSpeech, memory, bus, endHandlers, executor, clock, null);
    }

    /** @param mediation runs calls once a buyer joins; null leaves buyer legs ignored */
    public CallPipeline(AgentBrain brain, SpeechToText speechToText, TextToSpeech textToSpeech, MemoryStore memory,
                        ConsoleBus bus, List<CallEndHandler> endHandlers, Executor executor, LongSupplier clock,
                        MediationEngine mediation) {
        this(brain, speechToText, textToSpeech, memory, bus, endHandlers, executor, clock, mediation, null);
    }

    /** @param languages what a leg may choose; null keeps every leg in the language it started with */
    public CallPipeline(AgentBrain brain, SpeechToText speechToText, TextToSpeech textToSpeech, MemoryStore memory,
                        ConsoleBus bus, List<CallEndHandler> endHandlers, Executor executor, LongSupplier clock,
                        MediationEngine mediation, Language.Registry languages) {
        this.mediation = mediation;
        this.languages = languages;
        this.brain = Objects.requireNonNull(brain, "brain");
        this.speechToText = Objects.requireNonNull(speechToText, "speechToText");
        this.textToSpeech = Objects.requireNonNull(textToSpeech, "textToSpeech");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.endHandlers = List.copyOf(Objects.requireNonNull(endHandlers, "endHandlers"));
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Starts handling every leg the registry announces from now on. */
    public void attach(LegRegistry registry) {
        registry.onLegAdded(this::onLegAdded);
    }

    /** Handles one new leg. Returns quickly; the work runs on the executor. */
    void onLegAdded(Leg leg) {
        if (leg.role() != LegRole.FARMER) {
            FarmerLeg farmer = calls.get(leg.callId());
            if (mediation == null || farmer == null || !farmer.buyerJoined(leg)) {
                log.info("Ending {} leg {} of call {}: nothing to mediate", leg.role().wire(), leg.id(), leg.callId());
                try {
                    leg.hangup();
                } catch (RuntimeException e) {
                    log.warn("Hanging up leg {} failed", leg.id(), e);
                }
            }
            return;
        }
        new FarmerLeg(leg).begin();
    }

    /** The spoken apology used when the brain throws or returns nothing, against its contract. */
    static Reply apology(Language language) {
        boolean english = language.code().toLowerCase(Locale.ROOT).startsWith("en");
        return new Reply(english ? ENGLISH_APOLOGY : HINDI_APOLOGY, language, false);
    }

    private long now() {
        return clock.getAsLong();
    }

    private void publish(ConsoleEvent event) {
        bus.publish(event); // the bus already isolates failing subscribers
    }

    /** One farmer leg's state. All mutable fields are guarded by {@link #lock} unless noted. */
    private final class FarmerLeg implements SttListener, MediationEngine.FarmerSide {
        private final Leg leg;
        private final CallId callId;
        private final long startedMs;
        private final Object lock = new Object();
        /**
         * Held while frames are queued on the leg and while the leg's playback is cleared, so a frame from a
         * superseded generation can never be queued after the clear that was meant to drop it. Lock order is
         * {@code playLock} then {@link #lock} (a clear can complete a playback future whose callback takes
         * {@code lock}); {@code playLock} is never taken while holding {@code lock}.
         */
        private final Object playLock = new Object();
        /** Read without the lock by synthesis sinks; changed only while holding {@link #lock}. */
        private final AtomicLong generation = new AtomicLong();
        private final List<Line> lines = new ArrayList<>();

        private boolean ended;
        private boolean speaking;
        /** Clears started by intake barge-ins but not yet completed. Guarded by {@link #lock}. */
        private int clearing;
        /** The assistant's latest line and when it stopped being heard: its echo can still come back for a moment. */
        private String lastSaid = "";
        private long quietSinceMs;
        private boolean hungUpByUs;
        private CallContext context;
        private SttSession session;
        private Synthesis synthesis;
        /** The session audio is fed to. Volatile so the audio thread reads it without the lock. */
        private volatile SttSession feedTarget;
        private volatile boolean feedFailureLogged;
        /** Set while a buyer is being asked or the call is in mediation. Volatile so the STT thread reads it freely. */
        private volatile MediationSession mediationSession;
        private String outcome;
        private String dealSummary;
        /**
         * A request heard in the same turn that chose the language, held back because the greeting after it asked the
         * farmer something (a returning farmer's name check). Answered right after the farmer's next turn once the brain
         * no longer waits for an identity answer; dropped if that turn did not settle it. Guarded by {@link #lock}.
         */
        private Turn heldRequest;
        /**
         * Set once mediation takes the farmer's leg over: no intake reply starts after it, so a reply that was still
         * being prepared cannot land on top of a mediation line. A turn heard meanwhile is held and answered by
         * {@link #release()}, which clears this. Guarded by {@link #lock}.
         */
        private boolean intakeClosed;
        /**
         * Runs once nothing the intake said is playing any more. Taken by whichever step supersedes that reply, so it
         * is never left waiting on a reply that no longer exists. Hand-offs chain in the order they were deferred, so
         * one that a later hand-off supersedes still runs, ahead of the later one. Guarded by {@link #lock}.
         */
        private Runnable whenFree;
        /**
         * A turn heard while mediation had the leg, written under the same lock that reads {@link #intakeClosed}, so a
         * release either sees it or made it unnecessary. Answered by {@link #release()} once the assistant has the leg
         * back, at the generation that release binds it to. Guarded by {@link #lock}.
         */
        private Turn heldTurn;
        /** Null for a leg started with a fixed language. */
        private final LanguageFlow languageFlow;
        /** The end-of-call check and every party's answer to the save question. Thread safe on its own. */
        private final CallClosing closing;

        FarmerLeg(Leg leg) {
            this.leg = leg;
            this.callId = leg.callId();
            this.startedMs = now();
            this.languageFlow = languages != null && leg.languagePending() ? new LanguageFlow(languages::find) : null;
            this.closing = new CallClosing(leg.callerKey().isEmpty());
        }

        void begin() {
            calls.put(callId, this);
            publish(new ConsoleEvent.CallStarted(callId, startedMs, leg.languagePending() ? Language.AUTO_CODE : leg.language().code()));
            publish(ConsoleEvent.LegJoined.of(leg, startedMs));
            leg.onEnded(this::onEnded);
            leg.onAudio(this::onAudio);
            submit("start", this::start);
        }

        // ---- start: memory, speech-to-text session, greeting (on the executor) ----

        private void start() {
            LoadedMemory loaded = loadMemory();
            CallContext ctx = new CallContext(callId, leg, leg.language(), loaded.memory());
            synchronized (lock) {
                if (ended) {
                    return;
                }
                context = ctx;
            }
            if (loaded.publish()) {
                publish(ConsoleEvent.MemoryLoaded.of(callId, loaded.memory(), now()));
            }

            SttSession opened = null;
            try {
                opened = speechToText.open(leg.id(), LegRole.FARMER, languageFlow != null ? Language.DETECTING : leg.language(), this);
            } catch (RuntimeException e) {
                log.error("Could not open speech-to-text for leg {} of call {}; the farmer will not be heard",
                        leg.id(), callId, e);
            }
            boolean endedMeanwhile;
            long greetingGeneration;
            synchronized (lock) {
                endedMeanwhile = ended;
                if (!ended) {
                    session = opened;
                    feedTarget = opened;
                }
                greetingGeneration = generation.get();
            }
            if (endedMeanwhile) {
                closeQuietly(opened);
                return;
            }

            if (languageFlow != null) {
                languageFlow.asked();
                deliver(greetingGeneration, new Reply(LanguageLines.opener(LegRole.FARMER), leg.language(), false));
                return;
            }
            Reply greeting = askBrain("greet", () -> brain.greet(ctx), ctx.language());
            deliver(greetingGeneration, greeting);
        }

        private LoadedMemory loadMemory() {
            Optional<CallerKey> key = leg.callerKey();
            if (key.isEmpty()) {
                return new LoadedMemory(FarmerMemory.withheld(), false);
            }
            try {
                memory.callStarted(key.get(), callId, startedMs);
                FarmerMemory m = memory.load(key.get()).orElseGet(() -> FarmerMemory.empty(key.get()));
                if (!m.callerKey().equals(key)) {
                    log.error("The memory store returned another caller's memory for call {}; using none", callId);
                    return new LoadedMemory(FarmerMemory.empty(key.get()), false);
                }
                return new LoadedMemory(m, true);
            } catch (RuntimeException e) {
                log.warn("Could not load memory for call {}; treating the farmer as new", callId, e);
                return new LoadedMemory(FarmerMemory.empty(key.get()), false);
            }
        }

        // ---- audio and speech-to-text events (on transport / STT threads; never block) ----

        private void onAudio(AudioFrame frame) {
            SttSession target = feedTarget;
            if (target == null) {
                return; // not open yet, or the leg ended
            }
            try {
                target.feed(frame);
            } catch (RuntimeException e) {
                if (!feedFailureLogged) {
                    feedFailureLogged = true;
                    log.warn("Feeding audio to speech-to-text failed on leg {} (logged once)", leg.id(), e);
                }
            }
        }

        /** A buyer leg joined this call. False when there is nothing it could join. */
        boolean buyerJoined(Leg buyer) {
            CallContext ctx;
            MediationSession created;
            synchronized (lock) {
                if (ended || context == null || mediationSession != null) {
                    return false;
                }
                ctx = context;
                created = mediation.session(ctx, this, buyer, speechToText, textToSpeech, executor);
                mediationSession = created;
            }
            created.begin();
            return true;
        }

        @Override
        public void takeOver(Runnable then) {
            Synthesis toCancel = null;
            Runnable runNow = null;
            synchronized (lock) {
                intakeClosed = true;
                // A hand-off deferred earlier still has to run: it may be what frees the farmer's voice, so letting a
                // second one replace it would leave that voice muted for the rest of the call.
                Runnable pending = whenFree;
                whenFree = null;
                Runnable both = chain(pending, then);
                if (speaking || clearing != 0) {
                    // The reply already playing is heard to its end; the line that follows waits for it.
                    whenFree = both;
                } else {
                    generation.incrementAndGet(); // a reply that has not started yet must not start
                    toCancel = synthesis;
                    synthesis = null;
                    runNow = both;
                }
            }
            if (runNow == null) {
                return;
            }
            cancelQuietly(toCancel);
            submit("take over", runNow);
        }

        /** Runs {@code first} then {@code next}: a hand-off that fails must not take the one behind it with it. */
        private static Runnable chain(Runnable first, Runnable next) {
            if (first == null) {
                return next;
            }
            return () -> {
                try {
                    first.run();
                } finally {
                    next.run();
                }
            };
        }

        @Override
        public void release() {
            Runnable free;
            Turn held;
            long heldGeneration;
            boolean defer;
            synchronized (lock) {
                mediationSession = null;
                intakeClosed = false;
                // A hand-off still waiting was queued for a reply that never got to finish. Running it is what frees a
                // voice it holds, so it is taken rather than dropped.
                held = heldTurn;
                heldTurn = null;
                // Bound here, not when the answer is prepared: a turn heard after this release supersedes the held one.
                heldGeneration = generation.incrementAndGet();
                defer = speaking || clearing != 0;
                if (defer) {
                    if (held != null) {
                        Turn answer = held;
                        whenFree = chain(whenFree, () -> answerHeldTurn(answer, heldGeneration));
                    }
                    free = null;
                } else {
                    free = takeWhenFree();
                }
            }
            if (defer) {
                return;
            }
            if (free == null && held == null) {
                return;
            }
            // One step, so the leg is free before the held turn is answered on it.
            submit("resume", () -> {
                if (free != null) {
                    free.run();
                }
                if (held != null) {
                    answerHeldTurn(held, heldGeneration);
                }
            });
        }

        @Override
        public void hangUp() {
            synchronized (lock) {
                hungUpByUs = true;
            }
            try {
                leg.hangup();
            } catch (RuntimeException e) {
                log.warn("Hanging up leg {} failed", leg.id(), e);
            }
        }

        @Override
        public void note(Speaker speaker, String text) {
            synchronized (lock) {
                if (!ended) {
                    lines.add(new Line(speaker, text));
                }
            }
        }

        @Override
        public void outcome(String newOutcome, String newSummary) {
            synchronized (lock) {
                outcome = newOutcome;
                dealSummary = newSummary;
            }
        }

        @Override
        public CallClosing closing() {
            return closing;
        }

        @Override
        public void onSpeechStart() {
            MediationSession live = mediationSession;
            if (live != null) {
                live.farmerSpeechStart();
                // The farmer can interrupt an unprotected mediation line even after mediation's live phase ends.
                // The intake reply may also still be playing, so its own playback must be cleared below.
            }
            Synthesis toCancel;
            synchronized (lock) {
                if (ended || !speaking) {
                    return;
                }
                generation.incrementAndGet();
                clearing++;
                quiet();
                toCancel = synthesis;
                synthesis = null;
            }
            log.info("Speech detected on leg {} while the assistant is speaking: barge-in accepted", leg.id());
            clearIntakePlayback(toCancel);
        }

        @Override
        public void onFinalTurn(Turn heard) {
            // A spoken secret goes no further than here: mediation still sees the digits to withhold the turn.
            Turn turn = withText(heard, ScamPatterns.masked(heard.text()));
            MediationSession live = mediationSession;
            boolean mediating = live != null && live.live();
            if (!mediating && echoesTheAssistant(turn.text())) {
                log.info("Turn ignored on leg {}: the words heard repeat the assistant's own line (speaker echo)",
                        leg.id());
                return;
            }
            LanguageFlow.Outcome language = languageOutcome(turn);
            switch (language.kind()) {
                case IGNORED -> {
                    return;
                }
                case REASK -> {
                    askLanguageAgain(withLanguage(turn, leg.language()));
                    return;
                }
                case CHOSEN -> {
                    if (!mediating) {
                        languageChosen(turn, language);
                        return;
                    }
                    switchTo(language);
                    heard = withLanguage(heard, language.language());
                    turn = withLanguage(turn, language.language());
                }
                case SWITCHED -> {
                    switchTo(language);
                    heard = withLanguage(heard, language.language());
                    turn = withLanguage(turn, language.language());
                }
                case KEEP -> { }
            }
            if (mediating && live.farmerTurn(heard)) {
                note(Speaker.FARMER, turn.text());
                publish(ConsoleEvent.TurnHeard.of(callId, turn, now()));
                return;
            }
            if (closingTurn(turn)) {
                return;
            }
            answerOrHold(heard, turn, false);
        }

        /**
         * Answers {@code answerTo} with the brain, or holds it while mediation has the leg. The buyer's yes can land
         * after this turn's live check, so once the intake is closed the turn is first offered to the session that now
         * has the leg. That offer is made outside this leg's lock, which mediation's lock comes before; a turn it
         * declines comes back here and is held under the lock that saw the intake closed. {@code heard} is the turn
         * with its words unmasked, as mediation reads it.
         */
        private void answerOrHold(Turn heard, Turn answerTo, boolean offered) {
            List<Line> history = null;
            CallContext ctx = null;
            long turnGeneration = 0;
            boolean wasSpeaking = false;
            boolean closed = false;
            boolean offer = false;
            Synthesis toCancel = null;
            synchronized (lock) {
                if (ended || context == null) {
                    return;
                }
                if (intakeClosed && !offered) {
                    offer = true;
                } else {
                    if (speaking && Phrases.isBackchannel(answerTo.text())) {
                        log.info("Barge-in ignored on leg {}: backchannel while the assistant is speaking", leg.id());
                        return; // "haan" while the assistant talks is listening, not a reply: let it finish
                    }
                    history = List.copyOf(lines);
                    lines.add(new Line(Speaker.FARMER, answerTo.text()));
                    turnGeneration = generation.incrementAndGet();
                    wasSpeaking = speaking;
                    if (wasSpeaking) clearing++;
                    quiet();
                    toCancel = synthesis;
                    synthesis = null;
                    ctx = context;
                    closed = intakeClosed;
                    if (closed) {
                        // Held under the same lock that decided it: a release that runs afterwards sees this turn, and
                        // one that ran before it made closed false, so the turn cannot be written where nobody reads it.
                        heldTurn = answerTo;
                    }
                }
            }
            if (offer) {
                // The intake closed only after a take-over, so the session's phase is settled: live, or over for good.
                MediationSession owner = mediationSession;
                if (owner != null && owner.farmerTurn(heard)) {
                    note(Speaker.FARMER, answerTo.text());
                    publish(ConsoleEvent.TurnHeard.of(callId, answerTo, now()));
                    return;
                }
                answerOrHold(heard, answerTo, true);
                return;
            }
            if (wasSpeaking) {
                log.info("Turn heard on leg {} while the assistant is speaking: barge-in accepted", leg.id());
                clearIntakePlayback(toCancel); // a turn with no speech start first still interrupts the assistant
            }
            publish(ConsoleEvent.TurnHeard.of(callId, answerTo, now()));
            if (closed) {
                // Mediation has the leg: no brain call now, and no reply of ours over its lines. It is answered once
                // the assistant has the farmer back.
                return;
            }
            long respondGeneration = turnGeneration;
            CallContext respondCtx = ctx;
            List<Line> respondHistory = history;
            submit("respond", () -> respond(respondGeneration, respondCtx, respondHistory, answerTo));
        }

        private void respond(long turnGeneration, CallContext ctx, List<Line> history, Turn answerTo) {
            if (generation.get() != turnGeneration) {
                return; // a newer turn already arrived; skip the brain call
            }
            Reply reply = askBrain("respond", () -> brain.respond(ctx, history, answerTo), ctx.language());
            if (endCheckInstead(turnGeneration, reply)) {
                return;
            }
            Turn held = takeHeldRequest();
            if (held == null) {
                deliver(turnGeneration, reply);
            } else if (brain.awaitingIdentity(callId)) {
                // The farmer moved on without settling who they are: their newer turn is the one answered.
                log.info("A request held from the language turn was dropped on leg {}: the name check is still open",
                        leg.id());
                deliver(turnGeneration, reply);
            } else {
                deliver(turnGeneration, reply, () -> answerHeld(turnGeneration, held));
            }
        }

        private Turn takeHeldRequest() {
            synchronized (lock) {
                Turn held = heldRequest;
                heldRequest = null;
                return held;
            }
        }

        /** Under {@link #lock}: whoever superseded the reply mediation waited for takes its hand-off. */
        private Runnable takeWhenFree() {
            Runnable free = whenFree;
            whenFree = null;
            return free;
        }

        /**
         * Answers a turn heard while mediation had the leg, now that the assistant has it back. {@code replyGeneration}
         * is the one {@link #release()} bound it to: a turn heard since then supersedes it, as it would any other reply.
         */
        private void answerHeldTurn(Turn turn, long replyGeneration) {
            CallContext ctx;
            List<Line> history;
            CallClosing.Question answering;
            synchronized (lock) {
                if (ended || context == null || intakeClosed || generation.get() != replyGeneration) {
                    return;
                }
                if (closing.finished()) {
                    return; // the goodbye is on its way
                }
                ctx = context;
                history = List.copyOf(lines);
                answering = closing.pending().orElse(null);
            }
            if (answering != null) {
                // The farmer was answering the end-of-call question mediation asked them.
                ReplyGuard.Reading reading = readYesNo(ClosingLines.ask(answering, ctx.language().code()), turn.text());
                if (generation.get() != replyGeneration) {
                    return;
                }
                closingStep(replyGeneration, closing.heard(LegRole.FARMER, reading));
                return;
            }
            Turn heard = withLanguage(turn, ctx.language());
            Reply reply = askBrain("respond", () -> brain.respond(ctx, history, heard), ctx.language());
            // A goodbye asks first here too: a held "bye" never hangs up without the end-of-call check.
            if (!endCheckInstead(replyGeneration, reply)) {
                deliver(replyGeneration, reply);
            }
        }

        /**
         * Answers the request held from the language turn, now that identity is settled, in the leg's current language.
         * Its words never answered the name check: the brain reads them only after that decision.
         */
        private void answerHeld(long turnGeneration, Turn held) {
            if (generation.get() != turnGeneration) {
                return;
            }
            CallContext ctx;
            List<Line> history;
            synchronized (lock) {
                if (ended || context == null) {
                    return;
                }
                ctx = context;
                history = List.copyOf(lines);
            }
            Turn request = withLanguage(held, ctx.language());
            Reply reply = askBrain("respond", () -> brain.respond(ctx, history, request), ctx.language());
            if (!endCheckInstead(turnGeneration, reply)) {
                deliver(turnGeneration, reply);
            }
        }

        /**
         * A brain reply that would hang up (a goodbye) starts the end-of-call check instead, so the farmer is always asked
         * whether to end the call and whether it may be remembered before G-One hangs up. True when the check was started
         * and the reply must not be spoken. Once the check has finished, a hang-up goes ahead as it is.
         */
        private boolean endCheckInstead(long turnGeneration, Reply reply) {
            if (!reply.endCall() || closing.finished()) {
                return false;
            }
            CallClosing.Step step;
            synchronized (lock) {
                if (ended || generation.get() != turnGeneration) {
                    return true; // superseded: nothing to say
                }
                step = closing.start();
            }
            if (step.asks().isEmpty()) {
                // Already asking: the reply is said without hanging up.
                deliver(turnGeneration, new Reply(reply.text(), reply.language(), false));
                return true;
            }
            log.info("The brain's goodbye on call {} starts the end-of-call check", callId);
            closingStep(turnGeneration, step);
            return true;
        }

        // ---- the end of the call: "end the call?", then "may we remember?" (decided by code) ----

        /**
         * A turn that answers the end-of-call check, or a short "bye / that's all" that starts it. True when the turn was
         * handled here and must not reach the brain. Never starts while the name check waits or a request is held.
         */
        private boolean closingTurn(Turn turn) {
            boolean identityOpen = brain.awaitingIdentity(callId);
            CallClosing.Step step;
            CallClosing.Question answering = null;
            Language answerLanguage = null;
            long turnGeneration;
            boolean wasSpeaking;
            Synthesis toCancel;
            synchronized (lock) {
                if (ended || context == null) {
                    return false;
                }
                if (intakeClosed) {
                    // Mediation still has the leg: the turn is held and read once the assistant has the farmer back.
                    return false;
                }
                if (closing.finished()) {
                    return true; // the goodbye is on its way
                }
                if (closing.active()) {
                    // Read on the executor: a reply the words do not settle may take one small model call.
                    step = null;
                    answering = closing.pending().orElse(null);
                    answerLanguage = context.language();
                } else if (!identityOpen && heldRequest == null && closing.mayAsk(LegRole.FARMER)
                        && CallClosing.saysDone(turn.text())) {
                    step = closing.start();
                } else {
                    closing.turnTaken(LegRole.FARMER);
                    return false;
                }
                lines.add(new Line(Speaker.FARMER, turn.text()));
                turnGeneration = generation.incrementAndGet();
                wasSpeaking = speaking;
                if (wasSpeaking) clearing++;
                quiet();
                toCancel = synthesis;
                synthesis = null;
            }
            if (wasSpeaking) {
                clearIntakePlayback(toCancel);
            }
            publish(ConsoleEvent.TurnHeard.of(callId, turn, now()));
            if (step != null) {
                submit("closing", () -> closingStep(turnGeneration, step));
                return true;
            }
            CallClosing.Question question = answering;
            String asked = question == null ? "" : ClosingLines.ask(question, answerLanguage.code());
            submit("closing", () -> {
                ReplyGuard.Reading reading = readYesNo(asked, turn.text());
                if (generation.get() != turnGeneration) {
                    return; // a newer turn arrived while the answer was read; it is the one that counts
                }
                closingStep(turnGeneration, closing.heard(LegRole.FARMER, reading));
            });
            return true;
        }

        /** The layered yes/no reading; on any failure, the words alone. */
        private ReplyGuard.Reading readYesNo(String question, String reply) {
            try {
                ReplyGuard.Reading reading = brain.readYesNo(callId, question, reply);
                return reading == null ? ReplyGuard.Reading.UNCLEAR : reading;
            } catch (RuntimeException e) {
                log.warn("Reading a yes or no failed on call {}; using the words alone", callId, e);
                return ReplyGuard.read(reply);
            }
        }

        private void closingStep(long stepGeneration, CallClosing.Step step) {
            Language lang;
            synchronized (lock) {
                if (ended || context == null) {
                    return;
                }
                lang = context.language();
            }
            if (step.finished()) {
                deliver(stepGeneration, new Reply(ClosingLines.goodbye(lang.code()), lang, true));
            } else if (step.resumed()) {
                deliver(stepGeneration, new Reply(ClosingLines.carryOn(lang.code()), lang, false));
            } else {
                for (CallClosing.Ask ask : step.asks()) {
                    if (ask.role() == LegRole.FARMER) { // a buyer is asked by mediation, on its own leg
                        deliver(stepGeneration, new Reply(ClosingLines.ask(ask.question(), lang.code()), lang, false),
                                () -> waitForAnswer(ask.token()));
                    }
                }
            }
        }

        /** No answer within {@link CallClosing#ANSWER_WAIT_MS}: ask once more, then count it as no answer. */
        private void waitForAnswer(long token) {
            try {
                CompletableFuture.delayedExecutor(CallClosing.ANSWER_WAIT_MS, TimeUnit.MILLISECONDS, executor).execute(() -> {
                    CallClosing.Step step = closing.timeout(LegRole.FARMER, token);
                    if (step.asks().isEmpty() && !step.resumed() && !step.finished()) {
                        return;
                    }
                    long stepGeneration;
                    synchronized (lock) {
                        if (ended) {
                            return;
                        }
                        stepGeneration = generation.incrementAndGet();
                    }
                    closingStep(stepGeneration, step);
                });
            } catch (RejectedExecutionException e) {
                log.debug("No answer timer on leg {}: shutting down", leg.id());
            }
        }

        // ---- the leg's language ----

        private LanguageFlow.Outcome languageOutcome(Turn turn) {
            if (languageFlow == null) {
                return KEEP_LANGUAGE;
            }
            boolean nowSpeaking;
            synchronized (lock) {
                if (ended || context == null) {
                    return IGNORE_TURN;
                }
                nowSpeaking = speaking;
            }
            return languageFlow.onTurn(turn.text(), nowSpeaking);
        }

        /** The leg, its context, its recognition and any mediation all move to the new language. */
        private void switchTo(LanguageFlow.Outcome outcome) {
            Language next = outcome.language();
            String from = leg.languagePending() ? Language.AUTO_CODE : leg.language().code();
            SttSession listening;
            synchronized (lock) {
                context = new CallContext(callId, leg, next, context.memory());
                listening = session;
            }
            leg.switchLanguage(next);
            if (listening != null) {
                try {
                    listening.retarget(next);
                } catch (RuntimeException e) {
                    log.warn("Changing the speech-to-text language failed on leg {}", leg.id(), e);
                }
            }
            MediationSession inMediation = mediationSession;
            if (inMediation != null) {
                inMediation.farmerLanguage(next);
            }
            publish(new ConsoleEvent.LanguageChanged(callId, now(), leg.id(), LegRole.FARMER, from, next.code(), outcome.reason()));
        }

        /**
         * Confirms the language, greets in it, then answers the turn that chose it if that said anything else, unless the
         * greeting itself asks a question.
         */
        private void languageChosen(Turn turn, LanguageFlow.Outcome outcome) {
            Language next = outcome.language();
            Turn heardIn = withLanguage(turn, next);
            List<Line> before;
            long turnGeneration;
            boolean wasSpeaking;
            Synthesis toCancel;
            synchronized (lock) {
                if (ended || context == null) {
                    return;
                }
                before = new ArrayList<>(lines);
                lines.add(new Line(Speaker.FARMER, heardIn.text()));
                turnGeneration = generation.incrementAndGet();
                wasSpeaking = speaking;
                if (wasSpeaking) clearing++;
                quiet();
                toCancel = synthesis;
                synthesis = null;
            }
            if (wasSpeaking) {
                clearIntakePlayback(toCancel);
            }
            switchTo(outcome);
            publish(ConsoleEvent.TurnHeard.of(callId, heardIn, now()));
            CallContext ctx;
            synchronized (lock) {
                if (context == null) {
                    return;
                }
                ctx = context;
            }
            Reply confirmation = new Reply(LanguageLines.confirmation(next.code()), next, false);
            submit("confirm language", () -> deliver(turnGeneration, confirmation,
                    () -> greetIn(ctx, turnGeneration, heardIn, before, outcome.hasContent())));
        }

        private void greetIn(CallContext ctx, long turnGeneration, Turn turn, List<Line> before, boolean answer) {
            if (generation.get() != turnGeneration) {
                return;
            }
            // The English-first opener already introduced G-One: no second welcome after the confirmation.
            Reply greeting = askBrain("greet", () -> brain.greetAfterLanguageChoice(ctx), ctx.language());
            // A greeting that asks something, such as a returning farmer's name check, waits for its own answer: words
            // said before it was heard cannot answer it. The request is held and answered after that answer.
            // "How can I help?" is a question too, but only an identity question has to be answered first.
            boolean answerNow = answer && !(asksSomething(greeting) && brain.awaitingIdentity(ctx.callId()));
            if (answer && !answerNow) {
                synchronized (lock) {
                    heldRequest = turn;
                }
            }
            deliver(turnGeneration, greeting, !answerNow ? null : () -> {
                if (generation.get() != turnGeneration) {
                    return;
                }
                List<Line> history = new ArrayList<>(before);
                // The history holds what the farmer heard, never model thinking.
                history.add(new Line(Speaker.ASSISTANT,
                        ReasoningText.speakable(greeting.text(), greeting.language().code())));
                Reply reply = askBrain("respond", () -> brain.respond(ctx, history, turn), ctx.language());
                if (!endCheckInstead(turnGeneration, reply)) {
                    deliver(turnGeneration, reply);
                }
            });
        }

        private static boolean asksSomething(Reply greeting) {
            return ReasoningText.speakable(greeting.text(), greeting.language().code()).strip().endsWith("?");
        }

        private void askLanguageAgain(Turn turn) {
            long turnGeneration;
            boolean wasSpeaking;
            Synthesis toCancel;
            synchronized (lock) {
                if (ended || context == null) {
                    return;
                }
                lines.add(new Line(Speaker.FARMER, turn.text()));
                turnGeneration = generation.incrementAndGet();
                wasSpeaking = speaking;
                if (wasSpeaking) clearing++;
                quiet();
                toCancel = synthesis;
                synthesis = null;
            }
            if (wasSpeaking) {
                clearIntakePlayback(toCancel);
            }
            publish(ConsoleEvent.TurnHeard.of(callId, turn, now()));
            Language spoken = languages.find(REASK_LANGUAGE).orElse(leg.language());
            submit("ask language again", () -> deliver(turnGeneration, new Reply(LanguageLines.reask(), spoken, false)));
        }

        @Override
        public void onError(Exception error) {
            log.warn("Speech-to-text reported an error on leg {} of call {}", leg.id(), callId, error);
        }

        // ---- speaking ----

        /** Speaks {@code reply} unless a newer turn, a barge-in or the hang-up superseded {@code replyGeneration}. */
        private void deliver(long replyGeneration, Reply reply) {
            deliver(replyGeneration, reply, null);
        }

        /** @param next runs on the executor once the person has heard all of {@code reply}; null for nothing */
        private void deliver(long replyGeneration, Reply reply, Runnable next) {
            // The last check before the console and speech: model thinking is never shown or spoken.
            String spoken = ReasoningText.speakable(reply.text(), reply.language().code());
            synchronized (lock) {
                if (ended || intakeClosed || generation.get() != replyGeneration) {
                    log.debug("Dropping a superseded reply on leg {}", leg.id());
                    return;
                }
                if (clearing != 0) {
                    // A delayed barge-in clear must finish before this newer answer queues audio on the same leg.
                    whenFree = chain(whenFree, () -> deliver(replyGeneration, reply, next));
                    return;
                }
                lines.add(new Line(Speaker.ASSISTANT, spoken));
                speaking = true;
                lastSaid = spoken;
                // A leg that follows its own turns changes language only through its LanguageFlow, never on one reply.
                if (languageFlow == null && !reply.language().equals(context.language())) {
                    context = new CallContext(callId, leg, reply.language(), context.memory());
                }
            }
            publish(new ConsoleEvent.AssistantSaid(callId, now(), leg.id(), LegRole.FARMER, reply.language().code(),
                    spoken));

            Synthesis started;
            try {
                started = textToSpeech.speak(spoken, reply.language(), leg.playbackSampleRate(),
                        frame -> playFrame(replyGeneration, frame));
            } catch (RuntimeException e) {
                log.warn("Speech synthesis failed to start on leg {}", leg.id(), e);
                afterSynthesis(replyGeneration, reply, next);
                return;
            }
            boolean superseded;
            synchronized (lock) {
                superseded = ended || generation.get() != replyGeneration;
                if (!superseded) {
                    synthesis = started;
                }
            }
            if (superseded) {
                cancelQuietly(started); // whoever superseded it already cleared the leg
                return;
            }
            started.done().whenComplete((ignored, error) -> {
                if (error != null && !isCancellation(error)) {
                    log.warn("Speech synthesis failed on leg {}", leg.id(), error);
                }
                afterSynthesis(replyGeneration, reply, next);
            });
        }

        private void playFrame(long replyGeneration, AudioFrame frame) {
            synchronized (playLock) {
                if (generation.get() != replyGeneration) {
                    return;
                }
                try {
                    leg.play(frame);
                } catch (RuntimeException e) {
                    log.warn("Playing audio failed on leg {}", leg.id(), e);
                }
            }
        }

        /** Every frame of the reply is queued (or synthesis failed): wait for the farmer to hear it. */
        private void afterSynthesis(long replyGeneration, Reply reply, Runnable next) {
            if (generation.get() != replyGeneration) {
                return;
            }
            leg.playbackDone().thenAccept(outcome -> afterPlayback(replyGeneration, reply, outcome, next));
        }

        private void afterPlayback(long replyGeneration, Reply reply, PlaybackOutcome outcome, Runnable next) {
            boolean hangUp = false;
            Runnable free = null;
            synchronized (lock) {
                if (ended || generation.get() != replyGeneration || outcome == PlaybackOutcome.ENDED) {
                    return;
                }
                quiet();
                synthesis = null;
                if (reply.endCall()) {
                    hungUpByUs = true;
                    hangUp = true; // the leg ends here, so a pending hand-off line has nowhere to play
                } else if (whenFree != null) {
                    free = whenFree; // mediation waited for this reply; its own line goes first
                    whenFree = null;
                }
            }
            if (hangUp) {
                log.info("The assistant is ending call {}", callId);
                try {
                    leg.hangup();
                } catch (RuntimeException e) {
                    log.warn("Hanging up leg {} failed", leg.id(), e);
                }
            } else if (free != null) {
                submit("take over", free);
            } else if (next != null) {
                submit("next line", next);
            }
        }

        /** Barge-in: no more frames from {@code toCancel}, then drop what the leg has queued. */
        private void stopSpeaking(Synthesis toCancel) {
            cancelQuietly(toCancel);
            log.info("TTS cancelled on leg {} ({}); clearing queued playback", leg.id(),
                    toCancel != null ? "synthesis was running" : "synthesis had finished");
            synchronized (playLock) {
                try {
                    leg.clearPlayback();
                } catch (RuntimeException e) {
                    log.warn("Clearing playback failed on leg {}", leg.id(), e);
                }
            }
        }

        /** The hand-off cannot start on this leg until every older clear has finished. */
        private void clearIntakePlayback(Synthesis toCancel) {
            Runnable free;
            try {
                stopSpeaking(toCancel);
            } finally {
                synchronized (lock) {
                    clearing--;
                    free = clearing == 0 && !speaking && !ended ? takeWhenFree() : null;
                }
                if (free != null) {
                    submit("take over", free);
                }
            }
        }

        /** Under {@link #lock}: the assistant is no longer speaking. */
        private void quiet() {
            if (speaking) {
                speaking = false;
                quietSinceMs = now();
            }
        }

        /**
         * Whether a turn is the assistant's own voice coming back through the microphone (laptop speakers): heard while
         * it spoke, or within {@link #ECHO_TAIL_MS} after, and made of the words it was saying.
         */
        private boolean echoesTheAssistant(String text) {
            synchronized (lock) {
                boolean recent = speaking || now() - quietSinceMs < ECHO_TAIL_MS;
                return recent && !ended && Phrases.repeats(text, lastSaid);
            }
        }

        // ---- the end ----

        private void onEnded() {
            calls.remove(callId, this);
            MediationSession inMediation = mediationSession;
            if (inMediation != null) {
                inMediation.farmerEnded();
            }
            Synthesis toCancel;
            SttSession toClose;
            CallContext ctx;
            List<Line> transcript;
            String reason;
            String finalOutcome;
            String finalSummary;
            synchronized (lock) {
                if (ended) {
                    return;
                }
                ended = true;
                generation.incrementAndGet();
                quiet();
                whenFree = null;
                toCancel = synthesis;
                synthesis = null;
                toClose = session;
                session = null;
                feedTarget = null;
                ctx = context;
                transcript = List.copyOf(lines);
                lines.clear(); // transcripts live only for the call
                reason = hungUpByUs ? "assistant_hangup" : "hangup";
                finalOutcome = outcome;
                finalSummary = dealSummary;
            }
            cancelQuietly(toCancel);
            closeQuietly(toClose);
            long endedMs = now();
            // Once per party, before call_ended: what each said to "may we remember?" (NONE when never answered).
            closing.consent().forEach((role, answer) ->
                    publish(new ConsoleEvent.ConsentRecorded(callId, role, answer.name(), endedMs)));
            publish(new ConsoleEvent.LegEnded(callId, endedMs, leg.id(), LegRole.FARMER, reason));
            publish(new ConsoleEvent.CallEnded(callId, endedMs, endedMs - startedMs, reason));
            if (ctx == null) {
                log.info("Call {} ended before it started; no end handlers run", callId);
                return;
            }
            CallEndHandler.EndedCall endedCall =
                    new CallEndHandler.EndedCall(ctx, transcript, startedMs, endedMs, reason, finalOutcome, finalSummary,
                            closing.consent().get(LegRole.FARMER).name());
            submit("end handlers", () -> {
                for (CallEndHandler h : endHandlers) {
                    try {
                        h.callEnded(endedCall);
                    } catch (RuntimeException e) {
                        log.warn("A call end handler failed for call {}", callId, e);
                    }
                }
                ctx.callerKey().ifPresent(key -> {
                    try {
                        memory.callFinished(key, callId);
                    } catch (RuntimeException e) {
                        log.warn("Closing the memory record failed for call {}", callId, e);
                    }
                });
            });
        }

        // ---- helpers ----

        private Reply askBrain(String what, Supplier<Reply> call, Language language) {
            try {
                Reply reply = call.get();
                if (reply != null) {
                    return reply;
                }
                log.warn("The agent brain returned no reply ({}) on call {}; apologising", what, callId);
            } catch (RuntimeException e) {
                log.warn("The agent brain failed ({}) on call {}; apologising", what, callId, e);
            }
            return apology(language);
        }

        private void submit(String what, Runnable task) {
            try {
                executor.execute(() -> {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        log.error("Pipeline step '{}' failed on leg {} of call {}", what, leg.id(), callId, e);
                    }
                });
            } catch (RejectedExecutionException e) {
                log.warn("Pipeline step '{}' was not run for call {}: the executor is shut down", what, callId);
            }
        }

        private void cancelQuietly(Synthesis s) {
            if (s == null) {
                return;
            }
            try {
                s.cancel();
            } catch (RuntimeException e) {
                log.warn("Cancelling speech synthesis failed on leg {}", leg.id(), e);
            }
        }

        private void closeQuietly(SttSession s) {
            if (s == null) {
                return;
            }
            try {
                s.close();
            } catch (RuntimeException e) {
                log.warn("Closing speech-to-text failed on leg {}", leg.id(), e);
            }
        }
    }

    private static final LanguageFlow.Outcome KEEP_LANGUAGE = new LanguageFlow.Outcome(LanguageFlow.Kind.KEEP, null, false, null);
    private static final LanguageFlow.Outcome IGNORE_TURN = new LanguageFlow.Outcome(LanguageFlow.Kind.IGNORED, null, false, null);
    /** Hinglish: the repeat of the question has an English and a Hindi sentence, in one voice. Not yet heard live. */
    private static final String REASK_LANGUAGE = "hi-en";

    private static Turn withLanguage(Turn t, Language language) {
        return t.language().equals(language) ? t : new Turn(t.legId(), t.role(), language, t.text(), t.startedMs(), t.endedMs());
    }

    private static Turn withText(Turn t, String text) {
        return text.equals(t.text()) ? t : new Turn(t.legId(), t.role(), t.language(), text, t.startedMs(), t.endedMs());
    }

    private static boolean isCancellation(Throwable error) {
        Throwable t = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return t instanceof CancellationException;
    }

    /** @param publish false when nothing should be shown: withheld caller ID, or the load failed */
    private record LoadedMemory(FarmerMemory memory, boolean publish) {}
}
