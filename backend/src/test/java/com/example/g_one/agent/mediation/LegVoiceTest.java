package com.example.g_one.agent.mediation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.llm.FallbackLines;
import com.example.g_one.core.CallId;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.fakes.FakeTextToSpeech;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The mediation voice never sends model thinking to speech synthesis. */
class LegVoiceTest {
    @Test
    void thinkingIsStrippedAndALineOfOnlyThinkingBecomesTheApology() {
        FakeTextToSpeech tts = new FakeTextToSpeech();
        FakeLeg leg = FakeLeg.farmer(CallId.random());
        LegVoice voice = new LegVoice(leg, tts);

        voice.say(new LegVoice.Line("<think>translate this</think>Buyer 2400 de rahe hain.", FakeLeg.HINDI, false, null));
        leg.finishPlayback();
        voice.say(new LegVoice.Line("<reasoning>cut off", FakeLeg.HINDI, false, null));

        assertThat(tts.requests()).extracting(FakeTextToSpeech.Request::text).containsExactly(
                "Buyer 2400 de rahe hain.", new FallbackLines(new ObjectMapper()).apology("hi-IN"));
    }
}
