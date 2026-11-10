package com.example.g_one.agent.brain;

import com.example.g_one.agent.llm.FixedLines;
import com.example.g_one.agent.tools.memory.MemoryBlock;
import com.example.g_one.core.CallGist;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.ProfileFact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What a returning farmer hears right after saying yes to the name check, with no model: the name plus the newest open
 * follow-up or the last crop, in fixed wording from the {@code welcomeBack} section of {@value FixedLines#RESOURCE}.
 * For example "नमस्ते Suresh जी, फिर से स्वागत है। पिछली बार आपने लाल प्याज़ के ख़रीदार की बात की थी। ...".
 *
 * <p>The crop comes from the newest open follow-up that names one, else the newest gist that names one, else the
 * newest crop fact. Stored text is English (the summarizer writes English), so Hindi lines use the Hindi word for
 * common crops and the stored word otherwise. Nothing else stored is spoken: follow-up text is only classified.
 */
final class WelcomeBack {
    static final String CROP = "{crop}";
    /** English crop words and their Hindi names; the longer name wins when both match ("red onion" over "onion"). */
    private static final Map<String, String> HINDI_CROPS = hindiCrops();
    private static final Pattern PICKUP = Pattern.compile("(?i)\\b(pick ?up|payment|paid|pay)\\b");
    private static final Pattern BUYER = Pattern.compile("(?i)\\b(buyers?|sell|sale)\\b");

    enum Kind { BUYER, PICKUP, CROP, PLAIN }

    /** A line to speak and the resource key ({@code hi-IN}, {@code en-IN}) it was written for. */
    record Spoken(String code, String text) {}

    private record Lines(Map<Kind, String> byKind, String cropUnknown) {}

    private final Map<String, Lines> byCode;

    WelcomeBack(ObjectMapper json) {
        try (InputStream in = WelcomeBack.class.getResourceAsStream(FixedLines.RESOURCE)) {
            if (in == null) throw new IllegalStateException("Fixed lines not found: " + FixedLines.RESOURCE);
            Map<String, Lines> lines = new LinkedHashMap<>();
            json.readTree(in).path("welcomeBack").fields().forEachRemaining(e -> lines.put(e.getKey(), read(e.getKey(), e.getValue())));
            this.byCode = Map.copyOf(lines);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read fixed lines: " + FixedLines.RESOURCE, e);
        }
    }

    private static Lines read(String code, JsonNode n) {
        Map<Kind, String> byKind = new LinkedHashMap<>();
        for (Kind k : Kind.values()) {
            String v = n.path(k.name().toLowerCase(Locale.ROOT)).asText("");
            if (v.isBlank() || !v.contains(FixedLines.NAME)) {
                throw new IllegalStateException("welcomeBack." + k.name().toLowerCase(Locale.ROOT) + " for " + code
                        + " must be set and contain " + FixedLines.NAME);
            }
            byKind.put(k, v);
        }
        String unknown = n.path("cropUnknown").asText("");
        if (unknown.isBlank()) throw new IllegalStateException("welcomeBack.cropUnknown is missing for " + code);
        return new Lines(Map.copyOf(byKind), unknown);
    }

    /**
     * The welcome-back line for {@code code} ("hi" and "hi-en" use the Hindi lines), or empty when there are no lines in
     * that language or no usable remembered name; the caller then falls back to its plain greeting.
     */
    Optional<Spoken> line(String code, FarmerMemory memory) {
        Optional<String> name = MemoryBlock.rememberedName(memory);
        String key = FixedLines.bestKey(byCode.keySet(), code, null);
        if (name.isEmpty() || key == null) return Optional.empty();
        Lines l = byCode.get(key);
        boolean hindi = FixedLines.prefix(key).equals("hi");
        Optional<OpenItem> open = newestOpen(memory);
        Kind kind = open.map(o -> kindOf(o.text())).orElse(Kind.PLAIN);
        Optional<String> crop = open.isPresent()
                ? cropIn(open.get().text(), memory).or(() -> cropIn(open.get().gist().summary(), memory))
                : lastCrop(memory);
        if (kind == Kind.PLAIN || kind == Kind.CROP) kind = crop.isPresent() ? Kind.CROP : Kind.PLAIN;
        String cropWord = crop.map(c -> hindi ? HINDI_CROPS.getOrDefault(c, c) : c).orElse(l.cropUnknown());
        return Optional.of(new Spoken(key, l.byKind().get(kind).replace(FixedLines.NAME, name.get()).replace(CROP, cropWord)));
    }

    /** The newest open follow-up of the farmer's own gists (gists are newest first). */
    private record OpenItem(String text, CallGist gist) {}

    private static Optional<OpenItem> newestOpen(FarmerMemory memory) {
        return MemoryBlock.ownGists(memory).stream()
                .flatMap(g -> g.followUps().stream().filter(f -> !f.isBlank()).map(f -> new OpenItem(f, g)))
                .findFirst();
    }

    static Kind kindOf(String followUp) {
        if (PICKUP.matcher(followUp).find()) return Kind.PICKUP;
        if (BUYER.matcher(followUp).find()) return Kind.BUYER;
        return Kind.CROP;
    }

    /** The crop of the newest gist that names one, else the newest crop fact. */
    private static Optional<String> lastCrop(FarmerMemory memory) {
        for (CallGist g : MemoryBlock.ownGists(memory)) {
            Optional<String> c = cropIn(g.summary(), memory);
            if (c.isPresent()) return c;
        }
        return crops(memory).stream().findFirst();
    }

    /** The longest known crop word in {@code text}: the farmer's own crops, then the common crops. */
    static Optional<String> cropIn(String text, FarmerMemory memory) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> known = new ArrayList<>(crops(memory));
        known.addAll(HINDI_CROPS.keySet());
        return known.stream()
                .filter(c -> Pattern.compile("\\b" + Pattern.quote(c) + "\\b").matcher(lower).find())
                .max(Comparator.comparingInt(String::length));
    }

    /**
     * The farmer's crops, newest fact first, lower case: {@code crop:<name>} fields and a {@code crops} list
     * ("red onion, tomato") saved by the profile tool.
     */
    private static List<String> crops(FarmerMemory memory) {
        List<ProfileFact> facts = new ArrayList<>(memory.facts());
        facts.sort(Comparator.comparingLong((ProfileFact f) -> f.provenance().atMs()).reversed());
        List<String> out = new ArrayList<>();
        for (ProfileFact f : facts) {
            if (f.field().startsWith("crop:") && f.field().length() > 5) {
                out.add(f.field().substring(5).strip().toLowerCase(Locale.ROOT));
            } else if (f.field().equals("crops") || f.field().equals("crop")) {
                for (String c : f.value().split("[,،/]|\\band\\b")) {
                    if (!c.isBlank()) out.add(c.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out.stream().filter(c -> c.codePoints().anyMatch(Character::isLetter) && c.length() <= 40).distinct().toList();
    }

    private static Map<String, String> hindiCrops() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("red onion", "लाल प्याज़");
        m.put("onion", "प्याज़");
        m.put("tomato", "टमाटर");
        m.put("potato", "आलू");
        m.put("wheat", "गेहूँ");
        m.put("paddy", "धान");
        m.put("rice", "धान");
        m.put("soybean", "सोयाबीन");
        m.put("cotton", "कपास");
        m.put("garlic", "लहसुन");
        m.put("sugarcane", "गन्ना");
        m.put("green chilli", "हरी मिर्च");
        m.put("chilli", "मिर्च");
        m.put("grapes", "अंगूर");
        m.put("maize", "मक्का");
        m.put("mustard", "सरसों");
        m.put("cauliflower", "फूलगोभी");
        m.put("cabbage", "पत्तागोभी");
        m.put("banana", "केला");
        m.put("pomegranate", "अनार");
        return Map.copyOf(m);
    }
}
