package com.example.g_one.speech;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for live speech ({@code g-one.speech.*} in application.yml).
 *
 * @param sttMode {@code auto} tries Prisma realtime and falls back to our own turn detection with Prisma REST;
 *                {@code rest} goes straight to the fallback
 * @param vadThreshold Prisma's speech probability threshold, 0 to 1
 * @param minSpeechMs speech Prisma needs before it counts as a start
 * @param minSilenceMs silence that closes an utterance on the Prisma side
 * @param idleTimeoutSec Prisma closes the socket after this long without speech
 * @param maxSpeechSec longest single utterance Prisma will keep open
 * @param bargeInMinSpeechMs speech needed before the assistant is interrupted, so that "haan" does not cut it off
 * @param continuationHoldMs extra wait after an utterance that ends on a word like "aur" or "lekin"
 * @param maxHoldMs longest time finished words are held back while waiting for more
 * @param vadThresholdDbfs level our fallback turn detection counts as voice
 * @param vadHangoverMs silence that ends a turn in our fallback turn detection
 * @param vadShortHangoverMs the same, for a short phrase
 * @param vadShortTurnMs a turn this long or shorter counts as a short phrase
 * @param sessionRotateMinutes replace the Prisma socket this often; Prisma caps a session at 15 minutes
 * @param reconnectBaseMs wait before the first reconnect; it doubles after each failure
 * @param ttsFirstAudioTimeoutMs how long Timbre may stay silent before the attempt is treated as failed
 */
@ConfigurationProperties("g-one.speech")
public record SpeechProperties(
        @DefaultValue("auto") String sttMode,
        @DefaultValue("0.7") double vadThreshold,
        @DefaultValue("200") int minSpeechMs,
        @DefaultValue("400") int minSilenceMs,
        @DefaultValue("180") int idleTimeoutSec,
        @DefaultValue("30") int maxSpeechSec,
        @DefaultValue("700") int bargeInMinSpeechMs,
        @DefaultValue("1200") int continuationHoldMs,
        @DefaultValue("8000") int maxHoldMs,
        @DefaultValue("-42") double vadThresholdDbfs,
        @DefaultValue("600") int vadHangoverMs,
        @DefaultValue("350") int vadShortHangoverMs,
        @DefaultValue("1200") int vadShortTurnMs,
        @DefaultValue("14") int sessionRotateMinutes,
        @DefaultValue("250") int reconnectBaseMs,
        @DefaultValue("5000") int ttsFirstAudioTimeoutMs) {

    public boolean restOnly() {
        return "rest".equalsIgnoreCase(sttMode);
    }

    public static SpeechProperties defaults() {
        return new SpeechProperties("auto", 0.7, 200, 400, 180, 30, 700, 1200, 8000, -42, 600, 350, 1200, 14, 250, 5000);
    }
}
