package com.example.g_one.speech.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.speech.audio.Pcm;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Energy VAD, the backchannel and connective word lists, and the turn assembler on a virtual clock. */
class TurnDetectionTest {

    /** Runs timers when the test advances its clock, so no test sleeps. */
    private static final class Clock implements TurnAssembler.Timers {
        private record Task(long at, Runnable run, boolean[] cancelled) {}

        long now = 1_000;
        final List<Task> tasks = new ArrayList<>();

        @Override
        public TurnAssembler.Cancel after(long delayMs, Runnable task) {
            boolean[] cancelled = {false};
            tasks.add(new Task(now + delayMs, task, cancelled));
            return () -> cancelled[0] = true;
        }

        void advance(long ms) {
            long target = now + ms;
            while (true) {
                Task next = tasks.stream().filter(t -> !t.cancelled[0] && t.at <= target)
                        .min(Comparator.comparingLong(Task::at)).orElse(null);
                if (next == null) break;
                tasks.remove(next);
                now = Math.max(now, next.at);
                next.run.run();
            }
            now = target;
        }
    }

    private final Clock clock = new Clock();
    private final List<String> events = new ArrayList<>();
    private final TurnAssembler assembler = new TurnAssembler(new TurnAssembler.Config(700, 1200, 8000), clock, () -> clock.now,
            new TurnAssembler.Output() {
                @Override
                public void speechStart() {
                    events.add("start");
                }

                @Override
                public void finalTurn(String text, long startedMs, long endedMs) {
                    assertThat(endedMs).isGreaterThanOrEqualTo(startedMs);
                    events.add("turn:" + text);
                }
            });

    @Test
    void backchannelWordsAreRecognisedInEveryScript() {
        for (String s : List.of("haan", "Haan ji.", "theek hai", "achha", "ji", "हाँ", "ठीक है।", "अच्छा जी", "হ্যাঁ", "ঠিক আছে", "okay")) {
            assertThat(Phrases.isBackchannel(s)).as(s).isTrue();
        }
        for (String s : List.of("", "haan bech do", "nahi", "theek hai par daam kam hai", "मुझे गेहूं बेचना है", "2400")) {
            assertThat(Phrases.isBackchannel(s)).as(s).isFalse();
        }
    }

    @Test
    void connectivesAtTheEndMeanMoreIsComing() {
        for (String s : List.of("gehun hai aur", "daam theek hai lekin", "phir toh", "मेरे पास गेहूं है और", "दाम ठीक है लेकिन।",
                "আমার ধান আছে কিন্তু", "I have wheat and")) {
            assertThat(Phrases.endsMidThought(s)).as(s).isTrue();
        }
        for (String s : List.of("", "aur kya", "mujhe gehun bechna hai", "main aaun toh?", "दाम ठीक है")) {
            assertThat(Phrases.endsMidThought(s)).as(s).isFalse();
        }
    }

    @Test
    void shortAcknowledgementDoesNotAnnounceSpeechButStillBecomesATurn() {
        assembler.speechStarted();
        clock.advance(300);
        assembler.speechEnded();
        clock.advance(200);
        assembler.transcript("haan");
        clock.advance(2000);
        assertThat(events).containsExactly("turn:haan");
    }

    @Test
    void sustainedSpeechAnnouncesAfterTheGateOnce() {
        assembler.speechStarted();
        clock.advance(699);
        assertThat(events).isEmpty();
        clock.advance(1);
        assertThat(events).containsExactly("start");
        assembler.speechEnded();
        assembler.transcript("mujhe gehun bechna hai");
        assertThat(events).containsExactly("start", "turn:mujhe gehun bechna hai");
    }

    @Test
    void shortRealPhraseAnnouncesWhenItsTranscriptArrivesAndCommitsAtOnce() {
        assembler.speechStarted();
        clock.advance(400);
        assembler.speechEnded();
        clock.advance(150);
        assembler.transcript("nahi chahiye");
        assertThat(events).containsExactly("start", "turn:nahi chahiye");
    }

    @Test
    void connectiveHoldsTheTurnAndMergesTheContinuation() {
        assembler.speechStarted();
        clock.advance(900);
        assembler.speechEnded();
        assembler.transcript("mere paas gehun hai aur");
        assertThat(events).containsExactly("start");
        clock.advance(600);
        assembler.speechStarted();
        clock.advance(2000); // no time limit while the person keeps talking
        assembler.speechEnded();
        assembler.transcript("chana bhi hai");
        assertThat(events).containsExactly("start", "turn:mere paas gehun hai aur chana bhi hai");
    }

    @Test
    void connectiveHoldEndsAfterTheConfiguredWait() {
        assembler.speechStarted();
        clock.advance(900);
        assembler.speechEnded();
        assembler.transcript("daam theek hai lekin");
        clock.advance(1199);
        assertThat(events).containsExactly("start");
        clock.advance(1);
        assertThat(events).containsExactly("start", "turn:daam theek hai lekin");
    }

    @Test
    void transcriptArrivingDuringTheNextStretchIsMerged() {
        assembler.speechStarted();
        clock.advance(800);
        assembler.speechEnded();
        clock.advance(100);
        assembler.speechStarted(); // spoke again before the first transcript came back
        clock.advance(200);
        assembler.transcript("gehun hai");
        assertThat(events).containsExactly("start");
        clock.advance(500);
        assembler.speechEnded();
        assembler.transcript("do sau quintal");
        assertThat(events).containsExactly("start", "turn:gehun hai do sau quintal");
    }

