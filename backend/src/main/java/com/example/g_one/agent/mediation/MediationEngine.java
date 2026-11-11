package com.example.g_one.agent.mediation;

import com.example.g_one.agent.closing.CallClosing;
import com.example.g_one.agent.AgentBrain.Speaker;
import com.example.g_one.agent.tools.buyers.BuyerConsent;
import com.example.g_one.agent.tools.mandi.MandiCatalog;
import com.example.g_one.agent.tools.mandi.MandiQuote;
import com.example.g_one.agent.tools.memory.MemoryBlock;
import com.example.g_one.agent.tools.memory.MemoryGate;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.Language;
import com.example.g_one.core.Leg;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.TextToSpeech;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Builds a {@link MediationSession} when a buyer leg joins a call. */
@Component
public class MediationEngine {
    /** What a session may ask of the farmer's side of the call. */
    public interface FarmerSide {
        /**
         * Stops the intake conversation: no reply of its own may start from now on. {@code then} speaks on the farmer's
         * leg, so it runs once nothing the intake already said is still playing: never before, and never while the
         * farmer hears an intake reply. It also runs if the farmer interrupts that reply, or if a newer turn or the
         * hang-up supersedes it, so it always runs once. A hand-off that a later take-over supersedes still runs, ahead
         * of the later one, in the order they were asked for. The one exception is a reply that ends the call: the leg
         * hangs up and there is nothing left to say.
         */
        void takeOver(Runnable then);

        /** Mediation is over but the farmer stays: the assistant talks to them again. */
        void release();

        /** Ends the farmer's leg after the wrap-up. */
        void hangUp();

        /** Adds a line to the call transcript. */
        void note(Speaker speaker, String text);

        /** Records how the sale ended, for the gist. */
        void outcome(String outcome, String dealSummary);

        /** The call's end-of-call check, shared with the farmer leg: who is asked, and what each party answered. */
        CallClosing closing();
    }

    private final TurnReader reader;
    private final MediationLines lines;
    private final MandiQuote mandi;
    private final MandiCatalog catalog;
    private final BuyerConsent consent;
    private final MemoryGate gate;
    private final ConsoleBus bus;
    private final Language.Registry languages;
    private final Clock clock;
    private final AskGone ask;

    @Autowired
    public MediationEngine(TurnReader reader, MediationLines lines, MandiQuote mandi, MandiCatalog catalog,
                           BuyerConsent consent, MemoryGate gate, ConsoleBus bus, Language.Registry languages,
                           AskGone ask) {
        this(reader, lines, mandi, catalog, consent, gate, bus, languages, Clock.systemUTC(), ask);
    }

    public MediationEngine(TurnReader reader, MediationLines lines, MandiQuote mandi, MandiCatalog catalog,
                           BuyerConsent consent, MemoryGate gate, ConsoleBus bus, Language.Registry languages) {
        this(reader, lines, mandi, catalog, consent, gate, bus, languages, Clock.systemUTC());
    }

    public MediationEngine(TurnReader reader, MediationLines lines, MandiQuote mandi, MandiCatalog catalog,
                           BuyerConsent consent, MemoryGate gate, ConsoleBus bus, Language.Registry languages,
                           Clock clock) {
        this(reader, lines, mandi, catalog, consent, gate, bus, languages, clock, reader.askGone());
    }

    public MediationEngine(TurnReader reader, MediationLines lines, MandiQuote mandi, MandiCatalog catalog,
                           BuyerConsent consent, MemoryGate gate, ConsoleBus bus, Language.Registry languages,
                           Clock clock, AskGone ask) {
        this.ask = ask;
        this.reader = reader;
        this.lines = lines;
        this.mandi = mandi;
        this.catalog = catalog;
        this.consent = consent;
        this.gate = gate;
        this.bus = bus;
        this.languages = languages;
        this.clock = clock;
    }

    public MediationSession session(CallContext farmer, FarmerSide side, Leg buyer, SpeechToText stt, TextToSpeech tts,
                                    Executor executor) {
        Optional<String> name = gate.peek(farmer.callId()).filter(MemoryGate.State::usesMemory)
                .flatMap(s -> MemoryBlock.rememberedName(farmer.memory()));
        return new MediationSession(farmer, side, buyer, stt, tts, executor, reader, lines, mandi, catalog,
                consent.request(farmer.callId()).orElse(null), name.orElse(null), bus, languages, clock, ask);
    }
}
