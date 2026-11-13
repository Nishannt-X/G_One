package com.example.g_one.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.sqlite.SQLiteConfig;

/**
 * Stored call records in the {@code call_records} table. Uses the memory database file ({@code <dataDir>/g-one.db}) on
 * its own connection, or a private in-memory database when the memory store is in-memory. Keeps at most
 * {@link #MAX_RECORDS}; the oldest are deleted first. Every public method is {@code synchronized}.
 *
 * <p>A forgotten caller key leaves a tombstone in {@code call_record_forgets} (kept for good, like the memory store's):
 * {@link #forget} deletes the key's records and, in the same transaction, notes the forget time; {@link #save} of a
 * record for that key from a call that began at or before that time stores it {@link CallRecord#withdrawn() without}
 * the key, stated name, memory snapshot and farmer timeline. A call that begins after the forget may link again.
 */
public class CallRecordStore implements AutoCloseable {
    /** Retention cap: older records beyond this many are deleted. */
    public static final int MAX_RECORDS = 500;

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    /** Rows written at or after this level are already scrubbed by {@link RecordScrubber}. */
    static final int PRIVACY_VERSION = 1;
    private static final String COLUMNS = "call_id, started_ms, ended_ms, duration_ms, end_reason, languages, outcome, "
            + "consent_farmer, consent_buyer, farmer_key_hash, data, created_at, schema_version";

    private final Connection conn;
    private final ObjectMapper json = new ObjectMapper();
    private final int maxRecords;

    /** @param jdbcUrl such as {@code jdbc:sqlite:/path/g-one.db} or {@code jdbc:sqlite::memory:} */
    public CallRecordStore(String jdbcUrl) {
        this(jdbcUrl, MAX_RECORDS);
    }

