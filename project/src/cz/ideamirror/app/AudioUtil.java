package cz.ideamirror.app;

import java.util.Arrays;

/** Pure-Java perceptual volume curve and in-place PCM-16LE attenuation (tested in CoreTest). */
final class AudioUtil {
    static int clampPercent(int percent) {
        if (percent < 0) return 0;
        if (percent > 100) return 100;
        return percent;
    }

    /** Quadratic perceptual gain curve: 0% -> 0.0, 25% -> -24 dB, 50% -> -12 dB, 100% -> 1.0 (0 dB). */
    static float percentToGain(int percent) {
        int p = clampPercent(percent);
        if (p == 0) return 0f;
        if (p == 100) return 1f;
        float norm = p / 100f;
        return norm * norm;
    }

    /** Applies gain in-place to little-endian signed 16-bit PCM samples. */
    static void applyGainPcm16Le(byte[] pcm, int length, int percent) {
        if (pcm == null || length <= 0) return;
        int p = clampPercent(percent);
        if (p >= 100) return;
        int limit = Math.min(length, pcm.length) & ~1;
        if (p == 0) {
            Arrays.fill(pcm, 0, limit, (byte) 0);
            return;
        }
        float gain = percentToGain(p);
        for (int i = 0; i < limit; i += 2) {
            int sample = (pcm[i] & 0xFF) | (pcm[i + 1] << 8);
            int scaled = Math.round(sample * gain);
            if (scaled > 32767) scaled = 32767;
            else if (scaled < -32768) scaled = -32768;
            pcm[i] = (byte) (scaled & 0xFF);
            pcm[i + 1] = (byte) ((scaled >>> 8) & 0xFF);
        }
    }
}
