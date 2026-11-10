package com.example.g_one.analytics;

import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.memory.MemoryProperties;
import com.example.g_one.memory.SqliteMemoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the call records: the same database as farmer memory, or a private in-memory one when memory is in-memory. */
@Configuration
public class AnalyticsConfig {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsConfig.class);

    /** Takes the memory store so its data directory exists first. Spring closes this on shutdown. */
    @Bean
    CallRecordStore callRecordStore(MemoryProperties props, MemoryStore memoryStore) {
        if (props.store().equals("in-memory")) {
            log.info("Call records: in-memory (lost on restart)");
            return new CallRecordStore("jdbc:sqlite::memory:");
        }
        Path file = Path.of(props.dataDir()).toAbsolutePath().normalize().resolve(SqliteMemoryStore.FILE_NAME);
        return new CallRecordStore("jdbc:sqlite:" + file);
    }

    @Bean
    CallRecordWriter callRecordWriter(ConsoleBus bus, CallRecordStore store, ObjectMapper json) {
        return new CallRecordWriter(bus, store, json, Clock.systemUTC());
    }
}
