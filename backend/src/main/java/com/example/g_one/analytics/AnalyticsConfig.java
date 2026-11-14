package com.example.g_one.analytics;

import com.example.g_one.agent.tools.buyers.BuyerDirectory;
import com.example.g_one.agent.tools.mandi.MandiCatalog;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.Language;
import com.example.g_one.core.Tool;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.memory.ForgetListener;
import com.example.g_one.memory.MemoryProperties;
import com.example.g_one.memory.SqliteMemoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the call records: the same database as farmer memory, or a private in-memory one when memory is in-memory. */
@Configuration
public class AnalyticsConfig {
    /** The spool of records waiting for the store, under the (gitignored) data directory. */
    static final String SPOOL_DIR = "call-record-spool";
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

    /**
     * A farmer forgotten from the console loses their stored call records too, and leaves a tombstone so a call of
     * theirs that is still open is recorded without anything linked to them.
     */
    @Bean
    ForgetListener callRecordsForget(CallRecordWriter writer) {
        return key -> writer.forgetNow(key.value());
    }

    /**
     * Tool names come from the registered tool beans, languages from the language registry: the closed sets every
     * stored string is mapped to. Records wait in a spool under the data directory (or in memory, when the store is).
     */
    @Bean
    CallRecordWriter callRecordWriter(ConsoleBus bus, CallRecordStore store, ObjectMapper json, MandiCatalog catalog,
                                      BuyerDirectory buyers, ObjectProvider<Tool> tools, Language.Registry languages,
                                      MemoryProperties props) {
        List<String> toolNames = tools.orderedStream().map(Tool::name).toList();
        List<String> codes = languages.all().stream().map(Language::code).toList();
        Vocabulary vocab = new Vocabulary(new DealVocabulary(catalog, buyers), toolNames, codes);
        RecordSpool spool = props.store().equals("in-memory") ? new MemoryRecordSpool()
                : new FileRecordSpool(Path.of(props.dataDir()).toAbsolutePath().normalize().resolve(SPOOL_DIR), json);
        return new CallRecordWriter(bus, store, json, Clock.systemUTC(), vocab, spool);
    }
}
