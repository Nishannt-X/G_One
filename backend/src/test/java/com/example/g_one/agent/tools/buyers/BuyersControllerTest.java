package com.example.g_one.agent.tools.buyers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "g-one.memory.store=in-memory")
class BuyersControllerTest {
    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper json;

    @Test
    void listsAllBuyersWithoutAnyPhoneReference() throws Exception {
        String body = rest.getForObject("/api/buyers", String.class);
        JsonNode buyers = json.readTree(body).path("buyers");
        assertThat(buyers.size()).isBetween(6, 10);
        assertThat(body).doesNotContain("phoneRef", "MEHTA_AGRO");
    }

    @Test
    void ranksForACrop() throws Exception {
        JsonNode buyers = json.readTree(rest.getForObject("/api/buyers?commodity=onion&district=Nashik&quantity=20",
                String.class)).path("buyers");
        assertThat(buyers.get(0).path("id").asText()).isEqualTo("mehta-agro");
        assertThat(buyers.get(0).path("score").asDouble()).isPositive();
    }
}
