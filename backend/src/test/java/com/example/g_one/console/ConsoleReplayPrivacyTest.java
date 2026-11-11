package com.example.g_one.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A party's private question to G-One goes to the consoles watching live, and is never kept for a late subscriber. */
class ConsoleReplayPrivacyTest {
    @Test
    void anAsideIsDeliveredLiveButNeverReplayed() {
        ConsoleBus bus = new ConsoleBus();
        ConsoleState state = new ConsoleState(bus);
        List<ConsoleEvent> live = new ArrayList<>();
        state.subscribe(live::add);
        CallId call = CallId.of("call-1");
        LegId farmer = LegId.of("leg-f");
        bus.publish(new ConsoleEvent.CallStarted(call, 1, "hi-IN"));
        bus.publish(new ConsoleEvent.AsideAnswered(call, 2, farmer, LegRole.FARMER, "hi-IN",
                "जी-वन, मेरा कम से कम भाव तीन हज़ार नौ सौ है", "ठीक है", 1));
        bus.publish(new ConsoleEvent.AssistantSaid(call, 3, farmer, LegRole.FARMER, "hi-IN", "नमस्ते"));

        List<ConsoleEvent> late = new ArrayList<>();
        state.subscribe(late::add);

        assertThat(live).hasAtLeastOneElementOfType(ConsoleEvent.AsideAnswered.class);
        assertThat(late).hasSize(2).noneMatch(e -> e instanceof ConsoleEvent.AsideAnswered);
        state.close();
    }
}
