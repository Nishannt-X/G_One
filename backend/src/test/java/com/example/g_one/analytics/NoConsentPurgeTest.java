package com.example.g_one.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.console.ConsoleState;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A call nobody said yes to leaves nothing behind once it ends: the console's running-call state drops it at
 * call_ended, a new console subscriber is not replayed its events (subtitles included), and no record is stored.
 */
class NoConsentPurgeTest {
    @Test
    void anEndedCallWithoutConsentLeavesNothing() {
        ConsoleBus bus = new ConsoleBus();
        ConsoleState state = new ConsoleState(bus);
        try (CallRecordStore store = new CallRecordStore("jdbc:sqlite::memory:");
             CallRecordWriter writer = new CallRecordWriter(bus, store, new ObjectMapper(), Clock.systemUTC())) {
            CallId c = CallId.of("call-purge");
            LegId f = LegId.of("call-purge-f");
            bus.publish(new ConsoleEvent.CallStarted(c, 1, "hi-IN"));
            bus.publish(new ConsoleEvent.TurnHeard(c, 5, f, LegRole.FARMER, "hi-IN", "synthetic words", 2, 4));
            assertThat(state.activeCalls()).hasSize(1);
            bus.publish(new ConsoleEvent.ConsentRecorded(c, LegRole.FARMER, "NO", 6));
            bus.publish(new ConsoleEvent.CallEnded(c, 7, 6, "hangup"));

            assertThat(state.activeCalls()).isEmpty();
            List<ConsoleEvent> replayed = new ArrayList<>();
            state.subscribe(replayed::add).run();
            assertThat(replayed).isEmpty();
            assertThat(store.count()).isZero();
            assertThat(writer.openCalls()).isZero();
        }
    }
}
