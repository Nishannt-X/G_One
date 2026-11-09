package com.example.g_one.speech.lang;

import java.util.Locale;

/**
 * What to tell Gnani for each language we offer. Hinglish and Benglish have no Prisma language code: they are recognised
 * as the Indian language with an English hint and code-mixed output. Timbre has a dedicated Hinglish code ({@code hi-en})
 * but none for Benglish, which is spoken with {@code bn-IN}.
 *
 * @param sttHints value of the Prisma realtime {@code language_code} header
 * @param sttSingle the single code for Prisma REST and Prisma v2.5
 * @param ttsLanguage value of the Timbre {@code language} field
 * @param format value of the Prisma realtime {@code format} header; empty leaves it out (v2.5)
 */
public record LanguageRoute(String sttHints, String sttSingle, String ttsLanguage, String format) {
    private static final String CLEAN = "transcribe";
    private static final String MIXED = "codemix";

    /** @param prismaV3 whether the session uses Prisma v3.0, the only model taking several hints and a format */
    public static LanguageRoute of(String code, boolean prismaV3) {
        LanguageRoute full = switch (code.toLowerCase(Locale.ROOT)) {
            case "hi-in" -> new LanguageRoute("hi-IN,en-IN", "hi-IN", "hi-IN", CLEAN);
            case "hi-en" -> new LanguageRoute("hi-IN,en-IN", "hi-IN", "hi-en", MIXED);
            case "en-in" -> new LanguageRoute("en-IN,hi-IN", "en-IN", "en-IN", CLEAN);
            case "bn-in" -> new LanguageRoute("bn-IN,en-IN", "bn-IN", "bn-IN", CLEAN);
            case "bn-en" -> new LanguageRoute("bn-IN,en-IN", "bn-IN", "bn-IN", MIXED);
            default -> new LanguageRoute(code, code, code, CLEAN);
        };
        return prismaV3 ? full : new LanguageRoute(full.sttSingle, full.sttSingle, full.ttsLanguage, "");
    }
}
