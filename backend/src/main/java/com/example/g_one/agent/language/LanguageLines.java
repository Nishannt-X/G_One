package com.example.g_one.agent.language;

import com.example.g_one.core.LegRole;
import java.util.Map;

/**
 * The fixed lines around choosing a language. The question and its repeat are spoken before the person's language is
 * known, so they are English; the confirmations are in the language just chosen. Bengali, Benglish, Tamil and Kannada
 * lines are written without a native speaker and still need one to review them.
 */
public final class LanguageLines {
    private static final String CHOICES = "Hindi, English, Bengali, Tamil, Kannada, or Hinglish.";
    private static final String QUESTION = "Which language would you like to speak? " + CHOICES;
    private static final String ENGLISH = "en-IN";

    private static final Map<String, String> CONFIRM = Map.of(
            "hi-IN", "ठीक है, हिंदी में बात करते हैं।",
            ENGLISH, "All right, we will speak in English.",
            "hi-en", "ठीक है, हिंग्लिश में बात करते हैं।",
            "bn-IN", "ঠিক আছে, বাংলায় কথা বলি।",
            "bn-en", "ঠিক আছে, বেঙ্গলিশে কথা বলি।",
            "ta-IN", "சரி, தமிழில் பேசலாம்.",
            "kn-IN", "ಸರಿ, ಕನ್ನಡದಲ್ಲಿ ಮಾತನಾಡೋಣ.");

    private LanguageLines() {}

    public static String opener(LegRole role) {
        return role == LegRole.BUYER
                ? "Hello, this is G-One. I am connecting you to a farmer. " + QUESTION
                : "Hello, this is G-One. " + QUESTION;
    }

    /** English, then a short Hindi line for someone who did not follow the English. */
    public static String reask() {
        return "Sorry, I did not catch that. Please say the language you want: " + CHOICES + " कृपया अपनी भाषा का नाम बोलिए।";
    }

    /** True for the farmer leg's opening question and its re-ask: the lines that ask which language to speak. */
    public static boolean asksLanguage(String line) {
        if (line == null) return false;
        String l = line.strip();
        return l.equals(opener(LegRole.FARMER)) || l.equals(reask());
    }

    /** The line to speak once {@code code} is chosen; English for a language with no line of its own. */
    public static String confirmation(String code) {
        return CONFIRM.getOrDefault(code, CONFIRM.get(ENGLISH));
    }
}
