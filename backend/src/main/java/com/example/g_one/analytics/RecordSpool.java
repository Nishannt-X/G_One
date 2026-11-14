package com.example.g_one.analytics;

import java.util.List;

/**
 * Finished call records waiting to be stored. The writer puts a record here before it inserts it and removes it after
 * the insert, so a record survives a store outage or a crash and is replayed later. Records are already projected by
 * {@link RecordSchema}: the spool holds nothing a stored row could not. Putting a record with the same call id again
 * replaces it, so a replay is idempotent.
 */
interface RecordSpool {
    /** Keeps the record durably before returning. @throws java.io.UncheckedIOException when it cannot */
    void put(CallRecord record);

    void remove(String callId);

    /** Every waiting record, oldest call first. A spooled entry that cannot be read is skipped and kept. */
    List<CallRecord> all();

    int size();
}
