package com.example.g_one.speech.gnani;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.example.g_one.FakeUpstreams;
import com.example.g_one.GOneProperties;
import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.Language;
import com.example.g_one.core.TextToSpeech;
import com.example.g_one.speech.SpeechProperties;
import com.example.g_one.speech.TimbreClient;
import com.example.g_one.speech.audio.Pcm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Timbre adapter against a fake streaming socket and a fake REST endpoint. */
class TimbreTextToSpeechTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Language HINDI = new Language("hi-IN", "Hindi", "Nalini");
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final List<AudioFrame> frames = new CopyOnWriteArrayList<>();
    private FakeGnaniSocketServer ws;
    private FakeUpstreams rest;

    @BeforeEach
    void start() throws Exception {
        ws = new FakeGnaniSocketServer();
        rest = new FakeUpstreams();
    }

    @AfterEach
    void stop() throws Exception {
        ws.close();
        rest.close();
    }

    private TimbreTextToSpeech adapter(int stallMs) {
        SpeechProperties s = new SpeechProperties("auto", 0.7, 200, 400, 180, 30, 700, 1200, 8000, -42, 600, 350, 1200, 14, 10, stallMs);
        GOneProperties live = new GOneProperties(List.of(), "", null,
                new GOneProperties.Gnani(ws.baseUrl(), FakeUpstreams.KEY, "", "timbre-v2.5", 5), null);
        GOneProperties forRest = new GOneProperties(List.of(), "", null,
                new GOneProperties.Gnani(rest.baseUrl(), FakeUpstreams.KEY, "", "timbre-v2.5", 5), null);
        return new TimbreTextToSpeech(live, s, new TimbreClient(forRest), JSON);
    }

    private TextToSpeech.Synthesis speak(String text, Language language, int rate) {
        return adapter(5000).speak(text, language, rate, frames::add);
    }

    private static String audioMessage(int index, byte[] mulaw) {
        return "{\"type\":\"audio\",\"data\":{\"chunk_index\":" + index + ",\"audio\":\""
                + Base64.getEncoder().encodeToString(mulaw) + "\",\"is_final\":false}}";
    }

    private static final String COMPLETE = "{\"type\":\"complete\",\"data\":{\"chunk_index\":9,\"audio\":\"\",\"is_final\":true}}";

    private static byte[] mulawBytes(int n, byte value) {
        byte[] b = new byte[n];
        Arrays.fill(b, value);
        return b;
    }

    private FakeGnaniSocketServer.Conn conn(int index) {
        await().atMost(WAIT).until(() -> ws.conns.size() > index);
        return ws.conns.get(index);
    }

    @Test
    void sendsDocumentedRequestWithNumbersAsWords() throws Exception {
        ws.onConnect(c -> { });
        speak("गेहूं का भाव ₹2,400 प्रति क्विंटल है", HINDI, 8000);
        FakeGnaniSocketServer.Conn c = conn(0);
        JsonNode request = JSON.readTree(c.firstText.get(5, TimeUnit.SECONDS));
        assertThat(c.path).isEqualTo("/api/v1/tts");
        assertThat(c.headers.get("x-api-key-id")).isEqualTo(FakeUpstreams.KEY);
        assertThat(request.path("text").asText()).isEqualTo("गेहूं का भाव दो हज़ार चार सौ रुपये प्रति क्विंटल है");
        assertThat(request.path("voice").asText()).isEqualTo("Nalini");
        assertThat(request.path("model").asText()).isEqualTo("timbre-v2.5");
        assertThat(request.path("language").asText()).isEqualTo("hi-IN");
        assertThat(request.path("audio_config").path("container").asText()).isEqualTo("mulaw");
        assertThat(request.path("audio_config").path("sample_rate").asInt()).isEqualTo(8000);
    }

    @Test
    void benglishIsSpokenAsBengali() throws Exception {
        ws.onConnect(c -> { });
        speak("amar 20 kg dhan ache", new Language("bn-en", "Benglish", "Kirra"), 8000);
        JsonNode request = JSON.readTree(conn(0).firstText.get(5, TimeUnit.SECONDS));
        assertThat(request.path("language").asText()).isEqualTo("bn-IN");
        assertThat(request.path("voice").asText()).isEqualTo("Kirra");
        assertThat(request.path("text").asText()).isEqualTo("amar কুড়ি কেজি dhan ache");
    }

    @Test
    void unalignedChunksBecomeEven20MsFramesInOrder() throws Exception {
        ws.onConnect(c -> {
            c.send("{\"type\":\"start\",\"message\":\"Streaming started\"}");
            c.send(audioMessage(1, mulawBytes(100, (byte) 0x80)));
            c.send(audioMessage(2, mulawBytes(300, (byte) 0x80)));
            c.send(audioMessage(3, mulawBytes(70, (byte) 0x80)));
            c.send(COMPLETE);
        });
        TextToSpeech.Synthesis s = speak("namaste", HINDI, 8000);
        s.done().get(5, TimeUnit.SECONDS);

        assertThat(frames).hasSize(3);
        assertThat(frames.get(0).pcm16le()).hasSize(320);
        assertThat(frames.get(1).pcm16le()).hasSize(320);
        assertThat(frames.get(2).pcm16le()).hasSize(300); // the short tail is not padded
        assertThat(frames.get(1).timestampMs()).isEqualTo(20);
        assertThat(frames.get(0).sampleRate()).isEqualTo(8000);
        assertThat(Pcm.sample(frames.get(0).pcm16le(), 0)).isEqualTo((short) 32124);
        assertThat(conn(0).clientClosed.get(5, TimeUnit.SECONDS)).isEqualTo(1000);
    }

    @Test
    void widebandAsksTimbreForLinearPcmAtThatRate() throws Exception {
        ws.onConnect(c -> { });
        speak("namaste", HINDI, 24000);
        JsonNode config = JSON.readTree(conn(0).firstText.get(5, TimeUnit.SECONDS)).path("audio_config");
        assertThat(config.path("container").asText()).isEqualTo("raw");
        assertThat(config.path("encoding").asText()).isEqualTo("linear_pcm");
        assertThat(config.path("sample_width").asInt()).isEqualTo(2);
        assertThat(config.path("sample_rate").asInt()).isEqualTo(24000);
    }

    @Test
    void widebandAudioArrivesUnchangedEvenWhenMessagesSplitASample() throws Exception {
        byte[] pcm = new byte[4801 * 2];
        for (int i = 0; i < pcm.length / 2; i++) {
            short v = (short) (8000 * Math.sin(2 * Math.PI * 210 * i / 24000.0));
            pcm[2 * i] = (byte) v;
            pcm[2 * i + 1] = (byte) (v >> 8);
        }
        int[] cuts = {1, 511, 512, 1403, 837, 2048};
        ws.onConnect(c -> {
            int at = 0;
            int index = 1;
            for (int cut : cuts) {
                c.send(audioMessage(index++, Arrays.copyOfRange(pcm, at, at + cut)));
                at += cut;
            }
            c.send(audioMessage(index, Arrays.copyOfRange(pcm, at, pcm.length)));
            c.send(COMPLETE);
        });

        speak("namaste", HINDI, 24000).done().get(5, TimeUnit.SECONDS);

        ByteArrayOutputStream heard = new ByteArrayOutputStream();
        for (AudioFrame f : frames) {
            assertThat(f.sampleRate()).isEqualTo(24000);
            heard.write(f.pcm16le());
        }
        assertThat(frames.get(0).pcm16le()).hasSize(960);
        assertThat(heard.toByteArray()).isEqualTo(pcm);
    }

    @Test
    void cancelClosesTheSocketAndStopsFrames() throws Exception {
        ws.onConnect(c -> c.send(audioMessage(1, mulawBytes(800, (byte) 0x80))));
        TextToSpeech.Synthesis s = speak("ek lambi baat", HINDI, 8000);
        await().atMost(WAIT).until(() -> frames.size() == 5);
        FakeGnaniSocketServer.Conn c = conn(0);

        s.cancel();
        s.cancel(); // idempotent
        int heard = frames.size();
        c.send(audioMessage(2, mulawBytes(800, (byte) 0x80)));
        assertThat(c.clientClosed.get(5, TimeUnit.SECONDS)).isEqualTo(1000);
        Thread.sleep(100);

        assertThat(frames).hasSize(heard);
        assertThat(s.done()).isCancelled();
        assertThatThrownBy(() -> s.done().get()).isInstanceOf(CancellationException.class);
        assertThat(rest.timbre).as("a cancelled line must not fall back").isEmpty();
    }

    @Test
    void cancelBeforeTheSocketOpensLeavesNothingBehind() throws Exception {
        TextToSpeech.Synthesis s = speak("jaldi", HINDI, 8000);
        s.cancel();
        Thread.sleep(200);
        assertThat(frames).isEmpty();
        assertThat(s.done()).isCancelled();
        assertThat(rest.timbre).isEmpty();
    }

    @Test
    void cancelAfterCompletionIsHarmless() throws Exception {
        ws.onConnect(c -> c.send(COMPLETE));
        TextToSpeech.Synthesis s = speak("bas", HINDI, 8000);
        s.done().get(5, TimeUnit.SECONDS);
        s.cancel();
        assertThat(s.done()).isCompleted().isNotCancelled();
    }

    @Test
    void errorBeforeAudioFallsBackToRest() throws Exception {
        ws.onConnect(c -> c.send("{\"type\":\"error\",\"message\":\"Invalid text or audio configuration.\"}"));
        speak("namaste", HINDI, 8000).done().get(5, TimeUnit.SECONDS);
        assertThat(rest.timbre).hasSize(1);
        assertThat(frames.stream().mapToInt(AudioFrame::samples).sum()).isEqualTo(4000); // 0.5 s at 24 kHz becomes 8 kHz
        assertThat(frames.get(0).sampleRate()).isEqualTo(8000);
    }

    @Test
    void wavWhereMulawWasRequestedFallsBackRatherThanPlayingNoise() throws Exception {
        ws.onConnect(c -> c.send(audioMessage(1, "RIFFxxxxWAVE".getBytes())));
        speak("namaste", HINDI, 8000).done().get(5, TimeUnit.SECONDS);
        assertThat(rest.timbre).hasSize(1);
    }

    @Test
    void rejectedHandshakeFallsBackToRest() throws Exception {
        ws.rejections.set(1);
        speak("namaste", HINDI, 8000).done().get(5, TimeUnit.SECONDS);
        assertThat(rest.timbre).hasSize(1);
    }

    @Test
    void unreachableSocketFallsBackToRest() throws Exception {
        ws.close();
        speak("namaste", HINDI, 8000).done().get(5, TimeUnit.SECONDS);
        assertThat(rest.timbre).hasSize(1);
    }

    @Test
    void closeBeforeAudioFallsBackToRest() throws Exception {
        ws.onConnect(FakeGnaniSocketServer.Conn::drop);
        speak("namaste", HINDI, 8000).done().get(5, TimeUnit.SECONDS);
        assertThat(rest.timbre).hasSize(1);
    }

    @Test
    void silentServerFallsBackAfterTheTimeout() throws Exception {
        ws.onConnect(c -> { });
        adapter(150).speak("namaste", HINDI, 8000, frames::add).done().get(5, TimeUnit.SECONDS);
        assertThat(rest.timbre).hasSize(1);
    }

    @Test
    void failureAfterAudioDoesNotReplayTheLine() {
        ws.onConnect(c -> {
            c.send(audioMessage(1, mulawBytes(800, (byte) 0x80)));
            c.send("{\"type\":\"error\",\"message\":\"boom\"}");
        });
        TextToSpeech.Synthesis s = speak("namaste", HINDI, 8000);
        assertThatThrownBy(() -> s.done().get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThat(rest.timbre).isEmpty();
    }

    @Test
    void bothPathsFailingFailsTheLine() {
        ws.onConnect(FakeGnaniSocketServer.Conn::drop);
        rest.timbreFailures.set(1);
        TextToSpeech.Synthesis s = speak("namaste", HINDI, 8000);
        assertThatThrownBy(() -> s.done().get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void blankTextFinishesAtOnceWithoutCallingAnyone() {
        TextToSpeech.Synthesis s = speak("   ", HINDI, 8000);
        assertThat(s.done()).isCompleted();
        assertThat(ws.conns).isEmpty();
    }
}
