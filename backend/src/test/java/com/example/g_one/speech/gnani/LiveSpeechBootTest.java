package com.example.g_one.speech.gnani;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.TextToSpeech;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The whole application with a key set: exactly one real speech implementation of each kind. Nothing is called. */
@SpringBootTest(properties = {"g-one.gnani.api-key=test-key-not-a-secret", "g-one.demo.enabled=false",
        "g-one.memory.store=in-memory"})
class LiveSpeechBootTest {
    @Autowired
    private ObjectProvider<SpeechToText> speechToText;
    @Autowired
    private ObjectProvider<TextToSpeech> textToSpeech;

    @Test
    void aKeyWiresPrismaAndTimbre() {
        assertThat(speechToText.getIfUnique()).isInstanceOf(PrismaSpeechToText.class);
        assertThat(textToSpeech.getIfUnique()).isInstanceOf(TimbreTextToSpeech.class);
    }
}
