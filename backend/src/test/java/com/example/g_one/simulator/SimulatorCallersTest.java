package com.example.g_one.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.g_one.core.CallerKey;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.memory.MemoryProperties;
import com.example.g_one.memory.MemorySeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SimulatorCallersTest {
    private final SimulatorCallers callers = new SimulatorCallers("", "");

    @Test
    void theSameSyntheticNumberAlwaysGivesTheSameKey() {
        var a = callers.keyFor("+919876543210");
        assertThat(a).isPresent().isEqualTo(callers.keyFor(" +919876543210 "));
        assertThat(a).isNotEqualTo(callers.keyFor("+919876543211"));
    }

    @Test
    void aBlankNumberIsAWithheldCallerId() {
        assertThat(callers.keyFor(null)).isEmpty();
        assertThat(callers.keyFor("  ")).isEmpty();
    }

    @Test
    void aNumberOutsideTheSyntheticListIsRefusedWithoutEchoingIt() {
        assertThatThrownBy(() -> callers.keyFor("+15005550006"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("5005550006");
    }

    @Test
    void aConfiguredSaltChangesTheKeyAndAShortOneIsRefused() {
        var other = new SimulatorCallers("", "a-configured-salt-that-is-32-bytes!!");
        assertThat(other.keyFor("+919876543210")).isNotEqualTo(callers.keyFor("+919876543210"));
        assertThatThrownBy(() -> new SimulatorCallers("", "short")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyOfferedNumberIsAValidCaller() {
        for (var c : callers.all()) {
            assertThat(callers.keyFor(c.number())).isPresent().get().isInstanceOf(CallerKey.class);
        }
    }

    /** A synthetic test key (base64 of 32 ASCII bytes); never a real deployment key. */
    private static final String TEST_HMAC_KEY = Base64.getEncoder()
            .encodeToString("synthetic-test-hmac-key-32-bytes".getBytes(StandardCharsets.UTF_8));

    @Test
    void withTheDocumentedEnvCallerAIsTheSeededFarmer() throws IOException {
        // The README demo env: G_ONE_MEMORY_HMAC_KEY, G_ONE_DEMO_FARMER_PHONE=+919876543210 and the bundled seed.
        var store = new InMemoryMemoryStore();
        JsonNode seed;
        try (InputStream in = new ClassPathResource("memory/seed-suresh.json").getInputStream()) {
            seed = new ObjectMapper().readTree(in);
        }
        var salt = new MemoryProperties(null, null, TEST_HMAC_KEY, null, null).salt();
        var result = MemorySeeder.seed(store, seed, Map.of("G_ONE_DEMO_FARMER_PHONE", "+919876543210")::get, salt);
        assertThat(result.status()).isEqualTo(MemorySeeder.Status.SEEDED);

        // A caller salt set as well must not split the simulator from the seed.
        var simulator = new SimulatorCallers(TEST_HMAC_KEY, "a-configured-salt-that-is-32-bytes!!");
        CallerKey callerA = simulator.keyFor("+919876543210").orElseThrow();
        assertThat(callerA).isEqualTo(CallerKey.fromPhone("+919876543210", salt.orElseThrow()));
        assertThat(store.load(callerA).orElseThrow().fact("name").orElseThrow().value()).isEqualTo("Suresh");
        assertThat(store.load(simulator.keyFor("+919876543211").orElseThrow())).isEmpty();
    }

    @Test
    void aMalformedHmacKeyStopsStartupInsteadOfFallingBack() {
        assertThatThrownBy(() -> new SimulatorCallers("not base64!!", "")).isInstanceOf(IllegalStateException.class);
    }
}
