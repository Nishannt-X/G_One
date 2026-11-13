package com.example.g_one.analytics;

import com.example.g_one.memory.MemoryController;
import com.example.g_one.memory.MemoryProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The stored call records for the Analytics page: {@code GET /api/analytics/calls}, {@code GET} and {@code DELETE}
 * {@code /api/analytics/calls/{id}} and {@code GET /api/analytics/summary}. Behind the same access code as farmer
 * memory, since a record can hold a consenting farmer's profile facts.
 */
@RestController
public class AnalyticsController {
    static final int DEFAULT_LIMIT = 100;

    private final CallRecordStore store;
    private final MemoryProperties props;
    private final CallRecordWriter writer;

    public AnalyticsController(CallRecordStore store, MemoryProperties props, CallRecordWriter writer) {
        this.store = store;
        this.props = props;
        this.writer = writer;
    }

    @GetMapping("/api/analytics/calls")
    public ResponseEntity<?> calls(@RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
                                   @RequestHeader(value = MemoryController.HEADER, required = false) String code) {
        if (!allowed(code)) {
            return denied();
        }
        int bounded = Math.max(1, Math.min(limit, CallRecordStore.MAX_RECORDS));
        List<CallRecord.Summary> rows = store.list(bounded).stream().map(CallRecord::summary).toList();
        return ResponseEntity.ok(Map.of("calls", rows));
    }

    @GetMapping("/api/analytics/calls/{id}")
    public ResponseEntity<?> call(@PathVariable String id,
                                  @RequestHeader(value = MemoryController.HEADER, required = false) String code) {
        if (!allowed(code)) {
            return denied();
        }
        return store.get(id).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "not_found")));
    }

    @DeleteMapping("/api/analytics/calls/{id}")
    public ResponseEntity<?> delete(@PathVariable String id,
                                    @RequestHeader(value = MemoryController.HEADER, required = false) String code) {
        if (!allowed(code)) {
            return denied();
        }
        return store.delete(id) ? ResponseEntity.noContent().build()
                : ResponseEntity.status(404).body(Map.of("error", "not_found"));
    }

    @GetMapping("/api/analytics/summary")
    public ResponseEntity<?> summary(@RequestHeader(value = MemoryController.HEADER, required = false) String code) {
        if (!allowed(code)) {
            return denied();
        }
        return ResponseEntity.ok(AnalyticsSummary.of(store.list(CallRecordStore.MAX_RECORDS),
                writer.pendingRecords()));
    }

    private static ResponseEntity<?> denied() {
        return ResponseEntity.status(401).body(Map.of("error", "access_code_required"));
    }

    private boolean allowed(String given) {
        if (!props.accessCheckEnabled()) {
            return true;
        }
        if (given == null) {
            return false;
        }
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                props.accessCode().getBytes(StandardCharsets.UTF_8));
    }
}
