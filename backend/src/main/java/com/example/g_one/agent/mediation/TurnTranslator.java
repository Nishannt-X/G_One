package com.example.g_one.agent.mediation;

import com.example.g_one.GOneProperties;
import com.example.g_one.agent.llm.ChatClient;
import com.example.g_one.agent.llm.ChatException;
import com.example.g_one.agent.llm.ChatMessage;
import com.example.g_one.agent.llm.ChatRequest;
import com.example.g_one.agent.llm.PromptTemplate;
import com.example.g_one.core.Language;
import com.example.g_one.speech.SarvamTranslator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Translates one line and refuses to pass on wrong numbers. Sarvam goes first when configured. A failure, or a translation
 * whose numbers differ from the source, gets one try on the language model; if the numbers still differ the line is
 * withheld, because a wrong price is worse than no answer.
 */
@Component
public class TurnTranslator {
    public enum Status { OK, WITHHELD, FAILED }

    /** @param text the translation, null unless {@code status} is OK */
    public record Result(String text, Status status, String via) {
        static Result failed() {
            return new Result(null, Status.FAILED, "none");
        }

        boolean ok() {
            return status == Status.OK;
        }
    }

    private static final int LLM_MAX_TOKENS = 400;
    private static final double TEMPERATURE = 0.1;
    private static final Logger log = LoggerFactory.getLogger(TurnTranslator.class);

    private final ChatClient chat;
    private final ObjectMapper json;
    private final SarvamTranslator sarvam;
    private final boolean useSarvam;
    private final String system = PromptTemplate.load("/prompts/mediation-translate.md").render(Map.of());

    @Autowired
    public TurnTranslator(ChatClient chat, ObjectMapper json, SarvamTranslator sarvam, GOneProperties props) {
        this.chat = chat;
        this.json = json;
        this.sarvam = sarvam;
        // The offline demo must stay offline even with a key in the environment.
        this.useSarvam = !chat.isScripted() && sarvam != null && sarvam.configured()
                && props.translate() != null && props.translate().usesSarvam();
    }

    private TurnTranslator(ChatClient chat, ObjectMapper json) {
        this.chat = chat;
        this.json = json;
        this.sarvam = null;
        this.useSarvam = false;
    }

    static TurnTranslator llmOnly(ChatClient chat, ObjectMapper json) {
        return new TurnTranslator(chat, json);
    }

    public boolean usesSarvam() {
        return useSarvam;
    }

    /** Never throws. */
    public Result translate(String text, Language from, Language to) {
        if (from.equals(to)) return new Result(text, Status.OK, "same");
        String first = useSarvam ? sarvam(NumberGuard.digitized(text, from.code()), from, to) : null;
        if (first != null && checked(text, from, first, to, "sarvam")) return new Result(first, Status.OK, "sarvam");
        return secondOpinion(text, from, to, first != null);
    }

    /** Checks a translation the language model already produced with the rest of the turn. */
    public Result verify(String translation, String text, Language from, Language to) {
        if (checked(text, from, translation, to, "llm")) return new Result(translation, Status.OK, "llm");
        return secondOpinion(text, from, to, true);
    }

    private Result secondOpinion(String text, Language from, Language to, boolean hadMismatch) {
        String retry = llm(NumberGuard.digitized(text, from.code()), from, to);
        if (retry != null && checked(text, from, retry, to, "llm_retry")) return new Result(retry, Status.OK, "llm");
        return hadMismatch || retry != null ? new Result(null, Status.WITHHELD, "guard") : Result.failed();
    }

    private boolean checked(String text, Language from, String translation, Language to, String via) {
        NumberGuard.Check c = NumberGuard.check(text, from.code(), translation, to.code());
        if (!c.ok()) {
            log.warn("translation_guard mismatch via={} from={} to={} missing={} invented={}", via, from.code(), to.code(),
                    c.missing().size(), c.invented().size());
        }
        return c.ok();
    }

    private String sarvam(String text, Language from, Language to) {
        if (!sarvam.supports(from.code(), to.code())) return null;
        try {
            return sarvam.translate(text, from.code(), to.code());
        } catch (RuntimeException e) {
            log.warn("Sarvam translation failed ({}); using the language model", e.getClass().getSimpleName());
            return null;
        }
    }

    private String llm(String text, Language from, Language to) {
        if (!chat.configured()) return null;
        try {
            ObjectNode input = json.createObjectNode();
            input.put("fromLanguage", from.label());
            input.put("toLanguage", to.label());
            input.put("turn", text.length() <= TurnReader.MAX_TURN_CHARS ? text : text.substring(0, TurnReader.MAX_TURN_CHARS));
            String answer = chat.complete(new ChatRequest(List.of(ChatMessage.system(system), ChatMessage.user(input.toString())),
                    List.of(), TEMPERATURE, LLM_MAX_TOKENS).numeric()).content();
            int start = answer.indexOf('{');
            int end = answer.lastIndexOf('}');
            if (start < 0 || end <= start) return null;
            JsonNode root = json.readTree(answer.substring(start, end + 1));
            String t = root.path("translation").asText("").strip();
            return t.isEmpty() ? null : t;
        } catch (ChatException e) {
            log.warn("Translation model failed: {} {}", e.kind(), e.status());
            return null;
        } catch (RuntimeException | IOException e) {
            log.warn("Translation answer was unusable ({})", e.getClass().getSimpleName());
            return null;
        }
    }
}
