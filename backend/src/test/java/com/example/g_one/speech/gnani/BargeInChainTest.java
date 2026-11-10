package com.example.g_one.speech.gnani;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.g_one.FakeUpstreams;
import com.example.g_one.GOneProperties;
import com.example.g_one.agent.AgentBrain;
import com.example.g_one.agent.pipeline.CallPipeline;
import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.Language;
import com.example.g_one.core.LegRegistry;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.TextToSpeech;
import com.example.g_one.core.Turn;
import com.example.g_one.simulator.SimulatorLeg;
import com.example.g_one.speech.PrismClient;
import com.example.g_one.speech.SpeechProperties;
import com.example.g_one.speech.audio.Pcm;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Barge-in across the real server chain, with only the upstreams faked: a simulator leg (the browser's socket), the call
 * pipeline, Prisma realtime against a fake socket server, and a speech synthesiser that streams a long line in real
 * time as Timbre does. The person's microphone audio arrives while G-One is speaking.
 */
class BargeInChainTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Language HINDI = new Language("hi-IN", "Hindi", "Nalini");
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String CLEAR = "{\"type\":\"clear\"}";
    private static final String GREETING = "Namaste, main G-One hoon. Aaj mandi mein gehun ka bhav bahut achha chal raha hai.";

    /** What the browser receives, in order: "audio" for a playback frame, the text of every other message. */
    private final List<String> toBrowser = new CopyOnWriteArrayList<>();
    private final List<String> heardTurns = new CopyOnWriteArrayList<>();
    private final StreamingSpeech speech = new StreamingSpeech();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private FakeGnaniSocketServer prisma;
    private FakeUpstreams rest;
    private SimulatorLeg leg;
    private Thread microphone;
    private final AtomicBoolean micOn = new AtomicBoolean(true);

    /** A synthesiser that emits one 20 ms frame every 20 ms, for ten seconds, until it is cancelled. */
    static final class StreamingSpeech implements TextToSpeech {
        final List<String> texts = new CopyOnWriteArrayList<>();
        final List<Synthesis> cancelled = new CopyOnWriteArrayList<>();

        @Override
        public Synthesis speak(String text, Language language, int sampleRate, Consumer<AudioFrame> sink) {
            texts.add(text);
            CompletableFuture<Void> done = new CompletableFuture<>();
            Synthesis s = new Synthesis() {
                @Override
                public void cancel() {
                    cancelled.add(this);
                    done.cancel(false);
                }

                @Override
                public CompletableFuture<Void> done() {
                    return done;
                }
            };
            Thread t = new Thread(() -> {
                for (int i = 0; i < 500 && !done.isDone(); i++) {
                    sink.accept(AudioFrame.silence(sampleRate, 20, i * 20L));
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                done.complete(null);
            }, "fake-timbre");
            t.setDaemon(true);
            t.start();
            return s;
        }
    }

    private final AgentBrain brain = new AgentBrain() {
        @Override
        public Reply greet(CallContext ctx) {
            return new Reply(GREETING, HINDI, false);
        }

        @Override
        public Reply respond(CallContext ctx, List<Line> history, Turn latest) {
            heardTurns.add(latest.text());
            return new Reply("Ji, boliye.", HINDI, false);
        }
    };

    @BeforeEach
    void start() throws Exception {
        prisma = new FakeGnaniSocketServer();
        prisma.onConnect(c -> c.send("{\"type\":\"connected\",\"config\":{\"sample_rate\":8000,\"chunk_size\":512}}"));
        rest = new FakeUpstreams();
        SpeechProperties settings = new SpeechProperties("auto", 0.7, 200, 400, 180, 30, 700, 300, 8000, -42, 600, 350,
                1200, 14, 10, 5000);
        GOneProperties props = new GOneProperties(List.of(), "", null,
                new GOneProperties.Gnani(prisma.baseUrl(), FakeUpstreams.KEY, "", "timbre-v2.5", 5), null);
        GOneProperties forRest = new GOneProperties(List.of(), "", null,
                new GOneProperties.Gnani(rest.baseUrl(), FakeUpstreams.KEY, "", "timbre-v2.5", 5), null);
        PrismaSpeechToText stt = new PrismaSpeechToText(props, settings, new PrismClient(forRest, JSON), JSON);

        LegRegistry registry = new LegRegistry();
        new CallPipeline(brain, stt, speech, new InMemoryMemoryStore(), new ConsoleBus(), List.of(), executor,
                System::currentTimeMillis).attach(registry);
        leg = new SimulatorLeg(CallId.random(), LegRole.FARMER, HINDI, null, new SimulatorLeg.Channel() {
            @Override
            public void sendAudio(byte[] pcm16le) {
                toBrowser.add("audio");
            }

            @Override
            public void sendText(String json) {
                toBrowser.add(json);
            }

            @Override
            public void close() {}
        });
        registry.register(leg);
    }

    @AfterEach
    void stop() throws Exception {
        micOn.set(false);
        if (microphone != null) microphone.join(1000);
        leg.hangup();
        executor.shutdownNow();
        prisma.close();
        rest.close();
    }

    /** The browser's microphone: 20 ms of 8 kHz voiced PCM16 every 20 ms, sent while G-One is still playing. */
    private void startTalking() {
        byte[] pcm = new byte[160 * 2];
        for (int i = 0; i < 160; i++) Pcm.put(pcm, i, Pcm.clamp(9000 * Math.sin(2 * Math.PI * 300 * i / 8000.0)));
        microphone = new Thread(() -> {
            while (micOn.get()) {
                leg.receive(pcm);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "fake-mic");
        microphone.setDaemon(true);
        microphone.start();
    }

    private FakeGnaniSocketServer.Conn greetingPlaysAndTheMicrophoneReachesPrisma() {
        await().atMost(WAIT).until(() -> toBrowser.contains("audio"));
        await().atMost(WAIT).until(() -> prisma.conns.size() == 1);
        FakeGnaniSocketServer.Conn c = prisma.conns.get(0);
        startTalking();
        await().atMost(WAIT).until(() -> c.binary.size() >= 3);
        assertThat(speech.cancelled).as("still speaking").isEmpty();
        return c;
    }

    private long audioAfterClear() {
        int at = toBrowser.indexOf(CLEAR);
        return toBrowser.subList(at + 1, toBrowser.size()).stream().filter("audio"::equals).count();
    }

    @Test
    void speechOverTheAssistantCancelsSynthesisFlushesTheBrowserAndTheNextTurnIsAnswered() throws Exception {
        FakeGnaniSocketServer.Conn c = greetingPlaysAndTheMicrophoneReachesPrisma();

        long saidAt = System.nanoTime();
        c.send("{\"type\":\"speech_start\"}");
        await().atMost(WAIT).until(() -> toBrowser.contains(CLEAR));
        long tookMs = (System.nanoTime() - saidAt) / 1_000_000;
        assertThat(tookMs).as("speech start to clear: the 700 ms barge-in gate plus a little").isBetween(650L, 1500L);
        assertThat(speech.cancelled).hasSize(1);
        Thread.sleep(200);
        assertThat(audioAfterClear()).as("no greeting frame after the clear").isZero();

        c.send("{\"type\":\"processing\"}");
        c.send("{\"type\":\"transcript\",\"text\":\"ruko mujhe pyaaz ka bhav batao\",\"is_final\":true}");
        await().atMost(WAIT).until(() -> heardTurns.size() == 1);
        assertThat(heardTurns).containsExactly("ruko mujhe pyaaz ka bhav batao");
        await().atMost(WAIT).until(() -> speech.texts.size() == 2);
        assertThat(speech.texts).containsExactly(GREETING, "Ji, boliye.");
        await().atMost(WAIT).until(() -> audioAfterClear() > 0);
    }

    @Test
    void anAcknowledgementOverTheAssistantDoesNotStopIt() throws Exception {
        FakeGnaniSocketServer.Conn c = greetingPlaysAndTheMicrophoneReachesPrisma();

        c.send("{\"type\":\"speech_start\"}");
        c.send("{\"type\":\"processing\"}");
        c.send("{\"type\":\"transcript\",\"text\":\"haan ji\",\"is_final\":true}");
        Thread.sleep(1200);

        assertThat(toBrowser).doesNotContain(CLEAR);
        assertThat(speech.cancelled).isEmpty();
        assertThat(heardTurns).isEmpty();
        long before = toBrowser.stream().filter("audio"::equals).count();
        Thread.sleep(200);
        assertThat(toBrowser.stream().filter("audio"::equals).count()).as("the greeting keeps playing").isGreaterThan(before);
    }

    /**
     * Echo that got past the browser's echo cancellation and the microphone gate: words of its own line stop G-One (the
     * transcript announces speech before the turn), but G-One does not answer its own words as if the farmer said them.
     */
    @Test
    void theAssistantsOwnVoiceComingBackIsNotAnsweredAsATurn() throws Exception {
        FakeGnaniSocketServer.Conn c = greetingPlaysAndTheMicrophoneReachesPrisma();

        c.send("{\"type\":\"speech_start\"}");
        c.send("{\"type\":\"processing\"}");
        c.send("{\"type\":\"transcript\",\"text\":\"mandi mein gehun ka bhav\",\"is_final\":true}");
        Thread.sleep(1200);

        assertThat(heardTurns).isEmpty();
        assertThat(speech.texts).containsExactly(GREETING);
    }
}
