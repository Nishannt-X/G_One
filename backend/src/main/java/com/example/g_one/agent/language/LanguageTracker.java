package com.example.g_one.agent.language;

import com.example.g_one.agent.language.LanguageDetector.Detection;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Which language a leg is in as a call goes on, from its recent turns. One turn never changes a language it did not
 * name: a borrowed word or a single English sentence is not a switch.
 *
 * <ul>
 *   <li>A short request naming a language ("Hindi please") is honoured at once.</li>
 *   <li>Otherwise {@value #SWITCH_RUN} turns in a row in another language switch to it.</li>
 *   <li>A language and English that keep alternating become the mixed code (see {@link #MIXES}), instead of flipping:
 *       {@value #MIX_MIN_EACH} turns of each in the window, a run away from a language still in the window, or one turn
 *       that is itself code-mixed.</li>
 *   <li>A mixed code is left only after {@value #MIX_EXIT_RUN} turns in a row of one language since it was entered.</li>
 * </ul>
 * Not thread safe; {@link LanguageFlow} guards it.
 */
final class LanguageTracker {
    /** Recent meaningful turns kept. */
    static final int WINDOW = 5;
    static final int SWITCH_RUN = 2;
    static final int MIX_MIN_EACH = 2;
    static final int MIX_EXIT_RUN = 4;
    private static final String ENGLISH = "en-IN";

    /** @param mixed the code spoken when {@code base} and English are mixed */
    record Mix(String base, String mixed) {}

    /**
     * One line per mix. Only these two have a route in {@code LanguageRoute}. Tamil and Kannada have no mixed code today,
     * so a Tamil or Kannada leg that mixes in English stays Tamil or Kannada; a new pair needs a route there first.
     */
    static final List<Mix> MIXES = List.of(new Mix("hi-IN", "hi-en"), new Mix("bn-IN", "bn-en"));

    /** A new language for the leg. {@code reason} is the one the console shows. */
    record Switch(String code, String reason) {}

    private final Deque<String> window = new ArrayDeque<>();
    private String current;
    /** Meaningful turns since the language last changed. */
    private int sinceSwitch;

    LanguageTracker(String current) {
        this.current = current;
    }

    String current() {
        return current;
    }

    /** The turn that chose the language counts as the first one heard in it, unless it only named it. */
    void seed(Detection detection) {
        if (detection.hasContent() && detection.source() != LanguageDetector.Source.NAMED) window.addLast(detection.code());
    }

    Optional<Switch> observe(Detection detection) {
        if (detection.unknown()) return Optional.empty();
        if (detection.source() == LanguageDetector.Source.NAMED) return change(detection.code(), "named");
        if (!detection.hasContent()) return Optional.empty();
        String spoken = detection.code();
        window.addLast(spoken);
        while (window.size() > WINDOW) window.removeFirst();
        sinceSwitch++;
        if (spoken.equals(current)) return Optional.empty();

        int run = trailingRun(spoken);
        if (isMixed(current)) {
            return Math.min(run, sinceSwitch) >= MIX_EXIT_RUN ? change(spoken, "run") : Optional.empty();
        }
        if (isMixed(spoken)) {
            boolean ownSide = mixOf(spoken).filter(m -> current.equals(m.base()) || current.equals(ENGLISH)).isPresent();
            if (ownSide && detection.source() == LanguageDetector.Source.MIXED) return change(spoken, "mix");
            return run >= SWITCH_RUN ? change(spoken, "run") : Optional.empty();
        }
        Optional<Mix> pair = pairOf(spoken, current);
        if (pair.isEmpty()) return run >= SWITCH_RUN ? change(spoken, "run") : Optional.empty();
        Mix mix = pair.get();
        if (count(mix.base()) + count(mix.mixed()) >= MIX_MIN_EACH && count(ENGLISH) >= MIX_MIN_EACH) {
            return change(mix.mixed(), "mix");
        }
        if (run < SWITCH_RUN) return Optional.empty();
        return window.contains(current) ? change(mix.mixed(), "mix") : change(spoken, "run");
    }

    private Optional<Switch> change(String code, String reason) {
        if (code.equals(current)) return Optional.empty();
        current = code;
        sinceSwitch = 0;
        return Optional.of(new Switch(code, reason));
    }

    private int trailingRun(String code) {
        int run = 0;
        for (var it = window.descendingIterator(); it.hasNext() && it.next().equals(code); ) run++;
        return run;
    }

    private int count(String code) {
        return (int) window.stream().filter(code::equals).count();
    }

    private static boolean isMixed(String code) {
        return mixOf(code).isPresent();
    }

    private static Optional<Mix> mixOf(String mixed) {
        return MIXES.stream().filter(m -> m.mixed().equals(mixed)).findFirst();
    }

    /** The mix made of these two pure languages: one of them English, the other its base. */
    private static Optional<Mix> pairOf(String a, String b) {
        return MIXES.stream()
                .filter(m -> (a.equals(m.base()) && b.equals(ENGLISH)) || (b.equals(m.base()) && a.equals(ENGLISH)))
                .findFirst();
    }
}
