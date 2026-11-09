package com.example.g_one.speech.gnani;

import com.example.g_one.GOneProperties;
import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.Language;
import com.example.g_one.core.TextToSpeech;
import com.example.g_one.speech.SpeechProperties;
import com.example.g_one.speech.TimbreClient;
import com.example.g_one.speech.UpstreamException;
import com.example.g_one.speech.Wav;
import com.example.g_one.speech.audio.Mulaw;
import com.example.g_one.speech.audio.Reframer;
import com.example.g_one.speech.audio.StreamResampler;
import com.example.g_one.speech.lang.LanguageRoute;
import com.example.g_one.speech.lang.SpokenNumbers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Text-to-speech on Gnani Timbre. Each line gets its own WebSocket ({@code /api/v1/tts}), because Timbre has no cancel
 * message: closing the socket is how a line is stopped. Phone legs (8 kHz) stream mu-law, which is what Twilio carries;
 * wider rates stream linear PCM at the requested rate, since mu-law's 8-bit quantisation adds a hiss that follows the
 * speech. If the socket fails before any audio was
 * given out, the line is fetched from Timbre REST instead. Numbers are written out as words first, since Timbre reads
 * those better.
 */
public final class TimbreTextToSpeech implements TextToSpeech {
    private static final Logger log = LoggerFactory.getLogger(TimbreTextToSpeech.class);
    private static final int MULAW_RATE = 8000;
    private static final int FRAME_MS = 20;

    private final GOneProperties.Gnani gnani;
    private final SpeechProperties settings;
    private final TimbreClient rest;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ScheduledExecutorService watchdogs = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tts-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    public TimbreTextToSpeech(GOneProperties props, SpeechProperties settings, TimbreClient rest, ObjectMapper json) {
        this.gnani = props.gnani();
        this.settings = settings;
        this.rest = rest;
        this.json = json;
    }

    @Override
    public Synthesis speak(String text, Language language, int sampleRate, Consumer<AudioFrame> sink) {
        Job job = new Job(SpokenNumbers.normalize(text, language.code()), language, sampleRate, sink);
        job.start();
        return job;
    }

    private final class Job implements Synthesis, GnaniSocket.Events {
        private final String text;
        private final Language language;
        private final String timbreLanguage;
        private final int sampleRate;
        private final Consumer<AudioFrame> sink;
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final Reframer framer;
        /** Linear PCM instead of mu-law, for rates above what a phone line carries. */
        private final boolean wideband;

        /** Held while a frame is handed over, so that once cancel() returns no frame can follow. */
        private final Object sinkLock = new Object();
        private boolean cancelled;
        private long mediaMs;

        private volatile GnaniSocket socket;
        private volatile ScheduledFuture<?> watchdog;
        private volatile long lastActivityNanos = System.nanoTime();
        // Socket events arrive one at a time, so these need no lock.
        private StreamResampler resampler;
        /** The first half of a sample whose second half is in the next message. */
        private int heldByte = -1;
        private boolean anyAudio;
        private boolean settled;

        Job(String text, Language language, int sampleRate, Consumer<AudioFrame> sink) {
            this.text = text;
            this.language = language;
            this.timbreLanguage = LanguageRoute.of(language.code(), false).ttsLanguage();
            this.sampleRate = sampleRate;
            this.sink = sink;
            this.framer = new Reframer(sampleRate * FRAME_MS / 1000 * 2);
            this.wideband = sampleRate > MULAW_RATE;
            this.resampler = new StreamResampler(wideband ? sampleRate : MULAW_RATE, sampleRate);
        }

        void start() {
            if (text.isBlank()) {
                done.complete(null);
                return;
            }
            URI uri = URI.create(gnani.baseUrl().replaceFirst("^http", "ws") + "/api/v1/tts");
            armWatchdog();
            GnaniSocket opened = GnaniSocket.open(http, uri, Map.of("X-API-Key-ID", gnani.apiKey()), this);
            boolean over;
            synchronized (this) {
                socket = opened;
                over = settled;
            }
            if (over || isCancelled()) opened.close(); // it ended before the socket could be recorded
        }

        // ---- socket events ----

        @Override
        public void opened(GnaniSocket opened) {
            Map<String, Object> request = Map.of(
                    "text", text,
                    "voice", language.voice(),
                    "model", gnani.ttsModel(),
                    "language", timbreLanguage,
                    "audio_config", wideband
                            ? Map.of("sample_rate", sampleRate, "num_channels", 1, "sample_width", 2,
                                    "encoding", "linear_pcm", "container", "raw")
                            : Map.of("sample_rate", MULAW_RATE, "num_channels", 1, "container", "mulaw"));
            try {
                opened.sendText(json.writeValueAsString(request));
            } catch (IOException | RuntimeException e) {
                failed(e);
            }
        }

