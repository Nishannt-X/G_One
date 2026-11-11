package com.example.g_one.agent.mediation;

import com.example.g_one.agent.deal.Offer;
import com.example.g_one.agent.deal.Terms;
import com.example.g_one.core.LegRole;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What each party has said and heard during mediation, kept per leg: the lines, and the deal terms that party knows. A
 * line or a term is written only onto the legs of the people who actually said it or heard it to the end, so a party's
 * answers can only ever come from their own leg. The buyer's leg never holds a whisper, a farmer turn that was withheld
 * or cut off, a farmer term the buyer did not hear, or the farmer's own questions to the assistant.
 */
final class CallLedger {
    /** How many lines each leg keeps: the recent past is what a question is about. */
    static final int MAX_LINES = 12;
    /** A line is cut to this length: a question needs the gist, not a long monologue. */
    static final int MAX_CHARS = 240;
    private static final Pattern PHRASE = Pattern.compile("[\\p{L}\\p{M}\\p{N} .,'&/()-]{1,80}");

    enum Kind {
        /** This party said it, and it was passed on. */
        SAID,
        /** The other party said it; this is what this party heard to the end, in their language. */
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

    /**
     * The deal terms one party knows: said by them, or heard by them to the end. {@code price} is the last price a buyer
     * stated clearly, whatever has become of that offer since; whether it is still open is the deal desk's to say.
     */
    record Known(String commodity, String variety, String market, BigDecimal price, String unit, String quantity,
                 String pickup, String payment) {
        static final Known NOTHING = new Known(null, null, null, null, null, null, null, null);
    }

    private final Map<LegRole, Deque<Line>> legs = new EnumMap<>(LegRole.class);
    private final Map<LegRole, Known> known = new EnumMap<>(LegRole.class);

    CallLedger() {
        for (LegRole role : LegRole.values()) {
            legs.put(role, new ArrayDeque<>());
            known.put(role, Known.NOTHING);
        }
    }

    /** A party's own words, passed on to the other. */
    void said(LegRole speaker, String text) {
        add(speaker, Kind.SAID, text);
    }

    /** A translation that played to the end on {@code listener}'s leg. */
    void heard(LegRole listener, String text) {
        add(listener, Kind.HEARD, text);
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

    /** The last translation this party heard to the end, or null. */
    String lastHeard(LegRole leg) {
        List<Line> lines = lines(leg);
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).kind() == Kind.HEARD) return lines.get(i).text();
        }
        return null;
    }

    /** This party's earlier questions to the assistant, oldest first, for a follow-up. */
    List<String> askedBefore(LegRole leg) {
        return lines(leg).stream().filter(l -> l.kind() == Kind.ASKED).map(Line::text).toList();
    }

    // ---- terms ----

    /**
     * {@code leg} learned what a mediated turn stated: because they said it, or because its translation played to the
     * end on their leg. A price counts only from a buyer's clear offer, as the deal desk reads it.
     */
    void learned(LegRole leg, Offer offer, boolean buyerOffer) {
        if (offer == null) return;
        boolean price = buyerOffer && "clear".equals(offer.status()) && offer.price() != null && offer.price().signum() > 0
                && (offer.currency() == null || "INR".equalsIgnoreCase(offer.currency())) && phrase(offer.unit()) != null;
        synchronized (known) {
            Known k = known.get(leg);
            known.put(leg, new Known(
                    price ? or(phrase(offer.commodity()), k.commodity()) : or(k.commodity(), phrase(offer.commodity())),
                    price ? phrase(offer.variety()) : k.variety(),
                    price ? phrase(offer.market()) : k.market(),
                    price ? offer.price().stripTrailingZeros() : k.price(),
                    price ? Offer.unit(offer.unit()) : k.unit(),
                    or(phrase(offer.quantity()), k.quantity()),
                    or(phrase(offer.pickup()), k.pickup()),
                    or(phrase(offer.payment()), k.payment())));
        }
    }

    /** {@code leg} heard a read-back of these terms to the end. */
    void learned(LegRole leg, Terms t) {
        synchronized (known) {
            Known k = known.get(leg);
            known.put(leg, new Known(t.commodity(), t.variety(), t.market(), t.price(), t.unit(),
                    or(t.quantity(), k.quantity()), or(t.pickup(), k.pickup()), or(t.payment(), k.payment())));
        }
    }

    /** {@code leg} was told the crop and the amount: the farmer at intake, the buyer in the introduction. */
    void introduced(LegRole leg, String commodity, String quantity) {
        synchronized (known) {
            Known k = known.get(leg);
            known.put(leg, new Known(or(k.commodity(), phrase(commodity)), k.variety(), k.market(), k.price(), k.unit(),
                    or(k.quantity(), phrase(quantity)), k.pickup(), k.payment()));
        }
    }

    Known known(LegRole leg) {
        synchronized (known) {
            return known.get(leg);
        }
    }

    private static String or(String a, String b) {
        return a != null ? a : b;
    }

    /** Short plain text, or null: a model-extracted term that is odd is dropped, never read aloud. */
    private static String phrase(String s) {
        if (s == null) return null;
        String t = s.strip().replaceAll("\\s+", " ");
        return !t.isEmpty() && PHRASE.matcher(t).matches() ? t : null;
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
