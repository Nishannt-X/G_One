package com.example.g_one.analytics;

import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens to the console events of every call, keeps what a record may need while the call runs, and at
 * {@code call_ended} stores a {@link CallRecord} according to the parties' consent ({@link CallAccumulator}). Calls
 * with nobody's yes leave nothing behind: their collected data is dropped at the end of the call. The synthetic sample
 * replay ({@code replay-} call ids) is never stored.
 */
public final class CallRecordWriter implements AutoCloseable {
    /** Bounds memory if a call never ends: the oldest open call is dropped beyond this many. */
    static final int MAX_OPEN_CALLS = 50;

    private static final Logger log = LoggerFactory.getLogger(CallRecordWriter.class);

    private final CallRecordStore store;
    private final ObjectMapper json;
    private final Clock clock;
    private final Map<CallId, CallAccumulator> open = new LinkedHashMap<>();
    private final ConsoleBus.Subscription subscription;

    public CallRecordWriter(ConsoleBus bus, CallRecordStore store, ObjectMapper json, Clock clock) {
        this.store = store;
        this.json = json;
        this.clock = clock;
        this.subscription = bus.subscribe(this::accept);
    }

    synchronized void accept(ConsoleEvent event) {
        if (event.callId().value().startsWith("replay-")) {
            return;
        }
        if (event instanceof ConsoleEvent.CallStarted started) {
            open.put(started.callId(), new CallAccumulator(started));
            evictOldest();
            return;
        }
        CallAccumulator call = open.get(event.callId());
        if (call == null) {
            return;
        }
        call.accept(event, json);
        if (event instanceof ConsoleEvent.CallEnded) {
            open.remove(event.callId());
            finish(call);
        }
    }

    private void finish(CallAccumulator call) {
        try {
            if (call.forgot()) {
                // The farmer asked to be forgotten: their earlier records go too.
                call.farmerKey().ifPresent(store::deleteByFarmerKey);
            }
            Optional<CallRecord> record = call.toRecord(json, clock.millis());
            record.ifPresent(store::save);
            log.info("Call {} ended: {}", call.callId(), record.isPresent() ? "record stored" : "nothing stored (no consent)");
        } catch (RuntimeException e) {
            log.warn("Could not store the record of call {}", call.callId(), e);
        }
    }

    private void evictOldest() {
        Iterator<CallId> it = open.keySet().iterator();
        while (open.size() > MAX_OPEN_CALLS && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    synchronized int openCalls() {
        return open.size();
    }

    @Override
    public void close() {
        subscription.close();
    }
}
