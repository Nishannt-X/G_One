package com.example.g_one.agent.language;

import com.example.g_one.agent.language.LanguageDetector.Detection;
import com.example.g_one.core.Language;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * One leg's language, from the English question to the end of the call: asked, then chosen from the first useful
 * answer, then followed by {@link LanguageTracker}. After two answers that say nothing it settles on
 * {@value #DEFAULT_CODE}, the widest of the offered languages. Pure: the caller speaks and switches. Thread safe.
 */
public final class LanguageFlow {
    /** Hindi with English words: most callers understand it, whichever of the two they speak. */
    static final String DEFAULT_CODE = "hi-en";

    enum State { PENDING, ASKED, REASKED, DECIDED }

    /**
     * What a turn meant: {@code CHOSEN} sets the language for the first time and {@code SWITCHED} changes it mid-call;
     * {@code REASK} asks again, {@code IGNORED} drops the turn, and {@code KEEP} means carry on as usual.
     */
    public enum Kind { KEEP, IGNORED, REASK, CHOSEN, SWITCHED }

    /**
     * @param language the new language; null unless {@code kind} is CHOSEN or SWITCHED
     * @param hasContent the turn that chose the language also said something to answer
     * @param reason why, as the console shows it; null unless the language changed
     */
    public record Outcome(Kind kind, Language language, boolean hasContent, String reason) {
        private static final Outcome KEEP = new Outcome(Kind.KEEP, null, false, null);
        private static final Outcome IGNORED = new Outcome(Kind.IGNORED, null, false, null);
        private static final Outcome REASK = new Outcome(Kind.REASK, null, false, null);
    }

    private final Function<String, Optional<Language>> languages;
    private State state = State.PENDING;
    private LanguageTracker tracker;

    /** @param languages the offered language with a code, if any */
    public LanguageFlow(Function<String, Optional<Language>> languages) {
        this.languages = Objects.requireNonNull(languages, "languages");
    }

    /** The question has been put to the person. */
    public synchronized void asked() {
        if (state == State.PENDING) state = State.ASKED;
    }

    synchronized State state() {
        return state;
    }

    /** @param speaking G-One is still talking: an answer that says nothing then is not held against the person */
    public synchronized Outcome onTurn(String text, boolean speaking) {
        Detection detection = LanguageDetector.detect(text);
        return state == State.DECIDED ? tracked(detection) : answering(detection, speaking);
    }

    private Outcome answering(Detection detection, boolean speaking) {
        Optional<Language> language = detection.unknown() ? Optional.empty() : languages.apply(detection.code());
        if (language.isPresent()) {
            tracker = new LanguageTracker(detection.code());
            tracker.seed(detection);
            state = State.DECIDED;
            String reason = detection.source() == LanguageDetector.Source.NAMED ? "named" : "detected";
            return new Outcome(Kind.CHOSEN, language.get(), detection.hasContent(), reason);
        }
        if (speaking || state == State.PENDING) return Outcome.IGNORED;
        if (state == State.ASKED) {
            state = State.REASKED;
            return Outcome.REASK;
        }
        state = State.DECIDED;
        Language fallback = languages.apply(DEFAULT_CODE).or(() -> languages.apply("en-IN")).orElse(null);
        if (fallback == null) return Outcome.KEEP;
        tracker = new LanguageTracker(fallback.code());
        return new Outcome(Kind.CHOSEN, fallback, false, "default");
    }

    private Outcome tracked(Detection detection) {
        if (tracker == null) return Outcome.KEEP;
        return tracker.observe(detection)
                .flatMap(change -> languages.apply(change.code()).map(l -> new Outcome(Kind.SWITCHED, l, false, change.reason())))
                .orElse(Outcome.KEEP);
    }
}
