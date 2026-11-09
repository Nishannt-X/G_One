package com.example.g_one.speech.gnani;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.g_one.FakeUpstreams;
import com.example.g_one.GOneProperties;
import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.Language;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.SttListener;
import com.example.g_one.core.SttSession;
import com.example.g_one.core.Turn;
import com.example.g_one.speech.PrismClient;
import com.example.g_one.speech.SpeechProperties;
import com.example.g_one.speech.UpstreamException;
import com.example.g_one.speech.audio.Pcm;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Prisma adapter against a fake realtime socket and a fake REST endpoint; nothing leaves the machine. */
class PrismaSpeechToTextTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Language HINDI = new Language("hi-IN", "Hindi", "Nalini");
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final LegId leg = LegId.random();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<Turn> turns = new CopyOnWriteArrayList<>();
    private final List<Exception> errors = new CopyOnWriteArrayList<>();
    private final SttListener listener = new SttListener() {
        @Override
        public void onSpeechStart() {
            events.add("start");
        }

        @Override
        public void onFinalTurn(Turn turn) {
            events.add("turn:" + turn.text());
            turns.add(turn);
        }

        @Override
        public void onError(Exception error) {
            errors.add(error);
        }
    };

    private FakeGnaniSocketServer ws;
    private FakeUpstreams rest;
    private SttSession session;

    @BeforeEach
    void start() throws Exception {
        ws = new FakeGnaniSocketServer();
        rest = new FakeUpstreams();
    }

    @AfterEach
    void stop() throws Exception {
        if (session != null) session.close();
        ws.close();
        rest.close();
    }

    private static SpeechProperties settings(String mode, int rotateMinutes, int backoffMs, int bargeMs) {
        return new SpeechProperties(mode, 0.7, 200, 400, 180, 30, bargeMs, 300, 8000, -42, 600, 350, 1200, rotateMinutes,
                backoffMs, 5000);
    }

    private PrismaSpeechToText adapter(String wsBase, String model, SpeechProperties settings) {
        GOneProperties.Gnani gnani = new GOneProperties.Gnani(wsBase, FakeUpstreams.KEY, model, "timbre-v2.5", 5);
        GOneProperties props = new GOneProperties(List.of(), "", null, gnani, null);
        GOneProperties forRest = new GOneProperties(List.of(), "", null,
                new GOneProperties.Gnani(rest.baseUrl(), FakeUpstreams.KEY, model, "timbre-v2.5", 5), null);
        return new PrismaSpeechToText(props, settings, new PrismClient(forRest, JSON), JSON);
    }

    private void openRealtime() {
        session = adapter(ws.baseUrl(), "", settings("auto", 14, 10, 700)).open(leg, LegRole.FARMER, HINDI, listener);
    }

    private static void connected(FakeGnaniSocketServer.Conn c) {
        c.send("{\"type\":\"connected\",\"config\":{\"sample_rate\":8000,\"chunk_size\":512}}");
    }

    private static AudioFrame voiced(int rate, int ms, long at) {
        int n = rate * ms / 1000;
        byte[] pcm = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            Pcm.put(pcm, i, Pcm.clamp(9000 * Math.sin(2 * Math.PI * 300 * i / rate)));
        }
        return new AudioFrame(pcm, rate, at);
    }

    private void feedVoiced(int rate, int totalMs) {
        for (int t = 0; t < totalMs; t += 20) session.feed(voiced(rate, 20, t));
    }

    private FakeGnaniSocketServer.Conn conn(int index) {
        await().atMost(WAIT).until(() -> ws.conns.size() > index);
        return ws.conns.get(index);
    }

    @Test
    void sendsDocumentedHeadersAndExact1024ByteFrames() {
        ws.onConnect(PrismaSpeechToTextTest::connected);
        session = adapter(ws.baseUrl(), "prisma-v3.0", settings("auto", 14, 10, 700))
                .open(leg, LegRole.FARMER, new Language("hi-en", "Hinglish", "Poorvi"), listener);
        feedVoiced(8000, 400);

        FakeGnaniSocketServer.Conn c = conn(0);
        await().atMost(WAIT).until(() -> c.binary.size() >= 6);
        assertThat(c.path).isEqualTo("/stt/v3/stream");
        assertThat(c.headers).containsEntry("x-api-key-id", FakeUpstreams.KEY).containsEntry("model", "prisma-v3.0")
                .containsEntry("language_code", "hi-IN,en-IN").containsEntry("format", "codemix")
                .containsEntry("x-sample-rate", "8000").containsEntry("x-api-request-id", leg.value());
        assertThat(c.binary).allSatisfy(frame -> assertThat(frame).hasSize(1024));
    }

    @Test
    void prismaV25UsesOneLanguageAndNoFormatOrModelHeader() {
        ws.onConnect(PrismaSpeechToTextTest::connected);
        session = adapter(ws.baseUrl(), "", settings("auto", 14, 10, 700))
                .open(leg, LegRole.FARMER, new Language("hi-en", "Hinglish", "Poorvi"), listener);
        FakeGnaniSocketServer.Conn c = conn(0);
        assertThat(c.headers).containsEntry("language_code", "hi-IN").doesNotContainKeys("format", "model");
    }

    @Test
    void browserRatesAreResampledBeforeFraming() {
        ws.onConnect(PrismaSpeechToTextTest::connected);
        openRealtime();
        feedVoiced(48000, 1000);
        FakeGnaniSocketServer.Conn c = conn(0);
        await().atMost(WAIT).until(() -> c.binary.size() >= 14);
        assertThat(c.binary).allSatisfy(frame -> assertThat(frame).hasSize(1024));
        assertThat(Pcm.rms(c.binary.get(5))).isCloseTo(9000 / 32768.0 / Math.sqrt(2), org.assertj.core.data.Offset.offset(0.02));
    }

    @Test
    void framesArePacedEvery64MsEvenFromABacklog() {
        ws.onConnect(PrismaSpeechToTextTest::connected);
        openRealtime();
        feedVoiced(8000, 700); // all at once: ten frames queued
        FakeGnaniSocketServer.Conn c = conn(0);
        await().atMost(WAIT).until(() -> c.binaryAtNanos.size() >= 10);
        long spanMs = (c.binaryAtNanos.get(9) - c.binaryAtNanos.get(0)) / 1_000_000;
        assertThat(spanMs).isGreaterThanOrEqualTo(9 * 60L);
    }

    @Test
    void silenceIsDroppedOnlyWhileBehind() {
        ws.onConnect(PrismaSpeechToTextTest::connected);
        openRealtime();
        for (int t = 0; t < 2000; t += 20) session.feed(AudioFrame.silence(8000, 20, t));
        FakeGnaniSocketServer.Conn c = conn(0);
        await().pollDelay(Duration.ofMillis(400)).atMost(WAIT).until(() -> c.binary.size() >= 1);
        assertThat(c.binary.size()).isLessThan(10);
    }

    @Test
    void mapsPrismaEventsToAFinishedTurn() throws Exception {
        ws.onConnect(PrismaSpeechToTextTest::connected);
        openRealtime();
        FakeGnaniSocketServer.Conn c = conn(0);
        c.send("{\"type\":\"speech_start\",\"utterance_id\":\"u1\"}");
        Thread.sleep(800); // past the barge-in gate
        await().atMost(WAIT).until(() -> events.contains("start"));
        c.send("{\"type\":\"processing\"}");
        c.send("{\"type\":\"transcript\",\"text\":\" मुझे गेहूं बेचना है \",\"is_final\":true,\"latency\":320}");
        await().atMost(WAIT).until(() -> turns.size() == 1);
        assertThat(events).containsExactly("start", "turn:मुझे गेहूं बेचना है");
        Turn turn = turns.get(0);
        assertThat(turn.legId()).isEqualTo(leg);
        assertThat(turn.role()).isEqualTo(LegRole.FARMER);
        assertThat(turn.language()).isEqualTo(HINDI);
        assertThat(turn.endedMs()).isGreaterThanOrEqualTo(turn.startedMs());
    }

    @Test
    void backchannelNeverAnnouncesSpeechStart() {
        ws.onConnect(PrismaSpeechToTextTest::connected);
        openRealtime();
        FakeGnaniSocketServer.Conn c = conn(0);
        c.send("{\"type\":\"speech_start\"}");
        c.send("{\"type\":\"processing\"}");
        c.send("{\"type\":\"transcript\",\"text\":\"हाँ जी\",\"is_final\":true}");
        await().atMost(WAIT).until(() -> turns.size() == 1);
        assertThat(events).containsExactly("turn:हाँ जी");
    }

    @Test
    void reconnectsAfterADropAndKeepsListening() throws Exception {
        ws.onConnect(PrismaSpeechToTextTest::connected);
        session = adapter(ws.baseUrl(), "", settings("auto", 14, 10, 700)).open(leg, LegRole.FARMER, HINDI, listener);
        FakeGnaniSocketServer.Conn first = conn(0);
        Thread.sleep(2100); // a session this old counts as stable, so the reconnect is immediate
        first.drop();
        FakeGnaniSocketServer.Conn second = conn(1);
        feedVoiced(8000, 300);
        await().atMost(WAIT).until(() -> !second.binary.isEmpty());
        second.send("{\"type\":\"transcript\",\"text\":\"phir se namaste\",\"is_final\":true}");
        await().atMost(WAIT).until(() -> turns.size() == 1);
        assertThat(turns.get(0).text()).isEqualTo("phir se namaste");
        assertThat(errors).isEmpty();
    }

    @Test
    void reconnectsWhenPrismaEndsTheSessionItself() throws Exception {
        ws.onConnect(PrismaSpeechToTextTest::connected);
        openRealtime();
        FakeGnaniSocketServer.Conn first = conn(0);
        Thread.sleep(2100);
        first.closeWith(1001);
        conn(1);
        assertThat(errors).isEmpty();
    }

    @Test
    void aDropMidUtteranceDoesNotLeaveATurnHanging() {
        ws.onConnect(PrismaSpeechToTextTest::connected);
        openRealtime();
        FakeGnaniSocketServer.Conn first = conn(0);
        first.send("{\"type\":\"speech_start\"}");
        first.send("{\"type\":\"processing\"}");
        first.send("{\"type\":\"transcript\",\"text\":\"gehun aur\",\"is_final\":true}");
        await().atMost(WAIT).until(() -> events.contains("start"));
        first.drop();
        await().atMost(WAIT).until(() -> turns.size() == 1);
        assertThat(turns.get(0).text()).isEqualTo("gehun aur");
    }

    @Test
    void rotatesTheSocketBeforeTheSessionCap() {
        ws.onConnect(PrismaSpeechToTextTest::connected);
        // A rotation period of zero minutes makes the first rotation due at once.
        session = adapter(ws.baseUrl(), "", settings("auto", 0, 10, 700)).open(leg, LegRole.FARMER, HINDI, listener);
        conn(2);
        assertThat(errors).isEmpty();
    }

    @Test
    void rejectedHandshakeFallsBackToUploadsAtOnce() {
        ws.rejections.set(1);
        rest.sttTranscript = "gehun bechna hai";
        openRealtime();
        await().atMost(WAIT).until(() -> errors.stream().anyMatch(e -> e instanceof UpstreamException u && u.stage().equals("stt_degraded")));
        speakTurn(1500);
        await().atMost(WAIT).until(() -> turns.size() == 1);
        assertThat(turns.get(0).text()).isEqualTo("gehun bechna hai");
        assertThat(events).containsExactly("start", "turn:gehun bechna hai");
        assertThat(ws.conns).isEmpty();
    }

    @Test
    void repeatedConnectFailuresFallBackToUploads() throws Exception {
        rest.sttTranscript = "aloo";
        ws.close(); // nothing listens on that port any more
        openRealtime();
        await().atMost(WAIT).until(() -> errors.stream().anyMatch(e -> e instanceof UpstreamException u && u.stage().equals("stt_degraded")));
        speakTurn(1500);
        await().atMost(WAIT).until(() -> turns.size() == 1);
        assertThat(turns.get(0).text()).isEqualTo("aloo");
    }

    @Test
    void forcedRestModeNeverOpensASocket() {
        rest.sttTranscript = "tamatar";
        session = adapter(ws.baseUrl(), "", settings("rest", 14, 10, 700)).open(leg, LegRole.BUYER, HINDI, listener);
        speakTurn(1500);
        await().atMost(WAIT).until(() -> turns.size() == 1);
        assertThat(turns.get(0).role()).isEqualTo(LegRole.BUYER);
        assertThat(ws.conns).isEmpty();
        assertThat(rest.prisma.get(0).bodyText()).contains("filename=\"turn.wav\"").contains("hi-IN");
    }

    @Test
    void restFallbackSkipsTurnsThatHoldNoWords() throws Exception {
        rest.sttTranscript = "";
        session = adapter(ws.baseUrl(), "", settings("rest", 14, 10, 50)).open(leg, LegRole.FARMER, HINDI, listener);
        speakTurn(600);
        await().atMost(WAIT).until(() -> rest.prisma.size() == 1);
        Thread.sleep(100);
        assertThat(turns).isEmpty();
        assertThat(errors).isEmpty();
    }

    @Test
    void restFailureIsReportedAndTheSessionLivesOn() {
        rest.prismaFailures.set(2); // the first try and its retry
        session = adapter(ws.baseUrl(), "", settings("rest", 14, 10, 700)).open(leg, LegRole.FARMER, HINDI, listener);
        speakTurn(600);
        await().atMost(WAIT).until(() -> errors.size() == 1);
        rest.sttTranscript = "doosra";
        speakTurn(600);
        await().atMost(WAIT).until(() -> turns.size() == 1);
        assertThat(turns.get(0).text()).isEqualTo("doosra");
    }

    @Test
    void closeStopsEventsAndEndsTheSocket() throws Exception {
        ws.onConnect(PrismaSpeechToTextTest::connected);
        openRealtime();
        FakeGnaniSocketServer.Conn c = conn(0);
        await().atMost(WAIT).pollDelay(Duration.ofMillis(50)).until(() -> true);
        c.send("{\"type\":\"speech_start\"}");
        Thread.sleep(50);
        session.close();
        session.close(); // idempotent
        c.send("{\"type\":\"transcript\",\"text\":\"late\",\"is_final\":true}");
        session.feed(voiced(8000, 20, 0));
        assertThat(c.clientClosed.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1000);
        Thread.sleep(800);
        assertThat(events).isEmpty();
    }

    @Test
    void closingFromInsideACallbackDoesNotDeadlock() {
        ws.onConnect(PrismaSpeechToTextTest::connected);
        SttListener closer = new SttListener() {
            @Override
            public void onFinalTurn(Turn turn) {
                session.close();
                events.add("closed");
            }

            @Override
            public void onError(Exception error) {}
        };
        session = adapter(ws.baseUrl(), "", settings("auto", 14, 10, 700)).open(leg, LegRole.FARMER, HINDI, closer);
        conn(0).send("{\"type\":\"transcript\",\"text\":\"bas\",\"is_final\":true}");
        await().atMost(WAIT).until(() -> events.contains("closed"));
    }

    /** Pre-speech silence, then speech with natural dips, then enough silence to end the turn. */
    private void speakTurn(int speechMs) {
        long at = 0;
        for (int t = 0; t < 200; t += 20, at += 20) session.feed(AudioFrame.silence(8000, 20, at));
        for (int t = 0; t < speechMs; t += 20, at += 20) {
            session.feed(t % 240 == 0 ? AudioFrame.silence(8000, 20, at) : voiced(8000, 20, at));
        }
        for (int t = 0; t < 800; t += 20, at += 20) session.feed(AudioFrame.silence(8000, 20, at));
    }
}
