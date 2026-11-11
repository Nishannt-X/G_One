package com.example.g_one.agent.brain;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a name a caller gives is clearly not the remembered one. Only names in the same script are compared: "सुरेश"
 * against a stored "Suresh" cannot be told apart without transliteration, so that is never counted as a different name.
 */
final class NameMatch {
    private static final Set<String> TITLES = Set.of("जी", "ji", "jee", "babu", "বাবু", "sir", "shri", "श्री", "श्रीमान",
            "mr", "mrs", "ms", "bhai", "भाई");

    private NameMatch() {}

    /** True when {@code said} and {@code remembered} share a script and no name word in common. */
    static boolean differs(String said, String remembered) {
        if (said == null || remembered == null) return false;
        List<String> a = words(said);
        List<String> b = words(remembered);
        if (a.isEmpty() || b.isEmpty() || script(a.get(0)) != script(b.get(0))) return false;
        for (String w : a) {
            for (String r : b) {
                if (w.equals(r) || (w.length() >= 3 && r.startsWith(w)) || (r.length() >= 3 && w.startsWith(r))) return false;
            }
        }
        return true;
    }

    /** True when both are in the same script and share a name word. */
    static boolean same(String said, String remembered) {
        if (said == null || remembered == null) return false;
        List<String> a = words(said);
        List<String> b = words(remembered);
        return !a.isEmpty() && !b.isEmpty() && script(a.get(0)) == script(b.get(0)) && !differs(said, remembered);
    }

    private static List<String> words(String name) {
        String n = Normalizer.normalize(name.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replace("़", "");
        n = Normalizer.normalize(n, Normalizer.Form.NFC).replace('ँ', 'ं');
        return Arrays.stream(n.split("[^\\p{L}\\p{M}]+")).filter(w -> !w.isEmpty() && !TITLES.contains(w)).toList();
    }

    private static Character.UnicodeScript script(String word) {
        return Character.UnicodeScript.of(word.codePointAt(0));
    }
}