        @Override
        public synchronized void text(String message) {
            if (settled || isCancelled()) return;
            lastActivityNanos = System.nanoTime();
            JsonNode node;
            try {
                node = json.readTree(message);
            } catch (IOException e) {
                log.debug("Ignoring a message from Timbre that is not JSON");
                return;
            }
            switch (node.path("type").asText()) {
                case "audio" -> onAudio(node.path("data").path("audio").asText(""));
                case "complete" -> finish();
                case "error" -> failed(new UpstreamException("tts_failed", "Voice service reported an error."));
                default -> { }
            }
        }

        private void onAudio(String base64) {
            if (base64.isEmpty()) return;
            byte[] audio;
            try {
                audio = Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                failed(new UpstreamException("tts_failed", "Voice service sent undecodable audio."));
                return;
            }
            if (!anyAudio && looksLikeWav(audio)) {
                failed(new UpstreamException("tts_failed", "Voice service ignored the requested audio format."));
                return;
            }
            anyAudio = true;
            pushFrames(resampler.process(wideband ? wholeSamples(audio) : Mulaw.decode(audio)));
        }

        /** PCM16 may be cut mid-sample between messages; carries a split sample over to keep the bytes aligned. */
        private byte[] wholeSamples(byte[] data) {
            int held = heldByte >= 0 ? 1 : 0;
            int total = held + data.length;
            byte[] pcm = new byte[total & ~1];
            if (pcm.length > 0) {
                if (held == 1) pcm[0] = (byte) heldByte;
                System.arraycopy(data, 0, pcm, held, pcm.length - held);
                heldByte = -1;
            }
            if ((total & 1) == 1) heldByte = data[data.length - 1] & 0xff;
            return pcm;
        }

        private static boolean looksLikeWav(byte[] b) {
            return b.length >= 4 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F';
        }

        private void finish() {
            pushFrames(resampler.flush());
            byte[] rest = framer.drain();
            if (rest.length > 0) deliver(rest);
            settled = true;
            stopWatchdog();
            GnaniSocket s = socket;
            if (s != null) s.close();
            if (!isCancelled()) done.complete(null);
        }

        @Override
        public void closed(int code) {
            failed(new UpstreamException("tts_failed", "Voice service closed the connection early."));
        }

        @Override
        public synchronized void failed(Throwable error) {
            if (settled || isCancelled()) return;
            settled = true;
            stopWatchdog();
            GnaniSocket s = socket;
            if (s != null) s.close();
            if (anyAudio) {
                // Part of the line is already out; replaying it from the start would repeat words.
                log.warn("Timbre failed partway through a line");
                done.completeExceptionally(new UpstreamException("tts_failed", "Voice service failed mid-line."));
                return;
            }
            log.warn("Timbre streaming failed; using the REST path for this line");
            Thread.ofVirtual().start(this::viaRest);
        }

        // ---- REST fallback ----

        private void viaRest() {
            try {
                Wav.Decoded wav = Wav.decode(rest.synthesize(text, language.voice(), timbreLanguage, sampleRate));
                StreamResampler converter = new StreamResampler(wav.sampleRate(), sampleRate);
                pushFrames(converter.process(wav.pcm16le()));
                pushFrames(converter.flush());
                byte[] remainder = framer.drain();
                if (remainder.length > 0) deliver(remainder);
                if (!isCancelled()) done.complete(null);
            } catch (RuntimeException e) {
                log.warn("Timbre REST failed too");
                done.completeExceptionally(e);
            }
        }

        // ---- frames ----

        private void pushFrames(byte[] pcm) {
            for (byte[] frame : framer.push(pcm)) deliver(frame);
        }

        private void deliver(byte[] pcm) {
            synchronized (sinkLock) {
                if (cancelled) return;
                sink.accept(new AudioFrame(pcm, sampleRate, mediaMs));
                mediaMs += pcm.length / 2 * 1000L / sampleRate;
            }
        }

        private boolean isCancelled() {
            synchronized (sinkLock) {
                return cancelled;
            }
        }

        // ---- watchdog ----

        /** Timbre going quiet for too long counts as a failure, before the first audio and between chunks alike. */
        private void armWatchdog() {
            watchdog = watchdogs.schedule(this::checkActivity, settings.ttsFirstAudioTimeoutMs(), TimeUnit.MILLISECONDS);
        }

        private void checkActivity() {
            long idleMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastActivityNanos);
            if (idleMs >= settings.ttsFirstAudioTimeoutMs()) {
                failed(new UpstreamException("tts_failed", "Voice service took too long."));
            } else if (!done.isDone()) {
                watchdog = watchdogs.schedule(this::checkActivity, settings.ttsFirstAudioTimeoutMs() - idleMs, TimeUnit.MILLISECONDS);
            }
        }

        private void stopWatchdog() {
            ScheduledFuture<?> w = watchdog;
            if (w != null) w.cancel(false);
        }

        // ---- Synthesis ----

        @Override
        public void cancel() {
            synchronized (sinkLock) {
                if (cancelled || done.isDone()) return;
                cancelled = true;
            }
            stopWatchdog();
            GnaniSocket s = socket;
            if (s != null) s.close();
            done.cancel(false);
        }

        @Override
        public CompletableFuture<Void> done() {
            return done;
        }
    }
}
