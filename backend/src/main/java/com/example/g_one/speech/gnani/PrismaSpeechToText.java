package com.example.g_one.speech.gnani;

import com.example.g_one.GOneProperties;
import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.Language;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.SttListener;
import com.example.g_one.core.SttSession;
import com.example.g_one.core.Turn;
import com.example.g_one.core.TypedTurns;
import com.example.g_one.speech.PrismClient;
import com.example.g_one.speech.SpeechProperties;
import com.example.g_one.speech.UpstreamException;
import com.example.g_one.speech.audio.Pcm;
import com.example.g_one.speech.audio.Reframer;
import com.example.g_one.speech.audio.StreamResampler;
import com.example.g_one.speech.lang.LanguageRoute;
import com.example.g_one.speech.turn.EnergyVad;
import com.example.g_one.speech.turn.TurnAssembler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Speech-to-text on Gnani Prisma. Each leg gets one realtime WebSocket ({@code /stt/v3/stream}) fed 8 kHz PCM16 in frames
 * of exactly 512 samples every 64 ms; Prisma finds the turns and sends finished transcripts only, no partial ones. When
 * the socket cannot be had, the leg falls back to our own energy-based turn detection with one Prisma REST upload per
 * turn. Both paths go through {@link TurnAssembler}.
 */
public final class PrismaSpeechToText implements SpeechToText, TypedTurns {
    private static final Logger log = LoggerFactory.getLogger(PrismaSpeechToText.class);
    private final Map<LegId, Session> activeSessions = new java.util.concurrent.ConcurrentHashMap<>();

    static final int PRISMA_RATE = 8000;
    static final int FRAME_BYTES = 1024;
    private static final long FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(64);
    /** Audio kept while the socket is away, about five seconds; older frames are dropped first. */
    private static final int MAX_BACKLOG_FRAMES = 78;
    private static final int BEHIND_FRAMES = 2;
    private static final int MAX_CONNECT_ATTEMPTS = 3;
    private static final int CONNECTED_TIMEOUT_SECONDS = 5;
    private static final long STABLE_SESSION_MS = 2_000;
    private static final long ROTATE_RETRY_MS = 2_000;
    private static final int POLICY_VIOLATION = 1008;
    private static final int VAD_WINDOW_BYTES = 320;
    private static final int PRE_ROLL_WINDOWS = 15;

    private final GOneProperties.Gnani gnani;
    private final SpeechProperties settings;
    private final PrismClient prism;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final boolean prismaV3;

    public PrismaSpeechToText(GOneProperties props, SpeechProperties settings, PrismClient prism, ObjectMapper json) {
        this.gnani = props.gnani();
        this.settings = settings;
        this.prism = prism;
        this.json = json;
        this.prismaV3 = gnani.sttModel().toLowerCase().startsWith("prisma-v3");
    }

    @Override
    public SttSession open(LegId legId, LegRole role, Language language, SttListener listener) {
        Session session = new Session(legId, role, language, listener);
        activeSessions.put(legId, session);
        session.start();
        return session;
    }

    @Override
    public void say(LegId legId, String text) {
        Session s = activeSessions.get(legId);
        if (s != null && text != null && !text.isBlank()) {
            s.injectTurn(text.strip());
        }
    }

    private final class Session implements SttSession {
        private final LegId legId;
        private final LegRole role;
        private final Language language;
        private final SttListener listener;
        private final LanguageRoute route;

        private final ScheduledThreadPoolExecutor events;
        private final ExecutorService uploads;
        private final Thread sender;
        private final TurnAssembler assembler;
        private final LinkedBlockingDeque<byte[]> outgoing = new LinkedBlockingDeque<>();
        private final double silenceLevel = Math.pow(10, settings.vadThresholdDbfs() / 20);
        private volatile Thread eventThread;

        // Used only by feed().
        private final Reframer framer = new Reframer(FRAME_BYTES);
        private StreamResampler resampler;
        private int resamplerRate;

        /** Guards the fields below. */
        private final Object state = new Object();
        private GnaniSocket current;
        /** Non-null once Prisma said "connected": frames may flow. */
        private GnaniSocket ready;
        private long generation;
        private boolean restMode;
        private boolean closed;

        // Used only on the event thread.
        private int failures;
        private long connectedAtMs;
        private ScheduledFuture<?> connectWatchdog;
        private ScheduledFuture<?> rotation;