    CallRecordStore(String jdbcUrl, int maxRecords) {
        this.maxRecords = maxRecords;
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setBusyTimeout(5_000);
        try {
            conn = cfg.createConnection(jdbcUrl);
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS call_records (
                            call_id         TEXT PRIMARY KEY,
                            started_ms      INTEGER NOT NULL,
                            ended_ms        INTEGER NOT NULL,
                            duration_ms     INTEGER NOT NULL,
                            end_reason      TEXT NOT NULL,
                            languages       TEXT NOT NULL,
                            outcome         TEXT NOT NULL,
                            consent_farmer  TEXT NOT NULL,
                            consent_buyer   TEXT NOT NULL,
                            farmer_key_hash TEXT,
                            data            TEXT NOT NULL,
                            created_at      INTEGER NOT NULL
                        )""");
                st.execute("CREATE INDEX IF NOT EXISTS call_records_farmer ON call_records (farmer_key_hash)");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS call_record_forgets (
                            caller_key TEXT PRIMARY KEY,
                            at_ms      INTEGER NOT NULL
                        )""");
                migrate(st);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not open the call records table", e);
        }
    }

    /**
     * Version 1 tables have no {@code schema_version} column: it is added, and their rows read as version 1. Tables
     * before the privacy review have no {@code privacy_version}: it is added as 0, so {@link #scrubLegacy} finds their
     * rows. Adding a column with a default never rewrites or drops a row.
     */
    private static void migrate(Statement st) throws SQLException {
        boolean hasSchema = false;
        boolean hasPrivacy = false;
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(call_records)")) {
            while (rs.next()) {
                if (rs.getString("name").equals("schema_version")) hasSchema = true;
                if (rs.getString("name").equals("privacy_version")) hasPrivacy = true;
            }
        }
        if (!hasSchema) {
            st.execute("ALTER TABLE call_records ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 1");
        }
        if (!hasPrivacy) {
            st.execute("ALTER TABLE call_records ADD COLUMN privacy_version INTEGER NOT NULL DEFAULT 0");
        }
    }

    /**
     * Rewrites every row stored before the privacy review through {@code scrub} once, in one transaction, and marks it
     * scrubbed. @return how many rows were rewritten
     */
    public synchronized int scrubLegacy(UnaryOperator<CallRecord> scrub) {
        List<CallRecord> old;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + COLUMNS + " FROM call_records WHERE privacy_version < ?")) {
            ps.setInt(1, PRIVACY_VERSION);
            old = read(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read call records to scrub", e);
        }
        if (old.isEmpty()) {
            return 0;
        }
        return tx(() -> {
            for (CallRecord r : old) {
                CallRecord clean = scrub.apply(r);
                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE call_records SET data = ?, farmer_key_hash = ?, privacy_version = ?
                        WHERE call_id = ?""")) {
                    ps.setString(1, json.writeValueAsString(clean.data()));
                    ps.setString(2, clean.farmerKey());
                    ps.setInt(3, PRIVACY_VERSION);
                    ps.setString(4, r.callId());
                    ps.executeUpdate();
                }
            }
            return old.size();
        });
    }

    /**
     * Forgets a caller key: deletes its records and leaves a tombstone at {@code atMs}, in one transaction, so a record
     * of a call that began at or before then is saved without anything linked to the caller. Forgetting again keeps
     * the latest time. @return how many records were deleted
     */
    public synchronized int forget(String farmerKey, long atMs) {
        Objects.requireNonNull(farmerKey, "farmerKey");
        return tx(() -> {
            try (PreparedStatement mark = conn.prepareStatement("""
                    INSERT INTO call_record_forgets (caller_key, at_ms) VALUES (?, ?)
                    ON CONFLICT (caller_key) DO UPDATE SET at_ms = max(at_ms, excluded.at_ms)""")) {
                mark.setString(1, farmerKey);
                mark.setLong(2, atMs);
                mark.executeUpdate();
            }
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM call_records WHERE farmer_key_hash = ?")) {
                del.setString(1, farmerKey);
                return del.executeUpdate();
            }
        });
    }

    /** True when this caller key was forgotten at or after a call that began at {@code startedMs}. */
    public synchronized boolean forgotten(String farmerKey, long startedMs) {
        if (farmerKey == null) return false;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM call_record_forgets WHERE caller_key = ? AND ? <= at_ms")) {
            ps.setString(1, farmerKey);
            ps.setLong(2, startedMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the forget tombstones", e);
        }
    }

    private interface Work<T> {
        T run() throws SQLException, JsonProcessingException;
    }

    private <T> T tx(Work<T> work) {
        try {
            conn.setAutoCommit(false);
            try {
                T out = work.run();
                conn.commit();
                return out;
            } catch (SQLException | JsonProcessingException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("A call records transaction failed", e);
        }
    }

    /**
     * Saves a record (replacing one with the same call id) and trims to the retention cap, in one transaction. A
     * record whose caller key was forgotten at or after the call began is saved {@link CallRecord#withdrawn()}.
     */
    public synchronized void save(CallRecord record) {
        Objects.requireNonNull(record, "record");
        tx(() -> {
            insert(record.farmerKey() != null && forgotten(record.farmerKey(), record.startedMs())
                    ? record.withdrawn() : record);
            return null;
        });
    }

    private void insert(CallRecord r) throws SQLException, JsonProcessingException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO call_records (" + COLUMNS
                + ", privacy_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, r.callId());
            ps.setLong(2, r.startedMs());
            ps.setLong(3, r.endedMs());
            ps.setLong(4, r.durationMs());
            ps.setString(5, r.endReason());
            ps.setString(6, json.writeValueAsString(r.languages()));
            ps.setString(7, r.outcome());
            ps.setString(8, r.consentFarmer().name());
            ps.setString(9, r.consentBuyer().name());
            ps.setString(10, r.farmerKey());
            ps.setString(11, json.writeValueAsString(r.data()));
            ps.setLong(12, r.createdAt());
            ps.setInt(13, r.schemaVersion());
            ps.setInt(14, PRIVACY_VERSION);
            ps.executeUpdate();
        }
        try (PreparedStatement trim = conn.prepareStatement("""
                DELETE FROM call_records WHERE call_id NOT IN (
                    SELECT call_id FROM call_records ORDER BY started_ms DESC, call_id DESC LIMIT ?)""")) {
            trim.setInt(1, maxRecords);
            trim.executeUpdate();
        }
    }

    /** Newest call first. */
    public synchronized List<CallRecord> list(int limit) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + COLUMNS + " FROM call_records ORDER BY started_ms DESC, call_id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(0, limit));
            return read(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not list call records", e);
        }
    }

    public synchronized Optional<CallRecord> get(String callId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT " + COLUMNS + " FROM call_records WHERE call_id = ?")) {
            ps.setString(1, callId);
            return read(ps).stream().findFirst();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read a call record", e);
        }
    }

    /** @return true when a record was deleted */
    public synchronized boolean delete(String callId) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM call_records WHERE call_id = ?")) {
            ps.setString(1, callId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not delete a call record", e);
        }
    }

    /** Deletes every record of this farmer (used when the farmer asks to be forgotten). @return how many */
    public synchronized int deleteByFarmerKey(String farmerKey) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM call_records WHERE farmer_key_hash = ?")) {
            ps.setString(1, farmerKey);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not delete a farmer's call records", e);
        }
    }

    public synchronized int count() {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM call_records")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count call records", e);
        }
    }

    private List<CallRecord> read(PreparedStatement ps) throws SQLException {
        List<CallRecord> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    out.add(new CallRecord(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                            rs.getString(5), json.readValue(rs.getString(6), STRING_MAP), rs.getString(7),
                            Consent.parse(rs.getString(8)), Consent.parse(rs.getString(9)), rs.getString(10),
                            json.readTree(rs.getString(11)), rs.getLong(12), rs.getInt(13)));
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("A stored call record is not valid JSON", e);
                }
            }
        }
        return out;
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            // Closing on shutdown; nothing left to do.
        }
    }
}
