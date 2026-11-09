package com.example.g_one.speech.gnani;

import com.example.g_one.GOneProperties;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.TextToSpeech;
import com.example.g_one.speech.PrismClient;
import com.example.g_one.speech.SpeechProperties;
import com.example.g_one.speech.TimbreClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Real Gnani speech: only with an API key and the offline demo off, so the app still boots with no keys at all. */
@Configuration
@Conditional(LiveSpeechConfig.LiveSpeech.class)
public class LiveSpeechConfig {

    static boolean live(Environment env) {
        return !demo(env) && StringUtils.hasText(env.getProperty("g-one.gnani.api-key"));
    }

    private static boolean demo(Environment env) {
        return env.getProperty("g-one.demo.enabled", Boolean.class, false);
    }

    @Bean
    PrismaSpeechToText prismaSpeechToText(GOneProperties props, SpeechProperties settings, PrismClient prism, ObjectMapper json) {
        return new PrismaSpeechToText(props, settings, prism, json);
    }

    @Bean
    TextToSpeech timbreTextToSpeech(GOneProperties props, SpeechProperties settings, TimbreClient timbre, ObjectMapper json) {
        return new TimbreTextToSpeech(props, settings, timbre, json);
    }

    static class LiveSpeech implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return live(context.getEnvironment());
        }
    }

    /** Says at startup which speech the app has, and what to do when it has none. */
    @Component
    static class Status {
        private static final Logger log = LoggerFactory.getLogger(Status.class);

        Status(Environment env) {
            if (demo(env)) {
                log.info("Speech: offline demo (G_ONE_DEMO_MODE=true). Turns are typed and no speech service is called.");
            } else if (live(env)) {
                log.info("Speech: Gnani Prisma and Timbre.");
            } else {
                log.warn("Speech: keys missing. GNANI_API_KEY is not set, so calls will not be answered. "
                        + "Set it for live speech, or set G_ONE_DEMO_MODE=true for the offline demo.");
            }
        }
    }
}
