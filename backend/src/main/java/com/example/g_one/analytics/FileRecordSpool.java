package com.example.g_one.analytics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The spool on disk: one small JSON file per waiting record in {@code <dataDir>/call-record-spool/}, named by a digest
 * of the call id (so the name carries no text and a second put replaces the first). A file is written to a temporary
 * name, forced to disk, then renamed into place, so a crash leaves either the old file, the new one, or a temporary
 * file that is ignored and cleaned up. Disk-bound: it never drops a record.
 */
final class FileRecordSpool implements RecordSpool {
    private static final Logger log = LoggerFactory.getLogger(FileRecordSpool.class);
    private static final String SUFFIX = ".json";
    private static final String TEMP = ".tmp";
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final Path dir;
    private final ObjectMapper json;

    FileRecordSpool(Path dir, ObjectMapper json) {
        this.dir = dir;
        this.json = json;
        try {
            Files.createDirectories(dir);
            try (DirectoryStream<Path> temps = Files.newDirectoryStream(dir, "*" + TEMP)) {
                for (Path t : temps) Files.deleteIfExists(t);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open the call record spool", e);
        }
    }

    @Override
    public synchronized void put(CallRecord r) {
        Path target = dir.resolve(name(r.callId()) + SUFFIX);
        Path temp = dir.resolve(name(r.callId()) + TEMP);
        try {
            ObjectNode n = json.createObjectNode();
            n.put("callId", r.callId());
            n.put("startedMs", r.startedMs());
            n.put("endedMs", r.endedMs());
            n.put("durationMs", r.durationMs());
            n.put("endReason", r.endReason());
            n.set("languages", json.valueToTree(r.languages()));
            n.put("outcome", r.outcome());
            n.put("consentFarmer", r.consentFarmer().name());
            n.put("consentBuyer", r.consentBuyer().name());
            n.put("farmerKey", r.farmerKey());
            n.set("data", r.data());
            n.put("createdAt", r.createdAt());
            n.put("schemaVersion", r.schemaVersion());
            byte[] bytes = json.writeValueAsBytes(n);
            try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) ch.write(buf);
                ch.force(true);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot spool a call record", e);
        }
    }

    @Override
    public synchronized void remove(String callId) {
        try {
            Files.deleteIfExists(dir.resolve(name(callId) + SUFFIX));
        } catch (IOException e) {
            // Left for the next replay, which is idempotent.
            log.warn("Could not remove a spooled call record; it will be replayed again");
        }
    }

    @Override
    public synchronized List<CallRecord> all() {
        List<CallRecord> out = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
            for (Path f : files) {
                try {
                    JsonNode n = json.readTree(Files.readAllBytes(f));
                    out.add(new CallRecord(n.get("callId").asText(), n.path("startedMs").asLong(),
                            n.path("endedMs").asLong(), n.path("durationMs").asLong(), n.path("endReason").asText(""),
                            json.convertValue(n.path("languages"), STRING_MAP), n.path("outcome").asText("no_terms"),
                            Consent.parse(n.path("consentFarmer").asText(null)),
                            Consent.parse(n.path("consentBuyer").asText(null)),
                            n.path("farmerKey").isTextual() ? n.get("farmerKey").asText() : null, n.path("data"),
                            n.path("createdAt").asLong(), n.path("schemaVersion").asInt(CallRecord.SCHEMA_VERSION)));
                } catch (IOException | RuntimeException e) {
                    log.error("A spooled call record cannot be read; it is kept for inspection: {}", f.getFileName());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the call record spool", e);
        }
        out.sort(Comparator.comparingLong(CallRecord::startedMs));
        return out;
    }

    @Override
    public synchronized int size() {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
            int n = 0;
            for (Path ignored : files) n++;
            return n;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the call record spool", e);
        }
    }

    private static String name(String callId) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(callId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
