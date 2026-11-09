package com.example.g_one.speech;

import com.example.g_one.GOneProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.g_one.speech.audio.PcmSplitter;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.stereotype.Component;

/** Gnani Prisma speech-to-text, REST: POST /stt/v3, multipart audio_file (https://docs.gnani.ai/api/STT/speech-to-text). */
@Component
public class PrismClient {
    private static final Logger log = LoggerFactory.getLogger(PrismClient.class);
    /** Uploads at or below this size have never hit the TLS failure seen on larger ones. */
    static final int SAFE_UPLOAD_BYTES = 44 * 1024;

    private final GOneProperties.Gnani cfg;
    private final RestClient client;
    private final ObjectMapper json;

    public PrismClient(GOneProperties props, ObjectMapper json) {
        this.cfg = props.gnani();
        this.client = Http.client(cfg.baseUrl(), cfg.timeoutSeconds());
        this.json = json;
    }

    public boolean configured() {
        return cfg.configured();
    }

    /** @return the transcript; throws UpstreamException("no_speech") when Prisma hears nothing */
    public String transcribe(byte[] wav, String languageCode) {
        return upload(wav, languageCode, "");
    }

    /**
     * Transcribes one turn of mono PCM16. A short clip that fails gets one more try, because very short turns were seen
     * to fail once and then work. A clip over about 48 KB that fails is cut at its quietest moments into pieces below
     * that size, because larger Java uploads were seen to fail with {@code SSLException: bad_record_mac}.
     *
     * @param format the Prisma {@code format} field, or empty to leave it out
     * @return the transcript, empty when Prisma heard nothing
     */
    public String transcribePcm(byte[] pcm16le, int sampleRate, String languageCode, String format) {
        byte[] wav = Wav.encode(pcm16le, sampleRate);
        try {
            return orEmpty(() -> upload(wav, languageCode, format));
        } catch (UpstreamException e) {
            if (!e.retryable()) throw e;
            log.debug("Prisma upload of {} bytes failed; trying again", wav.length);
            if (wav.length <= SAFE_UPLOAD_BYTES) {
                return orEmpty(() -> upload(wav, languageCode, format));
            }
        }
        StringBuilder joined = new StringBuilder();
        for (byte[] piece : PcmSplitter.split(pcm16le, sampleRate, SAFE_UPLOAD_BYTES - 44)) {
            String text = orEmpty(() -> upload(Wav.encode(piece, sampleRate), languageCode, format));
            if (!text.isEmpty()) {
                if (joined.length() > 0) joined.append(' ');
                joined.append(text);
            }
        }
        return joined.toString();
    }

    private static String orEmpty(java.util.function.Supplier<String> call) {
        try {
            return call.get();
        } catch (UpstreamException e) {
            if ("no_speech".equals(e.stage())) return "";
            throw e;
        }
    }

    private String upload(byte[] wav, String languageCode, String format) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("audio_file", new ByteArrayResource(wav) {
            @Override
            public String getFilename() {
                return "turn.wav";
            }
        });
        form.add("language_code", languageCode);
        if (!cfg.sttModel().isBlank()) form.add("model", cfg.sttModel());
        if (!format.isBlank()) form.add("format", format);

        Http.Result r = Http.call("stt_failed", client.post().uri("/stt/v3")
                .header("X-API-Key-ID", cfg.apiKey())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form));
        JsonNode body = parse(r.body());
        if (!r.ok() || !body.path("success").asBoolean(true)) {
            throw new UpstreamException("stt_failed", describe(r.status(), body), r.status());
        }
        String transcript = body.path("transcript").asText("").trim();
        if (transcript.isEmpty()) throw new UpstreamException("no_speech", "No speech was detected.");
        return transcript;
    }

    private JsonNode parse(byte[] bytes) {
        try {
            return json.readTree(bytes);
        } catch (IOException e) {
            return json.createObjectNode();
        }
    }

    /** Only documented error codes/types are surfaced, never raw bodies. */
    static String describe(int status, JsonNode body) {
        String type = body.path("error").path("type").asText(body.path("detail").path("error_code").asText(""));
        return "Speech service returned HTTP " + status + (type.isEmpty() ? "" : " (" + type + ")") + ".";
    }
}
