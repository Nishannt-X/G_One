package com.example.g_one.simulator;

import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.Leg;
import com.example.g_one.core.LegRole;
import java.util.List;

/**
 * What a buyer's own screen may show of the call. This is an allow-list: only the buyer's words, their translation for
 * the farmer, translations spoken to the buyer and G-One's words to the buyer pass. Whispers, tools, memory and deal
 * state are the farmer's and operator's, and any event type added later stays private until it is listed here.
 */
final class BuyerLines {
    /** {@code kind} on the wire: "you_said", "they_hear", "you_hear" or "g_one". */
    record Line(String kind, String language, String text) {}

    private BuyerLines() {}

    /** The lines this event gives the buyer on {@code buyer}: none, or one, or a question to G-One and its answer. */
    static List<Line> forBuyer(ConsoleEvent event, Leg buyer) {
        if (!buyer.callId().equals(event.callId())) return List.of();
        if (event instanceof ConsoleEvent.AsideAnswered e) {
            // Only the buyer's own private exchange with G-One; the farmer's never leaves the farmer's leg.
            if (e.role() != LegRole.BUYER || !e.legId().equals(buyer.id())) return List.of();
            return List.of(new Line("you_said", e.language(), e.question()), new Line("g_one", e.language(), e.answer()));
        }
        Line line = switch (event) {
            case ConsoleEvent.TurnHeard e when e.role() == LegRole.BUYER && e.legId().equals(buyer.id()) ->
                    new Line("you_said", e.language(), e.text());
            case ConsoleEvent.TurnTranslated e when e.toRole() == LegRole.BUYER ->
                    new Line("you_hear", e.toLanguage(), e.text());
            case ConsoleEvent.TurnTranslated e when e.role() == LegRole.BUYER && e.legId().equals(buyer.id()) ->
                    new Line("they_hear", e.toLanguage(), e.text());
            case ConsoleEvent.AssistantSaid e when e.role() == LegRole.BUYER && e.legId().equals(buyer.id()) ->
                    new Line("g_one", e.language(), e.text());
            default -> null;
        };
        return line == null ? List.of() : List.of(line);
    }
}
