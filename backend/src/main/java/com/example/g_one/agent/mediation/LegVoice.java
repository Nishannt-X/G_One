package com.example.g_one.agent.mediation;

import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.Language;
import com.example.g_one.core.Leg;
import com.example.g_one.core.PlaybackOutcome;
import com.example.g_one.core.TextToSpeech;
import com.example.g_one.core.TextToSpeech.Synthesis;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything one leg hears during mediation, one line at a time, in order. Nothing queued is dropped. Barge-in stops
 * only the line playing now, and never a protected one (a read-back, a caution): those must be heard to the end.
 */
final class LegVoice {
    private static final Logger log = LoggerFactory.getLogger(LegVoice.class);

    /** @param done told how it ended: PLAYED, CLEARED (barge-in, or synthesis failed), or ENDED (the leg ended first) */
    record Line(String text, Language language, boolean isProtected, Consumer<PlaybackOutcome> done) {}

    private final Leg leg;
    private final TextToSpeech tts;
    private final Object lock = new Object();
    /** Serialises queueing frames with clearing the leg, so a stale frame cannot land after the clear. */
    private final Object playLock = new Object();
    private final Deque<Line> queue = new ArrayDeque<>();
    private Line current;
    private Synthesis synthesis;
    private long token;
    private boolean closed;

    LegVoice(Leg leg, TextToSpeech tts) {
        this.leg = leg;
        this.tts = tts;
    }

    void say(Line line) {
        boolean reject;
        synchronized (lock) {
            reject = closed;
            if (!closed) queue.addLast(line);
        }
        if (reject) {
            notify(line, PlaybackOutcome.ENDED);
            return;
        }
        pump();
    }

    /** The person on this leg started speaking. */
    void bargeIn() {
        Line stopped;
        Synthesis s;
        synchronized (lock) {
            if (current == null || current.isProtected()) return;
            stopped = current;
            s = synthesis;
            token++;
            current = null;
            synthesis = null;
        }
        cancel(s);
        synchronized (playLock) {
            try {
                leg.clearPlayback();
            } catch (RuntimeException e) {
                log.warn("Clearing playback failed on leg {}", leg.id(), e);
            }
        }
        notify(stopped, PlaybackOutcome.CLEARED);
        pump();
    }

    /** The leg is over: nothing more is spoken and every waiting line is told so. */
    void close() {
        Line playing;
        Synthesis s;
        Deque<Line> waiting;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            playing = current;
            s = synthesis;
            token++;
            current = null;
            synthesis = null;
            waiting = new ArrayDeque<>(queue);
            queue.clear();
        }
        cancel(s);
        if (playing != null) notify(playing, PlaybackOutcome.ENDED);
        waiting.forEach(l -> notify(l, PlaybackOutcome.ENDED));
    }

    private void pump() {
        Line next;
        long mine;
        synchronized (lock) {
            if (closed || current != null || queue.isEmpty()) return;
            next = queue.pollFirst();
            current = next;
            mine = ++token;
        }
        Synthesis started;
        try {
            started = tts.speak(next.text(), next.language(), leg.playbackSampleRate(), frame -> play(mine, frame));
        } catch (RuntimeException e) {
            log.warn("Speech synthesis failed to start on leg {}", leg.id(), e);
            finish(mine, next, PlaybackOutcome.CLEARED);
            return;
        }
        boolean stale;
        synchronized (lock) {
            stale = token != mine;
            if (!stale) synthesis = started;
        }
        if (stale) {
            cancel(started);
            return;
        }
        started.done().whenComplete((ignored, error) -> {
            if (error != null) {
                log.debug("Speech synthesis ended early on leg {}", leg.id());
                finish(mine, next, PlaybackOutcome.CLEARED);
                return;
            }
            leg.playbackDone().thenAccept(outcome -> finish(mine, next, outcome));
        });
    }

    private void play(long mine, AudioFrame frame) {
        synchronized (playLock) {
            synchronized (lock) {
                if (token != mine) return;
            }
            try {
                leg.play(frame);
            } catch (RuntimeException e) {
                log.warn("Playing audio failed on leg {}", leg.id(), e);
            }
        }
    }

    private void finish(long mine, Line line, PlaybackOutcome outcome) {
        synchronized (lock) {
            if (token != mine || current != line) return;
            current = null;
            synthesis = null;
        }
        notify(line, outcome);
        pump();
    }

    private void cancel(Synthesis s) {
        if (s == null) return;
        try {
            s.cancel();
        } catch (RuntimeException e) {
            log.warn("Cancelling speech synthesis failed on leg {}", leg.id(), e);
        }
    }

    private void notify(Line line, PlaybackOutcome outcome) {
        if (line.done() == null) return;
        try {
            line.done().accept(outcome);
        } catch (RuntimeException e) {
            log.warn("A line's completion step failed on leg {}", leg.id(), e);
        }
    }
}
