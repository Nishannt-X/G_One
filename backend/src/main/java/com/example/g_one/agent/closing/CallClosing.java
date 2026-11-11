package com.example.g_one.agent.closing;

import com.example.g_one.agent.deal.ReplyGuard;
import com.example.g_one.core.LegRole;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How a call ends, decided by code, never by the language model. When the conversation seems over, G-One asks every
 * party still on the call whether they would like to end it. Only when every one of them says yes does it ask each of
 * them, once, whether it may remember the main points of the call. Each answer is recorded per party:
 * <ul>
 *   <li>{@link Consent#YES} only for a plain spoken yes;</li>
 *   <li>{@link Consent#NO} for a plain no;</li>
 *   <li>{@link Consent#NONE} for silence, an unclear answer after one re-ask, a party that left before being asked or
 *       answering, and a party that is never asked (a withheld caller ID cannot be remembered).</li>
 * </ul>
 * A no to ending the call, or no clear answer after one re-ask, resumes the conversation, and that leg is not asked
 * again for its next {@value #COOLDOWN_TURNS} turns.
 *
 * <p>Thread safe: the farmer leg and the mediation lane share one instance. Every method returns a {@link Step}: the
 * lines to speak now, and whether the call resumed or finished.
 */
public final class CallClosing {
    /** How long a question waits for its answer before it is asked again (then treated as no answer). */
    public static final long ANSWER_WAIT_MS = 8_000;
    /** Turns of a leg that said no before it can be asked to end the call again. */
    public static final int COOLDOWN_TURNS = 3;

    /** What a party said to the save question. Wire values "YES", "NO", "NONE". */
    public enum Consent { YES, NO, NONE }

    /** The two questions, in order. */
    public enum Question { END, SAVE }

    private enum Phase { IDLE, END, SAVE, DONE }

    /**
     * One question to speak to one party. {@code token} identifies this asking for {@link #timeout}; {@code again} is
     * true for the one re-ask.
     */
    public record Ask(LegRole role, Question question, boolean again, long token) {}

    /**
     * @param asks lines to speak now, in order
     * @param resumed the end check is over without ending: carry on with the conversation
     * @param consent set when the call should end now: every party's answer to the save question
     */
    public record Step(List<Ask> asks, boolean resumed, Map<LegRole, Consent> consent) {
        static final Step NOTHING = new Step(List.of(), false, null);

        public synchronized boolean finished() {
            return consent != null;
        }
    }

    /**
     * Turns that close a conversation: goodbye, "that's all", "nothing else", "thank you, bye", in Hindi, English,
     * Hinglish, Bengali, Tamil and Kannada. Only a short turn counts; a longer one is still asking for something.
     */
    private static final Pattern DONE = Pattern.compile("(?iu)अलविदा|बाय|फोन रख|फ़ोन रख|रखता हूँ|रखती हूँ|बस इतना|बस यही"
            + "|और कुछ नहीं|कुछ और नहीं|हो गया|\\bbye\\b|goodbye|good bye|phone rakh|rakhta hu|rakhti hu|bas itna|bas yahi"
            + "|aur kuch nahi|kuch aur nahi|ho gaya|that'?s all|that is all|that'?s it|nothing else|nothing more|i'?m done"
            + "|we'?re done|বিদায়|রাখছি|আর কিছু না|আর কিছু নেই|ব্যস|rakhchi|ar kichu na|போதும்|வேறு எதுவும் இல்லை|வேறு ஒன்றும் இல்லை"
            + "|போய் வருகிறேன்|ಸಾಕು|ಬೇರೆ ಏನೂ ಇಲ್ಲ|ಬೇರೆ ಏನು ಇಲ್ಲ|ಹೋಗಿ ಬರುತ್ತೇನೆ");
    private static final int SHORT_TURN_WORDS = 8;

    private final Set<LegRole> present = EnumSet.noneOf(LegRole.class);
    /** Parties that are never asked the save question (withheld caller ID): their consent is NONE. */
    private final Set<LegRole> notAskedToSave = EnumSet.noneOf(LegRole.class);
    private final Map<LegRole, Boolean> endAnswers = new EnumMap<>(LegRole.class);
    private final Map<LegRole, Consent> consent = new EnumMap<>(LegRole.class);
    private final Map<LegRole, Integer> cooldown = new EnumMap<>(LegRole.class);
    private final Set<LegRole> reasked = EnumSet.noneOf(LegRole.class);
    private final Map<LegRole, Long> tokens = new EnumMap<>(LegRole.class);
    private Phase phase = Phase.IDLE;
    private long nextToken;

    /** @param withheldFarmer the farmer's caller ID is withheld: nothing about them can be remembered, so no save question */
    public CallClosing(boolean withheldFarmer) {
        present.add(LegRole.FARMER);
        if (withheldFarmer) notAskedToSave.add(LegRole.FARMER);
    }

    /** True for a short turn that closes the conversation ("bye", "that's all", "बस इतना ही"). */
    public static boolean saysDone(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.strip().toLowerCase(Locale.ROOT);
        return t.split("\\s+").length <= SHORT_TURN_WORDS && DONE.matcher(t).find();
    }

    /** True while either question waits for answers: turns go to {@link #heard}, not to the conversation. */
    public synchronized boolean active() {
        return phase == Phase.END || phase == Phase.SAVE;
    }

    public synchronized boolean finished() {
        return phase == Phase.DONE;
    }

    /** The question waiting for answers, or empty when neither is being asked. */
    public synchronized java.util.Optional<Question> pending() {
        return switch (phase) {
            case END -> java.util.Optional.of(Question.END);
            case SAVE -> java.util.Optional.of(Question.SAVE);
            default -> java.util.Optional.empty();
        };
    }

    /** A party joined the call (the buyer said yes to being connected). */
    public synchronized void joined(LegRole role) {
        if (phase == Phase.IDLE) present.add(role);
    }

    /** One ordinary conversation turn of {@code role}: counts down that leg's wait after a no. */
    public synchronized void turnTaken(LegRole role) {
        if (phase != Phase.IDLE) return;
        cooldown.computeIfPresent(role, (r, n) -> n <= 1 ? null : n - 1);
    }

    /** False while a leg that said no to ending is still within its {@value #COOLDOWN_TURNS} turns. */
    public synchronized boolean mayAsk(LegRole role) {
        return phase == Phase.IDLE && !cooldown.containsKey(role);
    }

    /** Starts the end check: asks every present party whether to end the call. Nothing happens unless idle. */
    public synchronized Step start() {
        if (phase != Phase.IDLE) return Step.NOTHING;
        phase = Phase.END;
        endAnswers.clear();
        reasked.clear();
        List<Ask> asks = new ArrayList<>();
        for (LegRole r : present) asks.add(ask(r, Question.END, false));
        return new Step(asks, false, null);
    }

    /** A turn of {@code role} while {@link #active}, read by words alone ({@link ReplyGuard}). */
    public synchronized Step heard(LegRole role, String text) {
        return heard(role, ReplyGuard.read(text));
    }

    /**
     * A turn of {@code role} while {@link #active}, already read as {@code reading} (by {@link ReplyGuard}, or by the
     * layered reader that may ask a model about a reply the words did not settle). Only {@code YES} saves.
     */
    public synchronized Step heard(LegRole role, ReplyGuard.Reading reading) {
        if (!active() || !present.contains(role)) return Step.NOTHING;
        if (phase == Phase.END) {
            if (endAnswers.containsKey(role)) return Step.NOTHING;
            return switch (reading) {
                case YES -> {
                    endAnswers.put(role, true);
                    tokens.remove(role);
                    yield allEndAnswered();
                }
                case NO -> resume();
                case UNCLEAR -> reaskOr(role, Question.END);
            };
        }
        if (consent.containsKey(role)) return Step.NOTHING;
        return switch (reading) {
            case YES -> record(role, Consent.YES);
            case NO -> record(role, Consent.NO);
            case UNCLEAR -> reaskOr(role, Question.SAVE);
        };
    }

    /** The question asked with {@code token} got no answer in {@link #ANSWER_WAIT_MS}. Stale tokens do nothing. */
    public synchronized Step timeout(LegRole role, long token) {
        Long current = tokens.get(role);
        if (!active() || current == null || current != token) return Step.NOTHING;
        return reaskOr(role, phase == Phase.END ? Question.END : Question.SAVE);
    }

    /** A party hung up. Their consent is NONE unless they already answered the save question. */
    public synchronized Step left(LegRole role) {
        if (!present.remove(role)) return Step.NOTHING;
        tokens.remove(role);
        endAnswers.remove(role);
        if (phase == Phase.SAVE) {
            consent.putIfAbsent(role, Consent.NONE);
            return allSaveAnswered();
        }
        if (phase == Phase.END) return present.isEmpty() ? Step.NOTHING : allEndAnswered();
        return Step.NOTHING;
    }

    /** Everyone's consent as it stands: NONE for anyone not (yet) answered. */
    public synchronized Map<LegRole, Consent> consent() {
        Map<LegRole, Consent> all = new EnumMap<>(LegRole.class);
        for (LegRole r : LegRole.values()) all.put(r, consent.getOrDefault(r, Consent.NONE));
        return all;
    }

    private Step reaskOr(LegRole role, Question q) {
        if (reasked.add(role)) return new Step(List.of(ask(role, q, true)), false, null);
        tokens.remove(role);
        // No clear answer twice: not ready to end, or no consent.
        return q == Question.END ? resume() : record(role, Consent.NONE);
    }

    private Step resume() {
        phase = Phase.IDLE;
        for (LegRole r : present) cooldown.put(r, COOLDOWN_TURNS);
        tokens.clear();
        endAnswers.clear();
        reasked.clear();
        return new Step(List.of(), true, null);
    }

    private Step allEndAnswered() {
        for (LegRole r : present) {
            if (!Boolean.TRUE.equals(endAnswers.get(r))) return Step.NOTHING;
        }
        phase = Phase.SAVE;
        reasked.clear();
        List<Ask> asks = new ArrayList<>();
        for (LegRole r : present) {
            if (notAskedToSave.contains(r)) consent.put(r, Consent.NONE);
            else asks.add(ask(r, Question.SAVE, false));
        }
        return asks.isEmpty() ? allSaveAnswered() : new Step(asks, false, null);
    }

    private Step record(LegRole role, Consent answer) {
        consent.put(role, answer);
        tokens.remove(role);
        return allSaveAnswered();
    }

    private Step allSaveAnswered() {
        for (LegRole r : present) {
            if (!consent.containsKey(r)) return Step.NOTHING;
        }
        phase = Phase.DONE;
        tokens.clear();
        return new Step(List.of(), false, consent());
    }

    private Ask ask(LegRole role, Question q, boolean again) {
        long token = ++nextToken;
        tokens.put(role, token);
        return new Ask(role, q, again, token);
    }
}