    @Test
    void heldTextIsReleasedWhenTheNextStretchHadNoWords() {
        assembler.speechStarted();
        clock.advance(800);
        assembler.speechEnded();
        clock.advance(100);
        assembler.speechStarted();
        assembler.transcript("gehun hai");
        clock.advance(300);
        assembler.speechEnded();
        assembler.transcript("");
        assertThat(events).containsExactly("start", "turn:gehun hai");
    }

    @Test
    void nothingIsHeldPastTheCap() {
        assembler.speechStarted();
        clock.advance(800);
        assembler.speechEnded();
        clock.advance(100);
        assembler.speechStarted();
        assembler.transcript("gehun hai");
        clock.advance(8000);
        assertThat(events).containsExactly("start", "turn:gehun hai");
    }

    @Test
    void noiseWithNoWordsLeavesNoTrace() {
        assembler.speechStarted();
        clock.advance(100);
        assembler.speechEnded();
        assembler.transcript("  ");
        assertThat(events).isEmpty();
        assertThat(assembler.idle()).isTrue();
    }

    @Test
    void transcriptWithoutAnySpeechEventsStillAnnouncesFirst() {
        assembler.transcript("namaste bhaiya");
        assertThat(events).containsExactly("start", "turn:namaste bhaiya");
    }

    @Test
    void transcriptWithoutASpeechEndStillClosesTheStretch() {
        assembler.speechStarted();
        clock.advance(300);
        assembler.transcript("haan");
        clock.advance(2000);
        assertThat(events).containsExactly("turn:haan");
        assertThat(assembler.idle()).isTrue();
    }

    @Test
    void eachTurnAnnouncesAgain() {
        for (int i = 0; i < 2; i++) {
            assembler.speechStarted();
            clock.advance(800);
            assembler.speechEnded();
            assembler.transcript("turn number");
        }
        assertThat(events).containsExactly("start", "turn:turn number", "start", "turn:turn number");
    }

    @Test
    void lostConnectionReleasesHeldTextAndResets() {
        assembler.speechStarted();
        clock.advance(800);
        assembler.speechEnded();
        assembler.transcript("gehun aur");
        assembler.connectionLost();
        assertThat(events).containsExactly("start", "turn:gehun aur");
        assertThat(assembler.idle()).isTrue();
    }

    private static byte[] window(double amplitude) {
        byte[] pcm = new byte[320];
        for (int i = 0; i < 160; i++) {
            Pcm.put(pcm, i, Pcm.clamp(amplitude * 32767 * Math.sin(2 * Math.PI * 300 * i / 8000.0)));
        }
        return pcm;
    }

    private static EnergyVad.Event feed(EnergyVad vad, double amplitude, int ms) {
        EnergyVad.Event last = EnergyVad.Event.NONE;
        for (int t = 0; t < ms; t += 20) {
            EnergyVad.Event e = vad.process(window(amplitude));
            if (e != EnergyVad.Event.NONE) last = e;
        }
        return last;
    }

    /** Speech with the short dips real speech has, which keep the background estimate low. */
    private static EnergyVad.Event speak(EnergyVad vad, int ms) {
        EnergyVad.Event last = EnergyVad.Event.NONE;
        for (int t = 0; t < ms; t += 20) {
            EnergyVad.Event e = vad.process(window(t % 240 == 0 ? 0 : 0.2));
            if (e != EnergyVad.Event.NONE) last = e;
        }
        return last;
    }

    @Test
    void vadFindsSpeechAndEndsShortPhrasesSooner() {
        EnergyVad vad = new EnergyVad(EnergyVad.Config.defaults());
        assertThat(feed(vad, 0, 500)).isEqualTo(EnergyVad.Event.NONE);
        assertThat(feed(vad, 0.2, 500)).isEqualTo(EnergyVad.Event.SPEECH_START);
        assertThat(feed(vad, 0, 340)).isEqualTo(EnergyVad.Event.NONE);
        assertThat(feed(vad, 0, 20)).isEqualTo(EnergyVad.Event.SPEECH_END);
    }

    @Test
    void vadUsesTheLongHangoverForLongTurns() {
        EnergyVad vad = new EnergyVad(EnergyVad.Config.defaults());
        speak(vad, 2000);
        assertThat(vad.inSpeech()).isTrue();
        assertThat(feed(vad, 0, 580)).isEqualTo(EnergyVad.Event.NONE);
        assertThat(feed(vad, 0, 20)).isEqualTo(EnergyVad.Event.SPEECH_END);
    }

    @Test
    void vadCutsTurnsAtTheRestLimit() {
        EnergyVad vad = new EnergyVad(EnergyVad.Config.defaults());
        speak(vad, 29_000 - 200);
        assertThat(vad.inSpeech()).isTrue();
        assertThat(speak(vad, 400)).isEqualTo(EnergyVad.Event.SPEECH_START);
        assertThat(vad.inSpeech()).isTrue();
    }

    @Test
    void vadIgnoresAConstantHumAfterAFewSeconds() {
        EnergyVad vad = new EnergyVad(EnergyVad.Config.defaults());
        feed(vad, 0.05, 30_000);
        feed(vad, 0.05, 2_000);
        assertThat(vad.inSpeech()).isFalse();
    }
}
