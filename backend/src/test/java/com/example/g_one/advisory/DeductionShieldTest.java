package com.example.g_one.advisory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DeductionShieldTest {
    private final DeductionShield shield = new DeductionShield();
    private final DeductionShieldTool tool = new DeductionShieldTool();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void parsesFiveKgPerBagCut() {
        var calc = shield.analyze(2500, "5 kg per bag cut lagega");
        // 5 kg per 50kg bag = 10 kg per quintal (10%) = ₹250
        assertThat(calc.totalWeightCutKgPerQuintal()).isEqualTo(10.0);
        assertThat(calc.totalDeductionsRupeesPerQuintal()).isEqualTo(250.0);
        assertThat(calc.netPriceRupeesPerQuintal()).isEqualTo(2250.0);
        assertThat(calc.suggestionEn()).contains("₹2250");
        assertThat(calc.suggestionHi()).contains("दो हज़ार दो सौ पचास");
    }

    @Test
    void parsesDhaltaAndCommission() {
        var calc = shield.analyze(3000, "2 kg dhalta aur 2% commission");
        // 2 kg per bag = 4 kg / quintal = 4% of 3000 = 120
        // 2% commission = 60
        // Total = 180, net = 2820
        assertThat(calc.totalWeightCutKgPerQuintal()).isEqualTo(4.0);
        assertThat(calc.totalDeductionsRupeesPerQuintal()).isEqualTo(180.0);
        assertThat(calc.netPriceRupeesPerQuintal()).isEqualTo(2820.0);
        assertThat(calc.items()).hasSize(2);
    }

    @Test
    void parsesKataAndPalledariPerBag() {
        var calc = shield.analyze(2000, "kata 3 kg per bag, palledari 20 rupay per bag");
        // 3 kg per bag = 6 kg / quintal = ₹120
        // Palledari 20 rs per bag = 2 bags * 20 = ₹40
        // Total = 160, net = 1840
        assertThat(calc.totalDeductionsRupeesPerQuintal()).isEqualTo(160.0);
        assertThat(calc.netPriceRupeesPerQuintal()).isEqualTo(1840.0);
    }

    @Test
    void parsesMoistureCutAndPalledariPerQuintal() {
        var calc = shield.analyze(4000, "4% moisture cut and 15 rs per quintal palledari");
        // 4% of 4000 = 160
        // 15 rs per quintal = 15
        // Total = 175, net = 3825
        assertThat(calc.totalDeductionsRupeesPerQuintal()).isEqualTo(175.0);
        assertThat(calc.netPriceRupeesPerQuintal()).isEqualTo(3825.0);
    }

    @Test
    void parsesDevanagariDeductions() {
        var calc = shield.analyze(2500, "ढलता 2 किलो प्रति बोरी, आढ़त 2 प्रतिशत और पल्लेदारी 20 रुपये");
        assertThat(calc.items()).isNotEmpty();
        assertThat(calc.netPriceRupeesPerQuintal()).isLessThan(2500.0);
        assertThat(calc.suggestionHi()).contains("रुपये प्रति क्विंटल शुद्ध");
    }

    @Test
    void handlesNoDeductionsGracefully() {
        var calc = shield.analyze(2500, "");
        assertThat(calc.netPriceRupeesPerQuintal()).isEqualTo(2500.0);
        assertThat(calc.totalDeductionsRupeesPerQuintal()).isEqualTo(0.0);
    }

    @Test
    void toolInvocationWorks() throws Exception {
        var args = JSON.createObjectNode()
                .put("basePrice", 2500)
                .put("terms", "5 kg per bag cut, 2% commission");
        ToolResult res = tool.invoke(args, null);
        assertThat(res.ok()).isTrue();
        var json = JSON.readTree(res.content());
        assertThat(json.get("netPrice").asDouble()).isLessThan(2500.0);
        assertThat(json.get("suggestedCounterOffer").asDouble()).isGreaterThan(json.get("netPrice").asDouble());
        assertThat(json.get("suggestionHi").asText()).contains("कमीशन");
    }
}
