package com.example.g_one.advisory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class QualityTermsTest {
    private final QualityTerms terms = new QualityTerms();
    private final QualityTermsTool tool = new QualityTermsTool();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void extractsMoistureInEnglishAndHindi() {
        var res1 = terms.extract("The wheat has 12.5% moisture level");
        assertThat(res1.moisturePercent()).isEqualTo(12.5);

        var res2 = terms.extract("नमी 14 प्रतिशत है माल में");
        assertThat(res2.moisturePercent()).isEqualTo(14.0);
    }

    @Test
    void extractsFaqGrades() {
        assertThat(terms.extract("This is FAQ quality grain").faqGrade()).isEqualTo("FAQ");
        assertThat(terms.extract("ये नॉन-एफएक्यू माल है").faqGrade()).isEqualTo("Non-FAQ");
        assertThat(terms.extract("Super quality Grade A onion").faqGrade()).isIn("Grade A", "Super");
    }

    @Test
    void extractsVarieties() {
        assertThat(terms.extract("50 quintal Sharbati gehun hai").variety()).isEqualTo("Sharbati");
        assertThat(terms.extract("हमारे पास लोकवन गेहूं है").variety()).isEqualTo("Lokwan");
        assertThat(terms.extract("Pusa 1121 basmati dhan").variety()).isEqualTo("Pusa 1121");
        assertThat(terms.extract("नाशिक का गरवा प्याज है").variety()).isEqualTo("Garwa");
    }

    @Test
    void extractsPackagingBags() {
        assertThat(terms.extract("50 kg jute bag packing").bagType()).contains("Jute");
        assertThat(terms.extract("बारदाना में भरा हुआ है").bagType()).contains("Jute");
        assertThat(terms.extract("plastic katta packing").bagType()).contains("Plastic");
        assertThat(terms.extract("सीधा ट्रैक्टर ट्रॉली में खुला माल है").bagType()).contains("Loose");
    }

    @Test
    void extractsCombinedDetailsInEnglish() {
        var res = terms.extract("We have Sharbati wheat, FAQ grade, 12% moisture, packed in jute bags");
        assertThat(res.variety()).isEqualTo("Sharbati");
        assertThat(res.faqGrade()).isEqualTo("FAQ");
        assertThat(res.moisturePercent()).isEqualTo(12.0);
        assertThat(res.bagType()).contains("Jute");
        assertThat(res.summaryEn()).contains("Variety: Sharbati");
        assertThat(res.summaryEn()).contains("Moisture: 12.0%");
    }

    @Test
    void extractsCombinedDetailsInHindi() {
        var res = terms.extract("लोकवन गेहूं है, नमी 13 प्रतिशत, बारदाना में पैक, ग्रेड ए माल है");
        assertThat(res.variety()).isEqualTo("Lokwan");
        assertThat(res.moisturePercent()).isEqualTo(13.0);
        assertThat(res.bagType()).contains("Jute");
        assertThat(res.summaryHi()).contains("किस्म: Lokwan");
        assertThat(res.summaryHi()).contains("तेरह प्रतिशत");
    }

    @Test
    void handlesNoTermsGracefully() {
        var res = terms.extract("hello how are you");
        assertThat(res.hasAny()).isFalse();
        assertThat(res.moisturePercent()).isNull();
        assertThat(res.variety()).isNull();
    }

    @Test
    void toolInvocationWorks() throws Exception {
        var args = JSON.createObjectNode().put("text", "Pusa 1121 paddy, 14% moisture, FAQ grade, jute bori");
        ToolResult res = tool.invoke(args, null);
        assertThat(res.ok()).isTrue();
        var json = JSON.readTree(res.content());
        assertThat(json.get("hasAny").asBoolean()).isTrue();
        assertThat(json.get("variety").asText()).isEqualTo("Pusa 1121");
        assertThat(json.get("moisturePercent").asDouble()).isEqualTo(14.0);
        assertThat(json.get("faqGrade").asText()).isEqualTo("FAQ");
        assertThat(json.get("bagType").asText()).contains("Jute");
    }
}
