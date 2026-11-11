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
import org.sqlite.SQLiteConfig;

/**
 * Stored call records in the {@code call_records} table. Uses the memory database file ({@code <dataDir>/g-one.db}) on
 * its own connection, or a private in-memory database when the memory store is in-memory. Keeps at most
 * {@link #MAX_RECORDS}; the oldest are deleted first. Every public method is {@code synchronized}.
 */
public final class CallRecordStore implements AutoCloseable {
    /** Retention cap: older records beyond this many are deleted. */
    public static final int MAX_RECORDS = 500;

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
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
                migrate(st);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not open the call records table", e);
        }
    }

    /**
     * Version 1 tables have no {@code schema_version} column: it is added, and their rows read as version 1. Adding a
     * column with a default never rewrites or drops a row.
     */
    private static void migrate(Statement st) throws SQLException {
        boolean has = false;
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(call_records)")) {
            while (rs.next()) {
                if (rs.getString("name").equals("schema_version")) has = true;
            }
        }
        if (!has) {
            st.execute("ALTER TABLE call_records ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 1");
        }
    }

    public synchronized void save(CallRecord r) {
        Objects.requireNonNull(r, "record");
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO call_records (" + COLUMNS
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
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
            ps.executeUpdate();
            try (PreparedStatement trim = conn.prepareStatement("""
                    DELETE FROM call_records WHERE call_id NOT IN (
                        SELECT call_id FROM call_records ORDER BY started_ms DESC, call_id DESC LIMIT ?)""")) {
                trim.setInt(1, maxRecords);
                trim.executeUpdate();
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Could not save a call record", e);
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
