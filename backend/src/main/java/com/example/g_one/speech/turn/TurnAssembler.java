package com.example.g_one.speech.turn;

import java.util.function.LongSupplier;

/**
 * Turns "speech started", "speech ended" and transcript events into what a {@link com.example.g_one.core.SttListener}
 * wants. It works the same whichever recogniser fed it.
 *
 * <ul>
 *   <li><b>Barge-in gate:</b> the start of speech is announced only after {@code bargeInMinSpeechMs} of continuous speech,
 *       or once a transcript shows it was more than an acknowledgement. "haan" spoken over the assistant stays silent.</li>
 *   <li><b>Semantic endpointing:</b> a transcript ending on a connective is held for {@code continuationHoldMs} and merged
 *       with whatever comes next. A complete phrase is committed the moment its transcript arrives.</li>
 *   <li><b>Pause handling:</b> a transcript arriving while the person has already started speaking again is held and
 *       merged too. Nothing is held for longer than {@code maxHoldMs}.</li>
 * </ul>
 * Every method, and every task given to the {@link Timers}, must run on one thread.
 */
public final class TurnAssembler {

    public record Config(int bargeInMinSpeechMs, int continuationHoldMs, int maxHoldMs) {
        public Config {
            if (bargeInMinSpeechMs < 0 || continuationHoldMs <= 0 || maxHoldMs <= 0) {
                throw new IllegalArgumentException("Invalid turn assembly settings");
            }
        }

        public static Config defaults() {
            return new Config(700, 1200, 8000);
        }
    }

    public interface Output {
        void speechStart();

        void finalTurn(String text, long startedMs, long endedMs);
    }

    /** Runs a task later on the assembler's thread. */
    public interface Timers {
        Cancel after(long delayMs, Runnable task);
    }

    public interface Cancel {
        void cancel();
    }

    private final Config config;
    private final Timers timers;
    private final LongSupplier clock;
    private final Output output;

    private boolean speechOpen;
    /** Utterances whose end was reported and whose transcript has not arrived yet. */
    private int awaitingTranscripts;
    private boolean announced;
    private long startedMs = -1;
    private long endedMs = -1;
    private Cancel bargeTimer;

    private String held = "";
    private long firstHeldMs;
    private Cancel holdTimer;

    public TurnAssembler(Config config, Timers timers, LongSupplier clock, Output output) {
        this.config = config;
        this.timers = timers;
        this.clock = clock;
        this.output = output;
    }

    /** The person started speaking. */
    public void speechStarted() {
        cancelHoldTimer();
        speechOpen = true;
        if (startedMs < 0) {
            startedMs = clock.getAsLong();
        }
        if (!announced && bargeTimer == null) {
            bargeTimer = timers.after(config.bargeInMinSpeechMs(), () -> {
                bargeTimer = null;
                announce();
            });
        }
    }

    /** The person stopped speaking and a transcript for this stretch is coming. */
    public void speechEnded() {
        cancelBargeTimer();
        if (speechOpen) {
            speechOpen = false;
            awaitingTranscripts++;
            endedMs = clock.getAsLong();
        }
    }

    /** The transcript of the oldest finished stretch; blank when it held no words. */
    public void transcript(String rawText) {
        String text = rawText == null ? "" : rawText.strip();
        if (awaitingTranscripts > 0) {
            awaitingTranscripts--;
        } else if (speechOpen) {
            // No end of speech was reported for this stretch, so the transcript itself closes it.
            cancelBargeTimer();
            speechOpen = false;
            endedMs = clock.getAsLong();
        }
        if (text.isEmpty()) {
            settleWithoutWords();
            return;
        }
        if (endedMs < 0) {
            endedMs = clock.getAsLong();
        }
        if (startedMs < 0) {
            startedMs = endedMs;
        }
        String merged = held.isEmpty() ? text : held + " " + text;
        if (!Phrases.isBackchannel(merged)) {
            cancelBargeTimer();
            announce();
        }
        boolean wantsMore = speechOpen || Phrases.endsMidThought(merged);
        long now = clock.getAsLong();
        if (held.isEmpty()) {
            firstHeldMs = now;
        }
        long heldFor = now - firstHeldMs;
        if (wantsMore && heldFor < config.maxHoldMs()) {
            held = merged;
            armHold(speechOpen ? config.maxHoldMs() - heldFor
                    : Math.min(config.continuationHoldMs(), config.maxHoldMs() - heldFor));
            return;
        }
        held = merged;
        commit();
    }

    /** The recogniser lost the connection: whatever was in flight will never get a transcript. */
    public void connectionLost() {
        cancelBargeTimer();
        speechOpen = false;
        awaitingTranscripts = 0;
        if (!held.isEmpty()) {
            commit();
        } else {
            resetTurn();
        }
    }

    /** Whether nothing is in progress, so the connection can be swapped without losing speech. */
    public boolean idle() {
        return !speechOpen && awaitingTranscripts == 0 && held.isEmpty();
    }

    private void settleWithoutWords() {
        if (!held.isEmpty()) {
            if (!speechOpen && awaitingTranscripts == 0 && holdTimer == null) {
                commit();
            } else if (!speechOpen && !Phrases.endsMidThought(held) && awaitingTranscripts == 0) {
                commit(); // noise after a pause; the words already heard are complete
            }
            return;
        }
        if (!speechOpen && awaitingTranscripts == 0) {
            resetTurn();
        }
    }

    private void armHold(long delayMs) {
        cancelHoldTimer();
        holdTimer = timers.after(Math.max(1, delayMs), () -> {
            holdTimer = null;
            if (!held.isEmpty()) {
                commit();
            }
        });
    }

    private void commit() {
        cancelHoldTimer();
        String text = held;
        held = "";
        long started = startedMs;
        long ended = Math.max(started, endedMs);
        boolean moreComing = speechOpen || awaitingTranscripts > 0;
        if (moreComing) {
            // A later stretch is under way: it becomes its own turn, starting now.
            startedMs = clock.getAsLong();
            endedMs = -1;
        } else {
            resetTurn();
        }
        output.finalTurn(text, started, ended);
    }

    private void resetTurn() {
        announced = false;
        startedMs = -1;
        endedMs = -1;
    }

    private void announce() {
        if (!announced) {
            announced = true;
            output.speechStart();
        }
    }

    private void cancelBargeTimer() {
        if (bargeTimer != null) {
            bargeTimer.cancel();
            bargeTimer = null;
        }
    }

    private void cancelHoldTimer() {
        if (holdTimer != null) {
            holdTimer.cancel();
            holdTimer = null;
        }
    }
}
