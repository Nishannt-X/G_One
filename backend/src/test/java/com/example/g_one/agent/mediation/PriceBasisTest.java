package com.example.g_one.agent.mediation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PriceBasisTest {
    @Test
    void acceptsOnlyAnExplicitUnambiguousBasis() {
        assertThat(PriceBasis.read("10 kg rice for 2000")).isNull();
        assertThat(PriceBasis.read("2000 per kg")).isEqualTo("kg");
        assertThat(PriceBasis.read("दो हजार प्रति क्विंटल")).isEqualTo("quintal");
        assertThat(PriceBasis.read("2000 total for all of it")).isEqualTo("total");
        assertThat(PriceBasis.read("total or per kg?")).isNull();
        assertThat(PriceBasis.conflicting("total or per kg?")).isTrue();
        assertThat(PriceBasis.conditional("not per kg")).isTrue();
        assertThat(PriceBasis.clarification("per kg")).isEqualTo("kg");
        assertThat(PriceBasis.clarification("total")).isEqualTo("total");
        assertThat(PriceBasis.clarification("per kg if I like it")).isNull();
        assertThat(PriceBasis.evidence("10 per kg last year, today 2000", "en-IN", new java.math.BigDecimal("2000"))).isNull();
        assertThat(PriceBasis.evidence("10 kg rice, 2000 per kg", "en-IN", new java.math.BigDecimal("2000"))).isEqualTo("kg");
    }
}