        // Used only on the sender thread, in REST mode.
        private final Reframer windows = new Reframer(VAD_WINDOW_BYTES);
        private final EnergyVad vad = new EnergyVad(new EnergyVad.Config(20, settings.vadThresholdDbfs(), 3, 4000, 120,
                settings.vadHangoverMs(), 29_000, settings.vadShortTurnMs(), settings.vadShortHangoverMs()));
        private final Deque<byte[]> preRoll = new ArrayDeque<>();
        private final ByteArrayOutputStream turnAudio = new ByteArrayOutputStream();
        private boolean inTurn;

        Session(LegId legId, LegRole role, Language language, SttListener listener) {
            this.legId = legId;
            this.role = role;
            this.language = language;
            this.listener = listener;
            this.route = LanguageRoute.of(language.code(), prismaV3);
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "stt-events-" + legId.value());
                thread.setDaemon(true);
                eventThread = thread;
                return thread;
            };
            events = new ScheduledThreadPoolExecutor(1, factory);
            events.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            events.setRemoveOnCancelPolicy(true);
            uploads = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "stt-upload-" + legId.value());
                thread.setDaemon(true);
                return thread;
            });
            sender = new Thread(this::sendLoop, "stt-send-" + legId.value());
            sender.setDaemon(true);
            assembler = new TurnAssembler(
                    new TurnAssembler.Config(settings.bargeInMinSpeechMs(), settings.continuationHoldMs(), settings.maxHoldMs()),
                    this::after, System::currentTimeMillis, new TurnAssembler.Output() {
                        @Override
                        public void speechStart() {
                            deliver(listener::onSpeechStart);
                        }

                        @Override
                        public void finalTurn(String text, long startedMs, long endedMs) {
                            deliver(() -> listener.onFinalTurn(new Turn(legId, role, language, text, startedMs, endedMs)));
                        }
                    });
        }

        void start() {
            if (settings.restOnly()) {
                synchronized (state) {
                    restMode = true;
                }
            } else {
                post(this::connect);
            }
            sender.start();
        }

        // ---- audio in ----

        @Override
        public void feed(AudioFrame frame) {
            synchronized (state) {
                if (closed) return;
            }
            for (byte[] piece : framer.push(toPrismaRate(frame))) {
                outgoing.offerLast(piece);
                if (outgoing.size() > MAX_BACKLOG_FRAMES) {
                    outgoing.pollFirst();
                }
            }
        }

        private byte[] toPrismaRate(AudioFrame frame) {
            if (frame.sampleRate() == PRISMA_RATE) {
                return frame.pcm16le();
            }
            if (resampler == null || resamplerRate != frame.sampleRate()) {
                resampler = new StreamResampler(frame.sampleRate(), PRISMA_RATE);
                resamplerRate = frame.sampleRate();
            }
            return resampler.process(frame.pcm16le());
        }

        // ---- sender thread ----

        private void sendLoop() {
            long nextSendNanos = System.nanoTime();
            try {
                while (true) {
                    byte[] frame = outgoing.takeFirst();
                    GnaniSocket socket = awaitSendable();
                    if (socket == null) {
                        if (isClosed()) return;
                        consumeForRest(frame);
                        continue;
                    }
                    // Falling behind (a reconnect, say): silence can go, so the backlog drains at the steady 64 ms pace.
                    if (outgoing.size() >= BEHIND_FRAMES && Pcm.rms(frame) < silenceLevel) {
                        continue;
                    }
                    long wait = nextSendNanos - System.nanoTime();
                    if (wait > 0) {
                        LockSupport.parkNanos(wait);
                        if (Thread.interrupted()) return;
                    }
                    nextSendNanos = Math.max(System.nanoTime(), nextSendNanos) + FRAME_NANOS;
                    send(socket, frame);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** Blocks until frames may go out. Returns null when they go to the REST path, or the session closed. */
        private GnaniSocket awaitSendable() throws InterruptedException {
            synchronized (state) {
                while (ready == null && !restMode && !closed) {
                    state.wait();
                }
                return closed ? null : ready;
            }
        }

        private boolean isClosed() {
            synchronized (state) {
                return closed;
            }
        }

        private void send(GnaniSocket socket, byte[] frame) {
            try {
                socket.sendBinary(frame);
            } catch (IOException | RuntimeException e) {
                log.debug("Could not send audio to Prisma on leg {}: {}", legId, e.toString());
                long gen;
                synchronized (state) {
                    if (ready != socket) return; // already being replaced
                    gen = generation;
                }
                post(() -> lost(gen, 0, e));
            }
        }

        // ---- connection, on the event thread ----

        private void connect() {
            long gen;
            synchronized (state) {
                if (closed || restMode) return;
                gen = ++generation;
            }
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("x-api-key-id", gnani.apiKey());
            headers.put("language_code", route.sttHints());
            if (!gnani.sttModel().isBlank()) headers.put("model", gnani.sttModel());
            if (!route.format().isBlank()) headers.put("format", route.format());
            headers.put("x-sample-rate", String.valueOf(PRISMA_RATE));
            headers.put("x-vad-threshold", String.valueOf(settings.vadThreshold()));
            headers.put("x-min-speech-ms", String.valueOf(settings.minSpeechMs()));
            headers.put("x-min-silence-ms", String.valueOf(settings.minSilenceMs()));
            headers.put("x-idle-timeout-sec", String.valueOf(settings.idleTimeoutSec()));
            headers.put("x-max-speech-s", String.valueOf(settings.maxSpeechSec()));
            headers.put("x-api-request-id", legId.value());
            URI uri = URI.create(gnani.baseUrl().replaceFirst("^http", "ws") + "/stt/v3/stream");
            GnaniSocket socket = GnaniSocket.open(http, uri, headers, new GnaniSocket.Events() {
                @Override
                public void opened(GnaniSocket opened) {
                    post(() -> onOpened(gen, opened));
                }

                @Override
                public void text(String message) {
                    post(() -> onMessage(gen, message));
                }

                @Override
                public void closed(int code) {
                    post(() -> lost(gen, code, null));
                }

                @Override
                public void failed(Throwable error) {
                    post(() -> lost(gen, 0, error));
                }
            });
            boolean abandon;
            synchronized (state) {
                abandon = closed || generation != gen;
                if (!abandon) current = socket;
            }
            if (abandon) {
                socket.close();
                return;
            }
            cancel(connectWatchdog);
            connectWatchdog = events.schedule(() -> lost(gen, 0, new IOException("No connected message from Prisma")),
                    CONNECTED_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private void onOpened(long gen, GnaniSocket socket) {
            boolean abandon;
            synchronized (state) {
                abandon = closed || generation != gen;
            }
            if (abandon) socket.close();
        }

        private void onMessage(long gen, String message) {
            synchronized (state) {
                if (closed || generation != gen) return;
            }
            JsonNode node;
            try {
                node = json.readTree(message);
            } catch (IOException e) {
                log.debug("Ignoring a message from Prisma that is not JSON on leg {}", legId);
                return;
            }
            switch (node.path("type").asText()) {
                case "connected" -> onConnected(gen);
                case "speech_start" -> assembler.speechStarted();
                case "processing" -> assembler.speechEnded();
                case "transcript" -> {
                    if (node.path("is_final").asBoolean(true)) {
                        assembler.transcript(node.path("text").asText(""));
                    }
                }
                case "error" -> {
                    log.warn("Prisma reported an error on leg {}", legId);
                    assembler.transcript("");
                    deliver(() -> listener.onError(new UpstreamException("stt_failed", "Speech recognition reported an error.")));
                }
                default -> { }
            }
        }

        private void onConnected(long gen) {
            cancel(connectWatchdog);
            connectWatchdog = null;
            connectedAtMs = System.currentTimeMillis();
            synchronized (state) {
                ready = current;
                state.notifyAll();
            }
            cancel(rotation);
            rotation = events.schedule(() -> rotate(gen), settings.sessionRotateMinutes(), TimeUnit.MINUTES);
        }

        /** Prisma caps a session at 15 minutes, so swap sockets first, at a moment nobody is speaking. */
        private void rotate(long gen) {
            synchronized (state) {
                if (closed || generation != gen) return;
            }
            if (!assembler.idle()) {
                rotation = events.schedule(() -> rotate(gen), ROTATE_RETRY_MS, TimeUnit.MILLISECONDS);
                return;
            }
            dropSocket();
            connect();
        }

        private void lost(long gen, int code, Throwable error) {
            synchronized (state) {
                if (closed || generation != gen) return;
            }
            boolean wasStable = connectedAtMs > 0 && System.currentTimeMillis() - connectedAtMs >= STABLE_SESSION_MS;
            connectedAtMs = 0;
            dropSocket();
            synchronized (state) {
                generation++; // late events from the dropped socket no longer count
            }
            assembler.connectionLost();
            if (error instanceof WebSocketHandshakeException || code == POLICY_VIOLATION) {
                fallBackToRest();
                return;
            }
            if (wasStable) {
                failures = 0;
                connect();
                return;
            }
            failures++;
            if (failures >= MAX_CONNECT_ATTEMPTS) {
                fallBackToRest();
                return;
            }
            events.schedule(this::connect, (long) settings.reconnectBaseMs() << (failures - 1), TimeUnit.MILLISECONDS);
        }

        private void dropSocket() {
            GnaniSocket old;
            synchronized (state) {
                old = current;
                current = null;
                ready = null;
            }
            cancel(connectWatchdog);
            connectWatchdog = null;
            cancel(rotation);
            rotation = null;
            if (old != null) old.close();
        }

        private void fallBackToRest() {
            log.warn("Prisma realtime is unavailable on leg {}; using turn-by-turn uploads", legId);
            synchronized (state) {
                restMode = true;
                state.notifyAll();
            }
            deliver(() -> listener.onError(new UpstreamException("stt_degraded", "Live recognition is unavailable; using uploads.")));
        }

        // ---- REST fallback, on the sender thread ----

        private void consumeForRest(byte[] frame) {
            for (byte[] window : windows.push(frame)) {
                EnergyVad.Event event = vad.process(window);
                if (inTurn) {
                    turnAudio.writeBytes(window);
                } else {
                    preRoll.addLast(window);
                    if (preRoll.size() > PRE_ROLL_WINDOWS) preRoll.removeFirst();
                }
                if (event == EnergyVad.Event.SPEECH_START) {
                    inTurn = true;
                    turnAudio.reset();
                    preRoll.forEach(turnAudio::writeBytes);
                    preRoll.clear();
                    post(assembler::speechStarted);
                } else if (event == EnergyVad.Event.SPEECH_END) {
                    inTurn = false;
                    byte[] pcm = turnAudio.toByteArray();
                    turnAudio.reset();
                    post(assembler::speechEnded);
                    upload(pcm);
                }
            }
        }

        private void upload(byte[] pcm) {
            try {
                uploads.execute(() -> {
                    try {
                        String text = prism.transcribePcm(pcm, PRISMA_RATE, route.sttSingle(), route.format());
                        post(() -> assembler.transcript(text));
                    } catch (RuntimeException e) {
                        log.warn("A Prisma upload failed on leg {}", legId);
                        post(() -> {
                            assembler.transcript("");
                            deliver(() -> listener.onError(e));
                        });
                    }
                });
            } catch (RejectedExecutionException e) {
                // Closed while the turn was ending.
            }
        }

        // ---- plumbing ----

        private void post(Runnable task) {
            try {
                events.execute(task);
            } catch (RejectedExecutionException e) {
                // Closed: nothing more to report.
            }
        }

        private TurnAssembler.Cancel after(long delayMs, Runnable task) {
            ScheduledFuture<?> future = events.schedule(task, delayMs, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }

        private void cancel(ScheduledFuture<?> future) {
            if (future != null) future.cancel(false);
        }

        private void deliver(Runnable callback) {
            if (isClosed()) return;
            try {
                callback.run();
            } catch (RuntimeException e) {
                log.warn("A speech listener failed on leg {}", legId, e);
            }
        }

        void injectTurn(String text) {
            long now = System.currentTimeMillis();
            deliver(listener::onSpeechStart);
            deliver(() -> listener.onFinalTurn(new Turn(legId, role, language, text, now - 500, now)));
        }

        @Override
        public void close() {
            activeSessions.remove(legId);
            GnaniSocket socket;
            synchronized (state) {
                if (closed) return;
                closed = true;
                socket = current;
                current = null;
                ready = null;
                state.notifyAll();
            }
            sender.interrupt();
            uploads.shutdownNow();
            events.shutdown();
            if (socket != null) socket.close();
            if (Thread.currentThread() != eventThread) {
                try {
                    if (!events.awaitTermination(2, TimeUnit.SECONDS)) {
                        log.warn("Speech events on leg {} did not stop in time", legId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
