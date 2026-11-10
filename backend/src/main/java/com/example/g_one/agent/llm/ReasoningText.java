package com.example.g_one.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
 *   <li>blocks are removed with their nesting counted, so an inner close never ends an outer block;</li>
 *   <li>a closing tag with no opening one means the thinking began before the content: everything up to it goes;</li>
 *   <li>an opening tag left open (the answer was cut off mid-thought) removes everything from it to the end;</li>
 *   <li>a tag cut off before its {@code >}, such as {@code <think} or {@code </thi} at the very end, is treated the
 *       same way. A lone {@code <} and the word "think" in ordinary text are left alone.</li>
 * </ol>
 * What remains may be empty; callers treat that as a bad answer.
 */
public final class ReasoningText {
    private static final Logger log = LoggerFactory.getLogger(ReasoningText.class);
    private static final List<String> NAMES = List.of("think", "thinking", "reasoning");
    /** Where a reasoning tag starts; its {@code >} is looked for separately, so a tag cut off before it still counts. */
    private static final Pattern TAG_START = Pattern.compile("(?i)<\\s*(/?)\\s*(" + String.join("|", NAMES) + ")\\b");
    /** A word after {@code <} or {@code </} at the very end, possibly the start of a cut-off tag name. */
    private static final Pattern TRAILING = Pattern.compile("(?i)<\\s*/?\\s*([a-z]+)$");
    private static final String DEFAULT_LANGUAGE = "en-IN";
    private static final String LAST_RESORT = "Sorry, I am having a little trouble right now. Please say that once more.";
    private static final Map<String, String> APOLOGY = apologies();

    private ReasoningText() {}

    /** The answer without any thinking, trimmed. Null becomes empty. */
    public static String strip(String content) {
        if (content == null) return "";
        StringBuilder out = new StringBuilder();
        Matcher tag = TAG_START.matcher(content);
        Deque<String> open = new ArrayDeque<>();
        int pos = 0;
        boolean cut = false;
        while (tag.find(pos)) {
            int gt = content.indexOf('>', tag.end());
            int lt = content.indexOf('<', tag.end());
            if (open.isEmpty()) out.append(content, pos, tag.start());
            if (gt < 0 || (lt >= 0 && lt < gt)) {
                if (!tag.group(1).isEmpty() && open.isEmpty()) out.setLength(0);
                cut = true; // a tag with no end: whatever follows cannot be trusted
                break;
            }
            if (tag.group(1).isEmpty()) {
                open.push(tag.group(2).toLowerCase(Locale.ROOT));
            } else if (!open.isEmpty()) {
                if (open.peek().equals(tag.group(2).toLowerCase(Locale.ROOT))) open.pop();
            } else {
                out.setLength(0); // a lone close: everything before it was thinking
            }
            pos = gt + 1;
        }
        if (!cut && open.isEmpty()) out.append(content, pos, content.length());
        return withoutCutOffTag(out.toString().strip());
    }

    /** Removes a trailing {@code <thi} or {@code </reas}: the start of a tag name cut off by the token limit. */
    private static String withoutCutOffTag(String s) {
        Matcher m = TRAILING.matcher(s);
        if (m.find() && isNamePrefix(m.group(1))) {
            return s.substring(m.start(), m.end()).contains("/") ? "" : s.substring(0, m.start()).strip();
        }
        return s;
    }

    private static boolean isNamePrefix(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        return NAMES.stream().anyMatch(n -> n.startsWith(w));
    }

    /** True when the text still holds any reasoning tag, including one cut off before its {@code >}. */
    public static boolean hasTag(String text) {
        if (text == null) return false;
        if (TAG_START.matcher(text).find()) return true;
        Matcher m = TRAILING.matcher(text.strip());
        return m.find() && isNamePrefix(m.group(1));
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
