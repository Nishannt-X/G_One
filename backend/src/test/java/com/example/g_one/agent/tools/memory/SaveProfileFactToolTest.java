package com.example.g_one.agent.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.ToolResult;
import com.example.g_one.fakes.FakeLeg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** save_profile_fact keeps names in English letters, takes the structured fields, and still stores only on a yes. */
class SaveProfileFactToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T03:30:00Z"), ZoneOffset.UTC);
    private static final CallerKey KEY = FakeLeg.FARMER_KEY;

    private final InMemoryMemoryStore store = new InMemoryMemoryStore();
    private final MemoryGate gate = new MemoryGate(CLOCK);
    private final PendingFacts pending = new PendingFacts(() -> store);
    private final SaveProfileFactTool tool = new SaveProfileFactTool(() -> store, gate, CLOCK, pending);

    private static CallContext newFarmer() {
        CallId id = CallId.random();
        return new CallContext(id, new FakeLeg(id, LegRole.FARMER, LegSource.TWILIO, FakeLeg.HINDI, KEY), FakeLeg.HINDI,
                FarmerMemory.empty(KEY));
    }

    private ToolResult save(CallContext ctx, String field, String value) throws Exception {
        return tool.invoke(JSON.readTree(JSON.writeValueAsString(java.util.Map.of("field", field, "value", value))), ctx);
    }

    @Test
    void aNameSaidInDevanagariIsKeptInEnglishLetters() throws Exception {
        CallContext ctx = newFarmer();
        assertThat(save(ctx, "name", "रमेश पाटिल").ok()).isTrue();
        assertThat(pending.held(ctx.callId())).singleElement()
                .satisfies(f -> assertThat(f.value()).isEqualTo("Ramesh Patil"));
    }

    @Test
    void structuredFieldsTakeFixedValuesAndRejectOthers() throws Exception {
        CallContext ctx = newFarmer();
        assertThat(save(ctx, "transport", "own tractor").ok()).isTrue();
        assertThat(save(ctx, "paymentPreference", "gpay").ok()).isTrue();
        assertThat(save(ctx, "storageAccess", "nearby").ok()).isTrue();
        assertThat(save(ctx, "typicalQuantity", "40 quintal per sale").ok()).isTrue();
        assertThat(pending.held(ctx.callId())).extracting(f -> f.field() + "=" + f.value()).containsExactly(
                "transport=own vehicle", "paymentPreference=UPI", "storageAccess=cold storage nearby",
                "typicalQuantity=40 quintal per sale");

        ToolResult bad = save(ctx, "paymentPreference", "crypto");
        assertThat(bad.ok()).isFalse();
        assertThat(bad.content()).contains("cash, UPI, bank transfer, on delivery");
        assertThat(save(ctx, "name", "王伟").ok()).isFalse();
        assertThat(save(ctx, "typicalQuantity", "phone 98765 43210").ok()).isFalse();
    }

    @Test
    void nothingIsStoredWithoutTheFarmersYes() throws Exception {
        CallContext ctx = newFarmer();
        save(ctx, "transport", "needs truck");
        save(ctx, "name", "Asha");
        assertThat(store.load(KEY)).as("held for the call only").isEmpty();

        pending.drop(ctx.callId());
        assertThat(pending.commit(ctx.callId())).isZero();
        assertThat(store.load(KEY)).as("no yes: nothing stored").isEmpty();

        CallContext yes = newFarmer();
        save(yes, "transport", "needs truck");
        assertThat(pending.commit(yes.callId())).isEqualTo(1);
        assertThat(store.load(KEY).orElseThrow().fact("transport").orElseThrow().value()).isEqualTo("needs truck");
    }

    @Test
    void theSchemaOffersTheNewFields() {
        JsonNode e = tool.jsonSchema().path("properties").path("field").path("enum");
        assertThat(e.toString()).contains("typicalQuantity", "transport", "paymentPreference", "storageAccess");
        assertThat(SaveProfileFactTool.FIELDS).contains("typicalQuantity", "transport", "paymentPreference", "storageAccess");
    }
}
