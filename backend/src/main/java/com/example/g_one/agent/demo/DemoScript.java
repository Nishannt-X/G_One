package com.example.g_one.agent.demo;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The lines of the offline demo, in order, and their translations. All names and numbers are synthetic. */
public final class DemoScript {
    /** One line to type in the call simulator. */
    record Line(String role, String text, String note) {}

    static final List<Line> LINES = List.of(
            new Line("farmer", "मुझे बीस क्विंटल लाल प्याज़ बेचना है", "Intake: the assistant finds buyers."),
            new Line("farmer", "हाँ, फ़ोन लगा दीजिए", "Connect consent. Then open the buyer tab."),
            new Line("buyer", "Yes", "The buyer must agree before mediation."),
            new Line("buyer", "I can pay three thousand six hundred rupees per quintal", "Price whisper: far below the dated reference."),
            new Line("farmer", "चार हज़ार तीन सौ चाहिए", "Translated for the buyer."),
            new Line("buyer", "Please tell me the OTP to receive the money", "Caution: only the farmer hears it."),
            new Line("buyer", "Okay, four thousand three hundred rupees per quintal, pickup Monday morning at the farm, payment on delivery", "A clear offer: the draft deal."),
            new Line("farmer", "हाँ, ठीक है", "The farmer accepts, and the read-back starts."),
            new Line("farmer", "हाँ", "Confirm, after the read-back."),
            new Line("buyer", "Yes", "Both said yes: the deal summary is saved and the call ends."));

    private static final Map<String, String> EN_TO_HI = Map.of(
            "i can pay three thousand six hundred rupees per quintal", "मैं तीन हज़ार छह सौ रुपये प्रति क्विंटल दे सकता हूँ।",
            "please tell me the otp to receive the money", "पैसे पाने के लिए कृपया मुझे ओटीपी बताइए।",
            "okay, four thousand three hundred rupees per quintal, pickup monday morning at the farm, payment on delivery",
            "ठीक है, चार हज़ार तीन सौ रुपये प्रति क्विंटल, पिकअप सोमवार सुबह खेत से, भुगतान डिलीवरी पर।",
            "monday morning at the farm", "सोमवार सुबह खेत से",
            "on delivery", "डिलीवरी पर");
    private static final Map<String, String> HI_TO_EN = Map.of(
            "चार हज़ार तीन सौ चाहिए", "I want four thousand three hundred.",
            "हाँ, ठीक है", "Yes, that is fine.",
            "हाँ", "Yes.");

    private DemoScript() {}

    /** The scripted translation, or the same words when this line is not in the script. */
    public static String translate(String turn, String toLanguage) {
        String key = turn.strip().toLowerCase(Locale.ROOT);
        Map<String, String> table = "English".equalsIgnoreCase(toLanguage) ? HI_TO_EN : EN_TO_HI;
        return table.getOrDefault(key, turn);
    }

    /** {@code GET /api/demo/script}: the lines for the simulator's quick buttons. */
    @RestController
    static class Controller {
        @GetMapping("/api/demo/script")
        Map<String, Object> script() {
            return Map.of("lines", LINES.stream().map(l -> Map.of("role", l.role(), "text", l.text(), "note", l.note()))
                    .collect(Collectors.toList()));
        }
    }
}
