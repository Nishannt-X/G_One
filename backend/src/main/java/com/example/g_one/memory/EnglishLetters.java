package com.example.g_one.memory;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Writes a name in English letters: a small, deterministic transliteration of the Indian scripts (Devanagari, Bengali,
 * Gurmukhi, Gujarati, Odia, Tamil, Telugu, Kannada, Malayalam) into plain Latin, so "रमेश पाटिल" is stored as
 * "Ramesh Patil". The summarizer is asked for English letters; this is the safety net for when it, or the profile tool,
 * hands over native script.
 *
 * <p>The Unicode blocks of these scripts share one layout (offset 0x15 is "ka" in each), so one table indexed by the
 * offset inside the block serves all of them, with a few per-script differences. Hindi, Bengali, Punjabi and Gujarati
 * drop the silent "a" at the end of a word ("रमेश" is Ramesh, not Ramesha) and between syllables ("कमला" is Kamla);
 * the southern scripts write that vowel only when it is said. It is a readable spelling, not a scholarly one.
 *
 * <p>The web console mirrors this table in {@code web/src/lib/farmersScript.ts}; keep the two in step.
 */
public final class EnglishLetters {
    private static final int FIRST_BLOCK = 0x0900;
    private static final int LAST_BLOCK = 0x0D7F;
    private static final int DEVANAGARI = 0x0900;
    private static final int BENGALI = 0x0980;
    private static final int GURMUKHI = 0x0A00;
    private static final int GUJARATI = 0x0A80;
    private static final int TAMIL = 0x0B80;

    /** Independent vowels and vowel signs, by offset in the block. */
    private static final String[] VOWEL = new String[0x80];
    /** Consonants, by offset in the block. */
    private static final String[] CONSONANT = new String[0x80];

    static {
        String[] vowels = {"a", "a", "a", "i", "i", "u", "u", "ri", "li", "e", "e", "e", "ai", "o", "o", "o", "au"};
        for (int i = 0; i < vowels.length; i++) VOWEL[0x04 + i] = vowels[i];
        String[] signs = {"a", "i", "i", "u", "u", "ri", "ri", "e", "e", "e", "ai", "o", "o", "o", "au"};
        for (int i = 0; i < signs.length; i++) VOWEL[0x3E + i] = signs[i];
        VOWEL[0x60] = "ri";
        VOWEL[0x61] = "li";
        VOWEL[0x62] = "li";
        VOWEL[0x63] = "li";
        String[] consonants = {"k", "kh", "g", "gh", "ng", "ch", "chh", "j", "jh", "n", "t", "th", "d", "dh", "n", "t",
            "th", "d", "dh", "n", "n", "p", "ph", "b", "bh", "m", "y", "r", "r", "l", "l", "zh", "v", "sh", "sh", "s", "h"};
        for (int i = 0; i < consonants.length; i++) CONSONANT[0x15 + i] = consonants[i];
        String[] nukta = {"q", "kh", "g", "z", "r", "rh", "f", "y"};
        for (int i = 0; i < nukta.length; i++) CONSONANT[0x58 + i] = nukta[i];
    }

    private static final int ANUSVARA = 0x02;
    private static final int CANDRABINDU = 0x01;
    private static final int VISARGA = 0x03;
    private static final int VIRAMA = 0x4D;
    private static final int GURMUKHI_TIPPI = 0x70;

    private EnglishLetters() {}

    /** True when {@code text} has a letter outside the Latin script. */
    public static boolean hasNonLatinLetters(String text) {
        return text != null && text.codePoints().anyMatch(c -> Character.isLetter(c)
                && Character.UnicodeScript.of(c) != Character.UnicodeScript.LATIN);
    }

