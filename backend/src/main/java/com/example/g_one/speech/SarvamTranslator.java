package com.example.g_one.speech;

import com.example.g_one.GOneProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Sarvam's {@code POST /translate}. One retry on 429 and 5xx with a short bounded wait, because a phone turn cannot wait
 * longer; a timeout is not retried, the caller falls back instead. Spoken turns are never logged.
 */
@Component
public class SarvamTranslator {
    /** Translation failed or is not available for this pair. The caller decides what to do next. */
    public static final class Failed extends RuntimeException {
        private final int status;

        Failed(String message, int status) {
            super(message, null, false, false);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private static final String STAGE = "sarvam_translate";
    private static final String CODE_MIXED = "code-mixed";
    private static final String FORMAL = "formal";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_INPUT_CHARS = 1000;
    private static final long DEFAULT_BACKOFF_MS = 300;
    private static final long MAX_BACKOFF_MS = 1_200;
    private static final Pattern MARKUP = Pattern.compile("[*#`_]+");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Logger log = LoggerFactory.getLogger(SarvamTranslator.class);

    /** Our language code to Sarvam's. Hinglish and Benglish are the base language on the wire. */
    private static final Map<String, String> CODES = Map.of(
            "hi-in", "hi-IN", "hi-en", "hi-IN", "en-in", "en-IN", "bn-in", "bn-IN", "bn-en", "bn-IN",
            "ta-in", "ta-IN", "kn-in", "kn-IN");

    private final GOneProperties.Translate cfg;
    private final RestClient client;
    private final ObjectMapper json;

    @Autowired
    public SarvamTranslator(GOneProperties props, ObjectMapper json) {
        this.cfg = props.translate();
        this.client = cfg != null && cfg.sarvamConfigured() ? Http.client(cfg.baseUrl(), cfg.timeoutSeconds()) : null;
        this.json = json;
    }

    public boolean configured() {
        return client != null;
    }

    /** True when Sarvam can translate between these two codes: both known, and different once mapped. */
    public boolean supports(String from, String to) {
        String source = wire(from);
        String target = wire(to);
        return source != null && target != null && !source.equals(target);
    }

    /** @throws Failed when there is no usable translation */
    public String translate(String text, String from, String to) {
        if (client == null) throw new Failed("Sarvam is not configured.", 0);
        if (!supports(from, to)) throw new Failed("Sarvam has no route for this language pair.", 0);
        if (text.length() > MAX_INPUT_CHARS) throw new Failed("The turn is too long to translate.", 0);
        byte[] body = body(text, from, to);
        long started = System.nanoTime();
        Http.Result r = send(body);
        if (r.status() == 429 || r.status() >= 500) {
            long wait = backoffMs(r.headers().getFirst("retry-after"));
            log.warn("sarvam_translate status={} retry_in_ms={}", r.status(), wait);
            pause(wait);
            r = send(body);
        }
        if (!r.ok()) {
            log.warn("sarvam_translate failed status={}", r.status());
            throw new Failed("Sarvam returned HTTP " + r.status() + ".", r.status());
        }
        String out = parse(r.body(), text);
        log.info("sarvam_translate chars={} latency_ms={} model={}", text.length(), (System.nanoTime() - started) / 1_000_000, cfg.model());
        return out;
    }

    private Http.Result send(byte[] body) {
        try {
            return Http.call(STAGE, client.post().uri("/translate")
                    .header("api-subscription-key", cfg.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body), MAX_BODY_BYTES);
        } catch (UpstreamException e) {
            throw new Failed("Sarvam could not be reached.", 0);
        }
    }

    /** Package-private so tests can check the wire format. */
    byte[] body(String text, String from, String to) {
        ObjectNode body = json.createObjectNode();
        body.put("input", text);
        body.put("source_language_code", wire(from));
        body.put("target_language_code", wire(to));
        body.put("model", cfg.model());
        body.put("mode", mode(to));
        // Plain digits: the code turns them into words, so every figure is spoken the same way.
        body.put("numerals_format", "international");
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Hinglish and Benglish listeners get the code-mixed mode where the model has one; only Mayura has modes. */
    private String mode(String to) {
        if (!cfg.model().toLowerCase(Locale.ROOT).startsWith("mayura")) return FORMAL;
        return to.toLowerCase(Locale.ROOT).endsWith("-en") ? CODE_MIXED : cfg.mode();
    }

    private String parse(byte[] raw, String input) {
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (IOException e) {
            throw new Failed("Sarvam returned an unreadable answer.", 0);
        }
        String text = root == null ? "" : root.path("translated_text").asText("");
        String clean = SPACE.matcher(MARKUP.matcher(text).replaceAll(" ")).replaceAll(" ").strip();
        if (clean.isEmpty()) throw new Failed("Sarvam returned an empty translation.", 0);
        if (clean.length() > 4 * input.length() + 120) throw new Failed("Sarvam returned an implausibly long translation.", 0);
        return clean;
    }

    private static String wire(String code) {
        return code == null ? null : CODES.get(code.toLowerCase(Locale.ROOT));
    }

    private static long backoffMs(String retryAfter) {
        if (retryAfter != null) {
            try {
                return Math.min(MAX_BACKOFF_MS, Math.max(0, Math.round(Double.parseDouble(retryAfter.strip()) * 1000)));
            } catch (NumberFormatException e) {
                // a date form: use the default
            }
        }
        return DEFAULT_BACKOFF_MS;
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
