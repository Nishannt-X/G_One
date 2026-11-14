package com.example.g_one.analytics;

import com.example.g_one.agent.tools.memory.ForgetMeTool;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens to the console events of every call, keeps what a record may need while the call runs, and at
 * {@code call_ended} stores a {@link CallRecord} for it ({@link CallAccumulator}). Every ended call is recorded; the
 * parties' consent decides only the conversation-derived fields, and a call without the farmer's yes is anonymous (no
 * caller key). The synthetic sample replay ({@code replay-} call ids) is never stored.
 *
 * <p>Every record passes {@link RecordSchema} (closed values only) before it is kept anywhere.
 *
 * <p>No call is lost. A record that cannot be built becomes a minimal record. Each record goes to the durable
 * {@link RecordSpool} before the insert and leaves it after; while the store is down records wait in the spool
 * (disk-bound, never dropped) and are replayed when a save succeeds again, on a periodic tick, at startup and at
 * shutdown. Beyond {@link #MAX_OPEN_CALLS} open calls the oldest is recorded early as incomplete ({@code evicted});
 * calls still open at shutdown are recorded as {@code shutdown}.
 *
 * <p>Before any read is served the writer runs the one-time scrub of old rows and applies the forget tombstones
 * ({@link #readiness()}); the controller refuses reads until it is {@link Readiness#READY}.
 *
 * <p>A forget ({@link #forget}) is written to the store first and fails loudly when it cannot be: the console and the
 * "forget me" tool call it before memory forgets. Open calls and spooled records of that caller lose everything
 * linked to them at once.
 */
public final class CallRecordWriter implements AutoCloseable {
    /** Open calls kept before the oldest is recorded early as incomplete. */
    static final int MAX_OPEN_CALLS = 500;
    /** Every this many waiting records, the writer logs an error: the store has been down a long time. */
    static final int LOUD_BACKLOG = 1_000;
    static final long TICK_MS = 1_000;
    static final String EVICTED = "evicted";
    static final String SHUTDOWN = "shutdown";
    /** The call id a memory forget intent carries when the forget came from outside a call (the console). */
    static final String FORGET_INTENT_CALL = "forget-intent";
    /** Quarantined rows are retried at most this often. */
    static final long QUARANTINE_RETRY_MS = 60_000;

    private static final Logger log = LoggerFactory.getLogger(CallRecordWriter.class);

    /** Whether reads may be served: the one-time scrub and the tombstone sweep have run. */
    public enum Readiness { CLEANING, READY, FAILED }

    private final CallRecordStore store;
    private final ObjectMapper json;
    private final Clock clock;
    private final Vocabulary vocab;
    private final RecordSchema schema;
    private final RecordSpool spool;
    /** Completes memory forget intents; null when no memory store is wired (then no intent is recorded). */
    private final MemoryForget memory;
    private final int maxOpen;
    private long nextQuarantineRetryMs;
    private final Map<CallId, CallAccumulator> open = new LinkedHashMap<>();
    /** Records the spool could not take (a disk failure): kept here and spooled again on the next tick. */
    private final List<CallRecord> unspooled = new ArrayList<>();
    private final ConsoleBus.Subscription subscription;
    private final ScheduledExecutorService ticks;
    private volatile Readiness readiness = Readiness.CLEANING;
    private boolean storeDown;
    private int loudAt = LOUD_BACKLOG;
    /** Builds a finished call's record; a test seam for build failures. */
    private BiFunction<CallAccumulator, String, CallRecord> builder;

    public CallRecordWriter(ConsoleBus bus, CallRecordStore store, ObjectMapper json, Clock clock) {
        this(bus, store, json, clock, Vocabulary.bundled(json), new MemoryRecordSpool(), null, MAX_OPEN_CALLS, true);
    }

    CallRecordWriter(ConsoleBus bus, CallRecordStore store, ObjectMapper json, Clock clock, Vocabulary vocab,
                     RecordSpool spool, MemoryForget memory) {
        this(bus, store, json, clock, vocab, spool, memory, MAX_OPEN_CALLS, true);
    }

    /** No memory store: forgets touch the call records only. */
    CallRecordWriter(ConsoleBus bus, CallRecordStore store, ObjectMapper json, Clock clock, Vocabulary vocab,
                     RecordSpool spool, int maxOpen, boolean tick) {
        this(bus, store, json, clock, vocab, spool, null, maxOpen, tick);
    }

    /** @param tick false for tests that drive {@link #tick()} themselves */
    CallRecordWriter(ConsoleBus bus, CallRecordStore store, ObjectMapper json, Clock clock, Vocabulary vocab,
                     RecordSpool spool, MemoryForget memory, int maxOpen, boolean tick) {
        this.store = store;
        this.json = json;
        this.clock = clock;
        this.vocab = vocab;
        this.schema = new RecordSchema(vocab, json);
        this.spool = spool;
        this.memory = memory;
        this.maxOpen = maxOpen;
        this.builder = (call, reason) -> call.toRecord(json, vocab.deals(), clock.millis(), reason);
        synchronized (this) {
            cleanup();
        }
        if (tick) {
            ticks = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "call-record-writer");
                t.setDaemon(true);
                return t;
            });
            ticks.scheduleWithFixedDelay(this::tickQuietly, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        } else {
            ticks = null;
        }
        this.subscription = bus.subscribe(this::accept);
    }

    /** Whether the controller may serve reads. */
    public Readiness readiness() {
        return readiness;
    }

    RecordSchema schema() {
        return schema;
    }

    synchronized void buildWith(BiFunction<CallAccumulator, String, CallRecord> builder) {
        this.builder = builder;
    }

    /**
     * At startup, before any read is served (memory or call records): completes every pending memory forget intent,
     * runs the one-time scrub of old rows, sweeps the tombstones, then replays the spool.
     */
    private void cleanup() {
        completeIntents();
        try {
            CallRecordStore.ScrubReport report = store.scrubLegacy(schema);
            if (report.scrubbed() + report.minimised() > 0) {
                log.info("Call records: scrubbed {} and minimised {} records stored before the privacy review",
                        report.scrubbed(), report.minimised());
            }
            if (report.quarantined() > 0) {
                log.error("Call records: {} old rows kept but quarantined (hidden); retried later", report.quarantined());
            }
            nextQuarantineRetryMs = clock.millis() + QUARANTINE_RETRY_MS;
            int swept = store.applyTombstones();
            if (swept > 0) log.info("Call records: {} records of forgotten callers deleted at startup", swept);
            flush();
            readiness = report.ok() ? Readiness.READY : Readiness.FAILED;
            if (!report.ok()) log.error("Call records: {} old rows could not be cleaned; reads stay closed", report.failed());
        } catch (RuntimeException e) {
            readiness = Readiness.FAILED;
            log.error("Call records: privacy cleanup failed; reads stay closed until it succeeds", e);
        }
    }

    synchronized void accept(ConsoleEvent event) {
        if (event.callId().value().startsWith("replay-")) {
            return;
        }
        if (event instanceof ConsoleEvent.CallStarted started) {
            open.put(started.callId(), new CallAccumulator(started));
            evictBeyondCap();
            return;
        }
        CallAccumulator call = open.get(event.callId());
        if (call == null) {
            return;
        }
        call.accept(event, json);
        if (event instanceof ConsoleEvent.ToolCalled t && t.ok() && t.tool().equals(ForgetMeTool.NAME)) {
            // Normally the tool already told the writer (before memory forgot); this repeat is harmless.
            call.farmerKey().ifPresent(key -> {
                try {
                    forget(key, t.atMs());
                } catch (RuntimeException e) {
                    // Logged in forget(); the call's own record is withdrawn by its accumulator regardless.
                }
            });
        }
        if (event instanceof ConsoleEvent.CallEnded) {
            open.remove(event.callId());
            finish(call, null);
        }
    }

    /**
     * Forgets a caller key: open calls and spooled records of that caller that began at or before {@code atMs} lose
     * everything linked to it, then the store deletes its records and keeps a durable tombstone, in one transaction.
     * Throws when the store cannot take it, so the caller (the console, the "forget me" tool) fails closed.
     */
    public synchronized void forget(String farmerKey, long atMs) {
        stripHeld(farmerKey, atMs);
        try {
            store.forget(farmerKey, atMs);
        } catch (RuntimeException e) {
            log.error("Could not store a forget for call records; the forget is refused", e);
            throw e;
        }
    }

    /**
     * The forget the console and the "forget me" tool ask for, before memory forgets: the call records forget (as
     * {@link #forget}) and, in the same transaction, record a durable intent to forget memory; then the intent is
     * completed at once when it can be. If memory cannot forget now, the intent stays and is completed at the next
     * startup or tick; this does not throw for it, since the call records already committed. Throws only when the call
     * records cannot forget (the caller then refuses the forget).
     */
    public synchronized void forgetNow(String farmerKey) {
        long atMs = clock.millis();
        stripHeld(farmerKey, atMs);
        try {
            if (memory != null) store.forgetWithIntent(farmerKey, FORGET_INTENT_CALL, atMs);
            else store.forget(farmerKey, atMs);
        } catch (RuntimeException e) {
            log.error("Could not store a forget for call records; the forget is refused", e);
            throw e;
        }
        completeIntents();
    }

    /** Completes every pending memory forget intent (idempotent); one that fails stays for the next try. */
    synchronized void completeIntents() {
        if (memory == null) return;
        List<CallRecordStore.ForgetIntent> pending;
        try {
            pending = store.pendingIntents();
        } catch (RuntimeException e) {
            log.error("Could not read the memory forget intents", e);
            return;
        }
        for (CallRecordStore.ForgetIntent intent : pending) {
            try {
                memory.forget(intent.callerKey(), intent.callId(), intent.atMs());
                store.intentDone(intent);
            } catch (RuntimeException e) {
                log.error("A memory forget is still pending; it is retried on the next tick");
            }
        }
    }

    /** Open calls and spooled records of this caller that began at or before the forget lose the linked fields. */
    private void stripHeld(String farmerKey, long atMs) {
        for (CallAccumulator call : open.values()) {
            if (call.farmerKey().filter(farmerKey::equals).isPresent() && call.startedMs() <= atMs) {
                call.withdrawKey();
            }
        }
        for (CallRecord r : spool.all()) {
            if (farmerKey.equals(r.farmerKey()) && r.startedMs() <= atMs) spool.put(r.withdrawn());
        }
        unspooled.replaceAll(r -> farmerKey.equals(r.farmerKey()) && r.startedMs() <= atMs ? r.withdrawn() : r);
    }

    private void finish(CallAccumulator call, String endReasonIfOpen) {
        CallRecord record;
        try {
            record = schema.project(builder.apply(call, endReasonIfOpen));
        } catch (RuntimeException e) {
            log.error("Could not build the record of call {}; storing a minimal record", call.callId());
            record = call.minimal(schema, clock.millis());
        }
        persist(record);
    }

    /** Spool first, then insert; the spool entry goes once the insert succeeded. */
    private void persist(CallRecord record) {
        boolean spooled;
        try {
            spool.put(record);
            spooled = true;
        } catch (RuntimeException e) {
            log.error("Could not spool the record of call {}; kept in memory until the spool works", record.callId());
            unspooled.add(record);
            spooled = false;
        }
        boolean wasDown = storeDown;
        if (save(record)) {
            if (spooled) spool.remove(record.callId());
            else unspooled.remove(record);
            log.info("Call {} ended: record stored (consent: {}{})", record.callId(), record.consentScope(),
                    record.termsOnly() ? ", terms only" : record.farmerKey() == null ? ", anonymous" : "");
            if (wasDown) flush();
        } else {
            warnBacklog();
        }
    }

    private boolean save(CallRecord record) {
        try {
            store.save(record);
            storeDown = false;
            return true;
        } catch (RuntimeException e) {
            if (!storeDown) log.warn("The call records store failed; records wait in the spool", e);
            storeDown = true;
            return false;
        }
    }

    /** Replays everything waiting, oldest first, stopping at the first failure (the store is still down). */
    private void flush() {
        for (Iterator<CallRecord> it = unspooled.iterator(); it.hasNext(); ) {
            try {
                spool.put(it.next());
                it.remove();
            } catch (RuntimeException e) {
                break;
            }
        }
        for (CallRecord r : spool.all()) {
            if (!save(r)) {
                warnBacklog();
                return;
            }
            spool.remove(r.callId());
        }
        loudAt = LOUD_BACKLOG;
    }

    private void warnBacklog() {
        int waiting = pendingRecords();
        if (waiting >= loudAt) {
            log.error("{} finished call records are waiting for the store (kept on disk, none dropped)", waiting);
            loudAt += LOUD_BACKLOG;
        }
    }

    /** Records the oldest open calls early, as incomplete, while more than the cap are open. Never drops one. */
    private void evictBeyondCap() {
        Iterator<Map.Entry<CallId, CallAccumulator>> it = open.entrySet().iterator();
        while (open.size() > maxOpen && it.hasNext()) {
            CallAccumulator oldest = it.next().getValue();
            it.remove();
            log.warn("More than {} calls open: recording call {} early as incomplete", maxOpen, oldest.callId());
            finish(oldest, EVICTED);
        }
    }

    /**
     * The periodic work: complete pending memory forget intents, finish a failed cleanup or retry quarantined rows (at
     * most every {@link #QUARANTINE_RETRY_MS}), then replay what waits.
     */
    synchronized void tick() {
        completeIntents();
        boolean quarantineDue = clock.millis() >= nextQuarantineRetryMs && store.pendingScrub() > 0;
        if (readiness != Readiness.READY || quarantineDue) cleanup();
        else flush();
    }

    private void tickQuietly() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.error("Call record writer tick failed", e);
        }
    }

    /** Records waiting to be stored (spooled, or held in memory while the spool fails). */
    public synchronized int pendingRecords() {
        return spool.size() + unspooled.size();
    }

    synchronized int openCalls() {
        return open.size();
    }

    @Override
    public void close() {
        subscription.close();
        if (ticks != null) ticks.shutdownNow();
        synchronized (this) {
            // Calls still open at shutdown are recorded as they stand, rather than lost.
            for (CallAccumulator call : List.copyOf(open.values())) {
                finish(call, SHUTDOWN);
            }
            open.clear();
            flush();
            int waiting = pendingRecords();
            if (waiting > 0) {
                log.error("Shutting down with {} call records not yet stored; {} of them stay spooled for the next start",
                        waiting, spool.size());
            }
        }
    }
}