    /**
     * The name in English letters, title case where it was transliterated: "रमेश पाटिल" becomes "Ramesh Patil", and
     * "Asha" stays "Asha". Accents are dropped ("Ramēsh" is "Ramesh"); digits, symbols and letters of scripts this class
     * does not know (Arabic, Chinese, ...) are removed.
     *
     * @return empty when no English letter is left
     */
    public static Optional<String> name(String text) {
        if (text == null) return Optional.empty();
        String latin = toLatin(text);
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < latin.length(); i++) {
            char c = latin.charAt(i);
            boolean ascii = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            kept.append(ascii || c == '.' || c == '\'' || c == '-' ? c : ' ');
        }
        String flat = kept.toString().replaceAll("\\s+", " ").strip().replaceAll("^[.'-]+|[.'-]+$", "").strip();
        return flat.chars().anyMatch(Character::isLetter) ? Optional.of(flat) : Optional.empty();
    }

    /**
     * Every Indian-script word in {@code text} written in Latin letters, title case; Latin text is kept (accents
     * dropped), and anything else passes through unchanged.
     */
    public static String toLatin(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int c = text.codePointAt(i);
            if (block(c) >= 0) {
                int end = i;
                int b = block(c);
                while (end < text.length() && (block(text.codePointAt(end)) == b || isJoiner(text.codePointAt(end)))) {
                    end += Character.charCount(text.codePointAt(end));
                }
                out.append(capitalise(word(text.substring(i, end), b)));
                i = end;
            } else {
                out.appendCodePoint(c);
                i += Character.charCount(c);
            }
        }
        return Normalizer.normalize(out, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    /** The start of the Indian script block {@code c} is in, or -1. */
    private static int block(int c) {
        if (c < FIRST_BLOCK || c > LAST_BLOCK) return -1;
        return c & ~0x7F;
    }

    private static boolean isJoiner(int c) {
        return c == 0x200C || c == 0x200D;
    }

    /** One syllable: consonants joined by virama, then a vowel (or the inherent "a", or none), then a nasal or "h". */
    private static final class Syllable {
        final StringBuilder consonants = new StringBuilder();
        int consonantCount;
        String vowel = "";
        boolean inherent;
        boolean deleted;
        String coda = "";

        boolean hasVowel() {
            return !vowel.isEmpty() && !deleted;
        }

        String text() {
            return consonants + (deleted ? "" : vowel) + coda;
        }
    }

    private static String word(String run, int block) {
        List<Syllable> syllables = new ArrayList<>();
        Syllable cur = null;
        boolean joining = false; // the last sign was a virama: the next consonant joins the cluster
        int[] points = run.codePoints().filter(c -> !isJoiner(c)).toArray();
        for (int k = 0; k < points.length; k++) {
            int o = points[k] - block;
            String consonant = consonant(o, block, k == 0);
            if (consonant != null) {
                boolean cluster = joining && cur != null;
                if (!cluster) {
                    cur = new Syllable();
                    syllables.add(cur);
                }
                // In Hindi, "v" after another consonant reads as "w": Rameshwar, Swami, Dwarka (Tamil keeps Selvi).
                boolean w = cluster && consonant.equals("v") && dropsSilentA(block);
                cur.consonants.append(w ? "w" : consonant);
                cur.consonantCount++;
                boolean khandaTa = block == BENGALI && o == 0x4E; // a "t" that never carries a vowel
                cur.vowel = khandaTa ? "" : "a";
                cur.inherent = !khandaTa;
                joining = false;
            } else if (o == VIRAMA) {
                if (cur != null && cur.inherent) {
                    cur.vowel = "";
                    cur.inherent = false;
                    joining = true;
                }
            } else if (isVowelSign(o)) {
                if (cur != null && cur.inherent) {
                    cur.vowel = VOWEL[o];
                    cur.inherent = false;
                }
                joining = false;
            } else if (o >= 0 && VOWEL[o] != null) {
                cur = new Syllable();
                cur.vowel = VOWEL[o];
                syllables.add(cur);
                joining = false;
            } else if (o == ANUSVARA || o == CANDRABINDU || o == VISARGA || (block == GURMUKHI && o == GURMUKHI_TIPPI)) {
                if (cur != null) cur.coda += o == VISARGA ? "h" : nasal(points, k, block);
                joining = false;
            } else if (o >= 0x66 && o <= 0x6F) {
                cur = new Syllable();
                cur.coda = String.valueOf((char) ('0' + o - 0x66));
                syllables.add(cur);
                joining = false;
            }
            // Accents, nukta, avagraha, danda and the rest carry no sound of their own here.
        }
        if (dropsSilentA(block)) dropSilentA(syllables);
        StringBuilder out = new StringBuilder();
        syllables.forEach(s -> out.append(s.text()));
        return out.toString();
    }

    private static boolean isVowelSign(int o) {
        return (o >= 0x3E && o <= 0x4C) || o == 0x62 || o == 0x63;
    }

    private static String consonant(int o, int block, boolean wordStart) {
        if (o < 0 || o >= 0x80) return null;
        if (block == BENGALI && o == 0x4E) return "t"; // khanda ta
        if (block == BENGALI && o == 0x2C) return "b"; // Bengali has no "va"
        if (block == BENGALI && o == 0x2F) return wordStart ? "j" : "y";
        if (block == TAMIL && o == 0x1A) return wordStart ? "s" : "ch";
        if (block == TAMIL && o == 0x1F) return "t";
        if (block == TAMIL && o == 0x31) return "r";
        return CONSONANT[o];
    }

    /** A nasal sign as heard before the next letter: "m" before pa to ma (Champa), "ng" before ha (Singh), else "n". */
    private static String nasal(int[] points, int k, int block) {
        if (k + 1 >= points.length) return "n";
        int o = points[k + 1] - block;
        if (o >= 0x2A && o <= 0x2E) return "m";
        return o == 0x39 ? "ng" : "n";
    }

    private static boolean dropsSilentA(int block) {
        return block == DEVANAGARI || block == BENGALI || block == GURMUKHI || block == GUJARATI;
    }

    /**
     * The silent "a": the last one in a word of two or more syllables (unless it follows a consonant cluster, as in
     * Rajendra), and a middle one between two sounded vowels (Kamla, Rajkumar), deciding from the right.
     */
    private static void dropSilentA(List<Syllable> s) {
        int n = s.size();
        if (n < 2) return;
        Syllable last = s.get(n - 1);
        if (last.inherent && last.vowel.equals("a") && last.consonantCount == 1 && last.coda.isEmpty()) {
            last.deleted = true;
        }
        for (int k = n - 2; k >= 1; k--) {
            Syllable here = s.get(k);
            Syllable next = s.get(k + 1);
            Syllable before = s.get(k - 1);
            if (here.inherent && here.vowel.equals("a") && here.consonantCount == 1 && here.coda.isEmpty()
                    && next.consonantCount > 0 && next.hasVowel() && before.hasVowel()) {
                here.deleted = true;
            }
        }
    }

    private static String capitalise(String word) {
        if (word.isEmpty()) return word;
        return word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
    }
}
