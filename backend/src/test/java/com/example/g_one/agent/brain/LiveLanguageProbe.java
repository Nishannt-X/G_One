package com.example.g_one.agent.brain;

import com.example.g_one.core.Language;
import com.example.g_one.speech.PrismClient;
import com.example.g_one.speech.TimbreClient;
import com.example.g_one.speech.UpstreamException;
import com.example.g_one.speech.lang.LanguageRoute;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Opt-in: one Timbre call and one Prisma call per configured language, through the real clients. Needs
 * {@code G_ONE_LIVE_PROBE=1} and {@code -Dtest=LiveLanguageProbe}. Writes {@code target/language-probe.txt}.
 */
@EnabledIfEnvironmentVariable(named = "G_ONE_LIVE_PROBE", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "g-one.memory.store=in-memory")
class LiveLanguageProbe {
    private static final Map<String, String> TEXT = Map.of(
            "hi-IN", "नमस्ते, प्याज का भाव क्या है?",
            "en-IN", "Hello, what is the price of onion?",
            "hi-en", "नमस्ते, onion का rate क्या है?",
            "bn-IN", "নমস্কার, পেঁয়াজের দাম কত?",
            "bn-en", "নমস্কার, onion এর rate কত?",
            "ta-IN", "வணக்கம், வெங்காயத்தின் விலை என்ன?",
            "kn-IN", "ನಮಸ್ಕಾರ, ಈರುಳ್ಳಿ ಬೆಲೆ ಎಷ್ಟು?");

    @Autowired TimbreClient timbre;
    @Autowired PrismClient prism;
    @Autowired Language.Registry languages;

    @Test
    void probe() throws Exception {
        List<String> out = new ArrayList<>();
        String only = System.getenv("LIVE_PROBE_ONLY");
        for (Language l : languages.all()) {
            if (only != null && !only.isBlank() && !only.equals(l.code())) continue;
            String text = TEXT.get(l.code());
            if (text == null) {
                out.add(l.code() + " no sample");
                continue;
            }
            LanguageRoute route = LanguageRoute.of(l.code(), false);
            byte[] wav;
            try {
                wav = timbre.synthesize(text, l.voice(), route.ttsLanguage());
            } catch (UpstreamException e) {
                out.add(l.code() + " voice=" + l.voice() + " tts=FAIL http=" + e.status());
                continue;
            }
            String stt;
            try {
                stt = "ok transcript=" + prism.transcribe(wav, route.sttSingle());
            } catch (UpstreamException e) {
                stt = "FAIL http=" + e.status() + " stage=" + e.stage();
            }
            out.add(l.code() + " voice=" + l.voice() + " tts=ok bytes=" + wav.length + " stt=" + stt);
        }
        Files.write(Path.of("target/language-probe.txt"), out);
    }
}
