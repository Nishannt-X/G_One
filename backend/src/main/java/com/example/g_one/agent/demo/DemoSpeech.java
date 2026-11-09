package com.example.g_one.agent.demo;

import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.Language;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.SttListener;
import com.example.g_one.core.SttSession;
import com.example.g_one.core.TextToSpeech;
import com.example.g_one.core.Turn;
import com.example.g_one.core.TypedTurns;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Speech for the offline demo. Turns are typed in the call simulator instead of spoken, and "speech" is a soft tone whose
 * length follows the text, so the browser's playback and marks behave as on a real call. Words appear on the console.
 */
@Component
@Primary
@ConditionalOnProperty(name = "g-one.demo.enabled", havingValue = "true")
public class DemoSpeech implements SpeechToText, TextToSpeech, TypedTurns {
    static final int MAX_TYPED_CHARS = 500;
    private static final int FRAME_MS = 20;
    private static final int MS_PER_CHAR = 40;
    private static final int MAX_SPEECH_MS = 6_000;

    private record Open(LegRole role, Language language, SttListener listener) {}

    private final Map<LegId, Open> sessions = new ConcurrentHashMap<>();

    @Override
    public SttSession open(LegId legId, LegRole role, Language language, SttListener listener) {
        sessions.put(legId, new Open(role, language, listener));
        return new SttSession() {
            @Override
            public void feed(AudioFrame frame) {
                // Turns are typed in the demo; microphone audio is ignored.
            }

            @Override
            public void retarget(Language next) {
                sessions.computeIfPresent(legId, (id, open) -> new Open(open.role(), next, open.listener()));
            }

            @Override
            public void close() {
                sessions.remove(legId);
            }
        };
    }

    @Override
    public void say(LegId legId, String text) {
        Open open = sessions.get(legId);
        if (open == null || text == null || text.isBlank()) return;
        String capped = text.strip();
        if (capped.length() > MAX_TYPED_CHARS) capped = capped.substring(0, MAX_TYPED_CHARS);
        long now = System.currentTimeMillis();
        open.listener().onSpeechStart();
        open.listener().onFinalTurn(new Turn(legId, open.role(), open.language(), capped, now, now));
    }

    @Override
    public Synthesis speak(String text, Language language, int sampleRate, Consumer<AudioFrame> sink) {
        int ms = Math.min(MAX_SPEECH_MS, Math.max(FRAME_MS, text.codePointCount(0, text.length()) * MS_PER_CHAR));
        int frames = ms / FRAME_MS;
        CompletableFuture<Void> done = new CompletableFuture<>();
        Thread worker = Executors.defaultThreadFactory().newThread(() -> {
            try {
                for (int i = 0; i < frames && !done.isDone(); i++) sink.accept(tone(sampleRate, i));
                done.complete(null);
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        });
        worker.setDaemon(true);
        worker.start();
        return new Synthesis() {
            @Override
            public void cancel() {
                done.cancel(false);
            }

            @Override
            public CompletableFuture<Void> done() {
                return done;
            }
        };
    }

    /** One 20 ms frame of a quiet 220 Hz tone, continuous from frame to frame. */
    private static AudioFrame tone(int rate, int index) {
        int samples = rate * FRAME_MS / 1000;
        ByteBuffer pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            double t = (index * samples + i) / (double) rate;
            pcm.putShort((short) (Math.sin(2 * Math.PI * 220 * t) * 1200));
        }
        return new AudioFrame(pcm.array(), rate, (long) index * FRAME_MS);
    }
}
