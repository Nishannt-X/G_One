package com.example.g_one.agent.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.memory.MemorySeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class MemoryBlockTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long DAY = 86_400_000L;
    private static final long NOW = 1_762_590_000_000L;

    @Test
    void theBundledSeedFarmerKeepsBothGistsAndTheOpenFollowUpWithinTheCap() throws IOException {
        MemoryStore store = new InMemoryMemoryStore();
        JsonNode seed;
        try (InputStream in = new ClassPathResource("memory/seed-suresh.json").getInputStream()) {
            seed = JSON.readTree(in);
        }
        MemorySeeder.seed(store, seed, Map.of("G_ONE_DEMO_FARMER_PHONE", "+15005550006")::get,
                Optional.of(FakeLeg.SALT));
        FarmerMemory suresh = store.load(FakeLeg.FARMER_KEY).orElseThrow();

        String block = MemoryBlock.render(suresh);

        assertThat(MemoryBlock.estimateTokens(block)).isLessThanOrEqualTo(MemoryBlock.MAX_TOKENS);
        assertThat(block).contains("Suresh asked the red onion price").contains("Suresh wanted to sell tomato")
                .contains("Find a buyer for ~20 quintal red onion after harvest")
                .contains("\"usualMandi\":\"Lasalgaon APMC\"");
    }

    @Test
    void anotherSpeakersStoredGistStaysOutOfThePrompt() {
        List<CallGist> gists = List.of(
                new CallGist(CallId.of("c-other"), NOW - DAY, NOW - DAY + 60_000,
                        MemoryBlock.OTHER_SPEAKER_PREFIX + "Someone asked for a cotton buyer.", List.of("Find a cotton buyer")),
                new CallGist(CallId.of("c-own"), NOW - 2 * DAY, NOW - 2 * DAY + 60_000,
                        "Suresh asked the red onion price.", List.of("Find a buyer for red onion")));
        String block = MemoryBlock.render(FarmerMemory.of(FakeLeg.FARMER_KEY, facts(), gists));

        assertThat(block).contains("Suresh asked the red onion price").contains("Find a buyer for red onion")
                .doesNotContain("cotton").doesNotContain("Other speaker");
    }

    @Test
    void anOpenFollowUpFromTheOldestGistSurvivesWhenItsSummaryIsDropped() {
        List<CallGist> gists = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long at = NOW - (i + 1) * DAY;
            gists.add(new CallGist(CallId.of("c-" + i), at, at + 60_000,
                    "Summary of call " + i + ": " + "the farmer talked about onions and the harvest. ".repeat(11),
                    i == 3 ? List.of("Send the cold storage list to the farmer") : List.of()));
        }
        String block = MemoryBlock.render(FarmerMemory.of(FakeLeg.FARMER_KEY, facts(), gists));

        assertThat(MemoryBlock.estimateTokens(block)).isLessThanOrEqualTo(MemoryBlock.MAX_TOKENS);
        assertThat(block).contains("Summary of call 0").doesNotContain("Summary of call 3")
                .contains("Send the cold storage list to the farmer");
    }

    @Test
    void manyGistsStayWithinTheCapWithOpenFollowUpsDeduplicatedNewestFirst() {
        List<CallGist> gists = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            long at = NOW - (i + 1) * DAY;
            gists.add(new CallGist(CallId.of("c-" + i), at, at + 60_000,
                    "Call " + i + " " + "x".repeat(700),
                    List.of("Follow-up " + i + " " + "y".repeat(200), "Shared reminder")));
        }
        String block = MemoryBlock.render(FarmerMemory.of(FakeLeg.FARMER_KEY, facts(), gists));

        assertThat(MemoryBlock.estimateTokens(block)).isLessThanOrEqualTo(MemoryBlock.MAX_TOKENS);
        assertThat(block.split("Shared reminder", -1)).hasSize(2);
        assertThat(block).doesNotContain("Call 3 ").doesNotContain("Follow-up 5 ");
        assertThat(block.indexOf("Follow-up 0 ")).isLessThan(block.indexOf("Shared reminder"));
    }

    private static List<ProfileFact> facts() {
        Provenance p = Provenance.said(CallId.of("c-0"), NOW - DAY);
        return List.of(new ProfileFact("name", "Suresh", p), new ProfileFact("district", "Nashik", p),
                new ProfileFact("usualMandi", "Lasalgaon APMC", p));
    }
}
