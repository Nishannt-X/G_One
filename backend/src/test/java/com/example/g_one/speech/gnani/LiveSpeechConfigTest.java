package com.example.g_one.speech.gnani;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.TextToSpeech;
import com.example.g_one.speech.SpeechProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

/** Real speech beans exist only with a key and the demo off. */
class LiveSpeechConfigTest {

    @Configuration
    @EnableConfigurationProperties(SpeechProperties.class)
    static class Stubs {
        @Bean
        org.springframework.web.client.RestClient unused() {
            return org.springframework.web.client.RestClient.create();
        }
    }

    @Test
    void liveOnlyWithAKeyAndNoDemo() {
        assertThat(LiveSpeechConfig.live(new MockEnvironment())).isFalse();
        assertThat(LiveSpeechConfig.live(new MockEnvironment().withProperty("g-one.gnani.api-key", ""))).isFalse();
        assertThat(LiveSpeechConfig.live(new MockEnvironment().withProperty("g-one.gnani.api-key", "   "))).isFalse();
        assertThat(LiveSpeechConfig.live(new MockEnvironment().withProperty("g-one.gnani.api-key", "k"))).isTrue();
        assertThat(LiveSpeechConfig.live(new MockEnvironment().withProperty("g-one.gnani.api-key", "k")
                .withProperty("g-one.demo.enabled", "true"))).isFalse();
    }

    @Test
    void noKeysMeansNoSpeechBeansAndABootingContext() {
        new ApplicationContextRunner().withUserConfiguration(Stubs.class, LiveSpeechConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SpeechToText.class);
            assertThat(context).doesNotHaveBean(TextToSpeech.class);
        });
    }

    @Test
    void settingsHaveDefaultsWithoutAnyConfiguration() {
        new ApplicationContextRunner().withUserConfiguration(Stubs.class).run(context -> {
            SpeechProperties settings = context.getBean(SpeechProperties.class);
            assertThat(settings).isEqualTo(SpeechProperties.defaults());
            assertThat(settings.restOnly()).isFalse();
        });
    }
}
