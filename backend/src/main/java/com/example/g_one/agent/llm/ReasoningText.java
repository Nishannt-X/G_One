package com.example.g_one.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes a reasoning model's thinking from its answer, so it is never shown, parsed or spoken. Groq's default
 * {@code reasoning_format: raw} puts the thinking inside the content in {@code <think>} tags, and an answer cut off by
 * the token limit can leave a block open. Handles {@code think}, {@code thinking} and {@code reasoning} tags in any case:
 * <ol>
 *   <li>complete blocks are removed;</li>
 *   <li>a closing tag with no opening one means the thinking began before the content: everything up to it goes;</li>
 *   <li>an opening tag with no closing one means the answer was cut off mid-thought: everything from it goes.</li>
 * </ol>
 * What remains may be empty; callers treat that as a bad answer.
 */
public final class ReasoningText {
    private static final Logger log = LoggerFactory.getLogger(ReasoningText.class);
    private static final String NAMES = "(?:think|thinking|reasoning)";
    private static final Pattern BLOCK = Pattern.compile("(?is)<\\s*(" + NAMES + ")\\b[^>]*>.*?<\\s*/\\s*\\1\\s*>");
    private static final Pattern CLOSE = Pattern.compile("(?i)<\\s*/\\s*" + NAMES + "\\s*>");
    private static final Pattern OPEN = Pattern.compile("(?i)<\\s*" + NAMES + "\\b[^>]*>");
    private static final Pattern ANY_TAG = Pattern.compile("(?i)<\\s*/?\\s*" + NAMES + "\\b[^>]*>");
    private static final String DEFAULT_LANGUAGE = "en-IN";
    private static final String LAST_RESORT = "Sorry, I am having a little trouble right now. Please say that once more.";
    private static final Map<String, String> APOLOGY = apologies();

    private ReasoningText() {}

    /** The answer without any thinking, trimmed. Null becomes empty. */
    public static String strip(String content) {
        if (content == null) return "";
        String s = BLOCK.matcher(content).replaceAll("");
        Matcher close = CLOSE.matcher(s);
        int end = -1;
        while (close.find()) end = close.end();
        if (end >= 0) s = s.substring(end);
        Matcher open = OPEN.matcher(s);
        if (open.find()) s = s.substring(0, open.start());
        return s.trim();
    }

    /** True when the text still holds any reasoning tag. */
    public static boolean hasTag(String text) {
        return text != null && ANY_TAG.matcher(text).find();
    }

    /**
     * The last check before text is spoken or shown on the console. Thinking is stripped; when nothing speakable is
     * left, or a tag somehow survives, the fixed apology line for {@code languageCode} is used instead.
     */
    public static String speakable(String text, String languageCode) {
        String clean = strip(text);
        if (!clean.isEmpty() && !hasTag(clean)) return clean;
        log.warn("Refused to speak text that held only model reasoning; using the fallback line");
        return apology(languageCode);
    }

    /** The apology from {@link FallbackLines#RESOURCE}, English when the language has none. */
    static String apology(String languageCode) {
        String line = languageCode == null ? null : APOLOGY.get(languageCode);
        if (line == null) line = APOLOGY.get(DEFAULT_LANGUAGE);
        return line == null ? LAST_RESORT : line;
    }

    private static Map<String, String> apologies() {
        Map<String, String> lines = new HashMap<>();
        try (InputStream in = ReasoningText.class.getResourceAsStream(FallbackLines.RESOURCE)) {
            if (in != null) {
                new ObjectMapper().readTree(in).path("apology").properties()
                        .forEach(e -> lines.put(e.getKey(), e.getValue().asText("")));
            }
        } catch (IOException e) {
            log.warn("Cannot read {}; the English fallback line is used", FallbackLines.RESOURCE);
        }
        lines.values().removeIf(String::isBlank);
        return Map.copyOf(lines);
    }
}
