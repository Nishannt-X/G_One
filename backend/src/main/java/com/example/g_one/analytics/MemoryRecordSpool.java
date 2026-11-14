package com.example.g_one.analytics;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The spool of a call records store that is itself in memory: as durable as that store, and no more. */
final class MemoryRecordSpool implements RecordSpool {
    private final Map<String, CallRecord> records = new LinkedHashMap<>();

    @Override
    public synchronized void put(CallRecord record) {
        records.put(record.callId(), record);
    }

    @Override
    public synchronized void remove(String callId) {
        records.remove(callId);
    }

    @Override
    public synchronized List<CallRecord> all() {
        return records.values().stream().sorted(Comparator.comparingLong(CallRecord::startedMs)).toList();
    }

    @Override
    public synchronized int size() {
        return records.size();
    }
}
