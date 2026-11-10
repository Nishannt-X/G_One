package com.example.g_one.agent.tools.memory;

import com.example.g_one.core.CallId;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.ProfileFact;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Profile facts a farmer stated during a call, held in memory until the call ends. Nothing reaches the
 * {@link MemoryStore} unless the farmer said yes to "may we remember the main points of this call?": then
 * {@link #commit} writes them; otherwise {@link #drop} discards them. A withheld caller ID never gets here (the tool
 * refuses first).
 */
@Component
public class PendingFacts {
    private record Held(CallerKey key, List<ProfileFact> facts) {}

    private final Map<CallId, Held> calls = new ConcurrentHashMap<>();
    private final Supplier<MemoryStore> store;

    @Autowired
    public PendingFacts(ObjectProvider<MemoryStore> store) {
        this(store::getIfAvailable);
    }

    /** @param store gives the store, or null when none is wired */
    public PendingFacts(Supplier<MemoryStore> store) {
        this.store = store;
    }

    public void add(CallId callId, CallerKey key, ProfileFact fact) {
        calls.compute(callId, (id, held) -> {
            List<ProfileFact> facts = new ArrayList<>(held == null ? List.of() : held.facts());
            facts.removeIf(f -> f.field().equals(fact.field()));
            facts.add(fact);
            return new Held(key, List.copyOf(facts));
        });
    }

    /** The facts held for a call, oldest first. */
    public List<ProfileFact> held(CallId callId) {
        Held held = calls.get(callId);
        return held == null ? List.of() : held.facts();
    }

    /** The farmer said yes: writes the call's facts (a forget tombstone in the store still drops them). */
    public int commit(CallId callId) {
        Held held = calls.remove(callId);
        MemoryStore s = store.get();
        if (held == null || held.facts().isEmpty() || s == null) return 0;
        s.upsertFacts(held.key(), held.facts());
        return held.facts().size();
    }

    /** No yes from the farmer: the call's facts are forgotten. */
    public void drop(CallId callId) {
        calls.remove(callId);
    }
}
