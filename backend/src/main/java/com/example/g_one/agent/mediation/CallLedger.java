package com.example.g_one.agent.mediation;

import com.example.g_one.core.LegRole;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What each party has said and heard during mediation, kept per leg. A line is written only onto the legs of the people
 * who actually said or heard it, so a party's view of the call can only ever contain their own leg: the buyer's leg never
 * holds a whisper, a farmer turn that was withheld, or the farmer's own questions to the assistant.
 */
final class CallLedger {
    /** How many lines each leg keeps: the recent past is what a question is about. */
    static final int MAX_LINES = 12;
    /** A line is cut to this length: a question needs the gist, not a long monologue. */
    static final int MAX_CHARS = 240;

    enum Kind {
        /** This party said it, and it was passed on. */
        SAID,
        /** The other party said it; this is what this party heard, in their language. */
        HEARD,
        /** The assistant said it on this leg (an introduction, a read-back, a fixed line). */
        ASSISTANT,
        /** A private line the assistant said to the farmer only. */
        WHISPER,
        /** This party asked the assistant directly; nobody else heard it. */
        ASKED,
        /** The assistant's answer to that question, heard on this leg only. */
        ANSWERED
    }

    record Line(Kind kind, String text) {}

    private final Map<LegRole, Deque<Line>> legs = new EnumMap<>(LegRole.class);

    CallLedger() {
        for (LegRole role : LegRole.values()) legs.put(role, new ArrayDeque<>());
    }

    /** A relayed turn: the speaker's own words on their leg, the translation the listener heard on theirs. */
    void relayed(LegRole speaker, String said, String heard) {
        add(speaker, Kind.SAID, said);
        add(speaker.other(), Kind.HEARD, heard);
    }

    void assistant(LegRole leg, String text) {
        add(leg, Kind.ASSISTANT, text);
    }

    void whisper(String text) {
        add(LegRole.FARMER, Kind.WHISPER, text);
    }

    /** A question to the assistant and its answer: both stay on the asker's leg. */
    void aside(LegRole asker, String question, String answer) {
        add(asker, Kind.ASKED, question);
        add(asker, Kind.ANSWERED, answer);
    }

    /** The lines on one leg, oldest first. */
    List<Line> lines(LegRole leg) {
        synchronized (legs) {
            return new ArrayList<>(legs.get(leg));
        }
    }

    private void add(LegRole leg, Kind kind, String text) {
        if (text == null || text.isBlank()) return;
        String t = text.strip();
        if (t.length() > MAX_CHARS) t = t.substring(0, MAX_CHARS) + "…";
        synchronized (legs) {
            Deque<Line> lines = legs.get(leg);
            lines.addLast(new Line(kind, t));
            while (lines.size() > MAX_LINES) lines.removeFirst();
        }
    }
}
