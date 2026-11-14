package com.example.g_one.memory;

import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.speech.LlmClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@link CallSummarizer} backed by the configured OpenAI-compatible LLM ({@code G_ONE_LLM_*}, through
 * {@link LlmClient}). One request per call, JSON in and out, in the shape docs/memory.md "Write timing" gives.
 *
 * <p>The transcript sent is capped to its last {@value #MAX_LINES} lines and {@value #MAX_CHARS} characters, so a long
 * call cannot spend the per-model token budget the farmer's next call needs. The end of a call (the deal readback, what
 * is still open) matters most, so the oldest lines go first; the request says how many were left out.
 */
public final class LlmCallSummarizer implements CallSummarizer {
    static final String STAGE = "gist_failed";
    /** At most this many transcript lines go to the summarizer, the newest ones. */
    static final int MAX_LINES = 40;
    /** At most this many characters of transcript text go to the summarizer. */
    static final int MAX_CHARS = 6_000;
    static final String SYSTEM = """
            You write the call memory for G-One, a phone assistant that helps Indian farmers sell produce.
            Reply with ONE JSON object and nothing else:
            {"gist": string,
             "followUps": [{"text": string, "dueHint": string or null, "status": "OPEN" or "DONE",
                            "id": string or null}],
             "profileUpdates": [{"field": string, "value": string}]}
            Rules:
            - gist: ONE short factual sentence, at most 25 words, third person, plain English: what the farmer asked
              for and what was agreed or left open. A note, never a replay of the conversation: no quotes, no "he
              said", no greetings. No numbers except the agreed deal terms (crop, quantity, price with its unit).
              Never call anything verified, binding or a live price.
            - Never write phone numbers, OTPs, PINs, account, Aadhaar or other ID numbers anywhere.
            - followUps: things G-One should do on a later call. When this call completed an earlier open follow-up
              (openFollowUps), list it with its "id" and status DONE.
            - After a deal both sides confirmed, add an OPEN follow-up to check the pickup and payment with that
              buyer, with the pickup day as dueHint.
            - transcriptOmitted, when present, counts earlier lines left out of a long call.
            - profileUpdates: only stable facts about the FARMER that the farmer clearly stated; never the buyer, never
              a guess. Leave a field out when unsure. Allowed fields and values:
              name (the farmer's own name in English letters, e.g. "Ramesh Patil", never Devanagari or another
              script), village, district, state, usualMandi (place names in English letters), crops (one entry per
              crop, English crop name), language (one of HI, EN, HI_EN, BN), typicalQuantity (how much they usually
              sell at once, short, e.g. "40 quintal per sale"), transport (one of "own vehicle", "needs truck",
              "buyer arranges"), paymentPreference (one of "cash", "UPI", "bank transfer", "on delivery"),
              storageAccess (one of "has cold storage", "cold storage nearby", "no cold storage").
            """;

    private final LlmClient llm;
    private final ObjectMapper json;

    public LlmCallSummarizer(LlmClient llm, ObjectMapper json) {
        this.llm = llm;
        this.json = json;
    }

    @Override
    public boolean available() {
        return llm.configured();
    }

    @Override
    public Draft summarize(CallTranscript transcript, FarmerMemory current) {
        JsonNode answer = llm.askJson(STAGE, SYSTEM, userMessage(transcript, current));
        return parse(answer);
    }

    String userMessage(CallTranscript transcript, FarmerMemory current) {
        ObjectNode root = json.createObjectNode();
        ArrayNode profile = root.putArray("profile");
        for (ProfileFact f : current.facts()) {
            profile.addObject().put("field", f.field()).put("value", f.value())
                    .put("source", f.provenance().saidOrInferred().wire());
        }
        ArrayNode open = root.putArray("openFollowUps");
        for (OpenFollowUp f : CallSummarizer.openFollowUps(current)) {
            open.addObject().put("id", f.id()).put("text", f.text());
        }
        List<CallTranscript.Line> kept = capped(transcript.lines());
        int omitted = transcript.lines().size() - kept.size();
        if (omitted > 0) root.put("transcriptOmitted", omitted);
        ArrayNode lines = root.putArray("transcript");
        for (CallTranscript.Line line : kept) {
            lines.addObject().put("speaker", line.speaker().name().toLowerCase(Locale.ROOT)).put("text", line.text());
        }
        try {
            return json.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode the gist request", e);
        }
    }

    /** The newest lines within {@value #MAX_LINES} lines and {@value #MAX_CHARS} characters, in call order. */
    static List<CallTranscript.Line> capped(List<CallTranscript.Line> all) {
        int chars = 0;
        int from = all.size();
        while (from > 0 && all.size() - from < MAX_LINES) {
            String text = all.get(from - 1).text();
            if (chars + text.length() > MAX_CHARS) {
                if (from == all.size()) {
                    // One overlong last line: keep its end rather than nothing.
                    CallTranscript.Line l = all.get(from - 1);
                    return List.of(new CallTranscript.Line(l.speaker(), text.substring(text.length() - MAX_CHARS), l.atMs()));
                }
                break;
            }
            chars += text.length();
            from--;
        }
        return all.subList(from, all.size());
    }

    static Draft parse(JsonNode answer) {
        String gist = answer.path("gist").asText("");
        List<FollowUp> followUps = new ArrayList<>();
        for (JsonNode f : answer.path("followUps")) {
            if (f.isTextual()) {
                followUps.add(new FollowUp(f.asText(), null, true));
            } else if (f.isObject()) {
                String due = f.path("dueHint").isTextual() ? f.path("dueHint").asText() : null;
                boolean open = !"DONE".equalsIgnoreCase(f.path("status").asText("OPEN"));
                String ref = f.path("id").isTextual() ? f.path("id").asText() : null;
                followUps.add(new FollowUp(f.path("text").asText(""), due, open, ref));
            }
        }
        List<FactUpdate> updates = new ArrayList<>();
        for (JsonNode u : answer.path("profileUpdates")) {
            if (u.path("field").isTextual() && u.path("value").isTextual()) {
                updates.add(new FactUpdate(u.path("field").asText(), u.path("value").asText()));
            }
        }
        return new Draft(gist, followUps, updates);
    }
}
