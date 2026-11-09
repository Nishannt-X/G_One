package com.example.g_one.speech.turn;

import com.example.g_one.speech.audio.Pcm;

/**
 * Decides where a person's turn starts and ends from the level of short audio windows. A window counts as voiced when
 * it is above both a fixed threshold and a multiple of the background level. The background estimate falls at once to
 * any quieter window and creeps up slowly, so a constant hum stops counting as speech after a few seconds while the
 * natural dips in speech keep the estimate low. A turn starts after {@code minSpeechMs} of voiced audio and ends after
 * {@code hangoverMs} of silence, or at {@code maxTurnMs} (Prisma REST takes at most 30 seconds). A short turn ends after
 * only {@code shortHangoverMs}, so "haan, bech do" gets its answer sooner. Not thread-safe.
 */
public final class EnergyVad {

    /**
     * @param windowMs length of each window given to {@link #process}
     * @param thresholdDbfs fixed level a voiced window must reach
     * @param noiseRatio how many times above the background a voiced window must be; 0 turns the background off
     * @param floorRiseMs time constant of the background estimate rising
     * @param shortTurnMs a turn no longer than this counts as a short phrase
     * @param shortHangoverMs silence that ends a short phrase
     */
    public record Config(int windowMs, double thresholdDbfs, double noiseRatio, int floorRiseMs, int minSpeechMs,
                         int hangoverMs, int maxTurnMs, int shortTurnMs, int shortHangoverMs) {
        public Config {
            if (windowMs <= 0 || minSpeechMs < 0 || hangoverMs <= 0 || maxTurnMs <= 0 || floorRiseMs <= 0 || noiseRatio < 0
                    || shortTurnMs < 0 || shortHangoverMs <= 0 || shortHangoverMs > hangoverMs) {
                throw new IllegalArgumentException("Invalid turn detection settings");
            }
        }

        public static Config defaults() {
            return new Config(20, -42, 3, 4000, 120, 600, 29_000, 1200, 350);
        }
    }

    public enum Event { NONE, SPEECH_START, SPEECH_END }

    private final Config config;
    private final double threshold;
    private double floor;
    private boolean speaking;
    private int candidateMs;
    private int silenceMs;
    private int turnMs;
    private int lastSilenceMs;

    public EnergyVad(Config config) {
        this.config = config;
        this.threshold = Math.pow(10, config.thresholdDbfs() / 20);
        this.floor = config.noiseRatio() > 0 ? threshold / config.noiseRatio() : 0;
    }

    /** Takes the next window, PCM16 little-endian of {@code windowMs}, and reports whether a turn just started or ended. */
    public Event process(byte[] window) {
        boolean voiced = isVoiced(Pcm.rms(window));
        int step = config.windowMs();
        if (!speaking) {
            candidateMs = voiced ? candidateMs + step : Math.max(0, candidateMs - step);
            if (candidateMs < Math.max(step, config.minSpeechMs())) {
                return Event.NONE;
            }
            speaking = true;
            turnMs = candidateMs;
            silenceMs = 0;
            candidateMs = 0;
            return Event.SPEECH_START;
        }
        turnMs += step;
        silenceMs = voiced ? 0 : silenceMs + step;
        int spokenMs = turnMs - silenceMs;
        int hangover = spokenMs <= config.shortTurnMs() ? config.shortHangoverMs() : config.hangoverMs();
        if (silenceMs < hangover && turnMs < config.maxTurnMs()) {
            return Event.NONE;
        }
        speaking = false;
        lastSilenceMs = silenceMs;
        silenceMs = 0;
        turnMs = 0;
        return Event.SPEECH_END;
    }

    private boolean isVoiced(double rms) {
        if (config.noiseRatio() > 0) {
            floor = rms < floor ? rms : floor + (rms - floor) * config.windowMs() / config.floorRiseMs();
        }
        return rms >= Math.max(threshold, floor * config.noiseRatio());
    }

    public boolean inSpeech() {
        return speaking;
    }

    /** How much silence closed the last turn, in milliseconds. */
    public int lastTrailingSilenceMs() {
        return lastSilenceMs;
    }
}
