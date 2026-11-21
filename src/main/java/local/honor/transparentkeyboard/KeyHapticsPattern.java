package local.honor.transparentkeyboard;

/**
 * Pure timing/amplitude builder for key haptics. No Android types, so it can be checked with javac.
 *
 * {@code VibrationEffect.createWaveform(long[] timings, int repeat)} treats even indexes as OFF
 * and odd indexes as ON, so the first value is a delay before the vibrator turns on.
 * Use {@code [0, dur, gap, dur, ...]} for that overload.
 *
 * {@code createWaveform(long[] timings, int[] amplitudes, int repeat)} pairs each duration with
 * an explicit amplitude, so {@code [dur, gap, dur]} / {@code [amp, 0, amp]} is correct there.
 * Repeat is always -1 (no loop).
 */
final class KeyHapticsPattern {
    static final int REPEAT_NONE = -1;
    /** Same value as {@code VibrationEffect.DEFAULT_AMPLITUDE}. */
    static final int DEFAULT_AMPLITUDE = -1;
    static final int POLICY_BLOCK = 0;
    static final int POLICY_ALLOW = 1;
    static final int POLICY_UNKNOWN = -1;

    final boolean oneShot;
    final long oneShotDurationMs;
    final int oneShotAmplitude;
    final long[] timings;
    final int[] amplitudes;

    private KeyHapticsPattern(boolean oneShot, long oneShotDurationMs, int oneShotAmplitude, long[] timings, int[] amplitudes) {
        this.oneShot = oneShot;
        this.oneShotDurationMs = oneShotDurationMs;
        this.oneShotAmplitude = oneShotAmplitude;
        this.timings = timings;
        this.amplitudes = amplitudes;
    }

    /** 0% maps to amplitude 1 (minimum), not off. */
    static int amplitudeFromPercent(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        return Math.max(1, Math.min(255, Math.round(p * 2.55f)));
    }

    static boolean shouldPlayCustom(int policy) { return policy == POLICY_ALLOW; }
    static boolean shouldSuppressOriginal(int policy) { return policy == POLICY_BLOCK; }

    /**
     * @param pulses 1, 2 or 3
     * @param amp 1-255, used only when hasAmp is true
     */
    static KeyHapticsPattern create(int durMs, int gapMs, int pulses, int amp, boolean hasAmp) {
        int dur = Math.max(5, Math.min(80, durMs));
        int gap = Math.max(20, Math.min(120, gapMs));
        int n = pulses <= 1 ? 1 : (pulses == 2 ? 2 : 3);
        int a = Math.max(1, Math.min(255, amp));
        if (n == 1) {
            return new KeyHapticsPattern(true, dur, hasAmp ? a : DEFAULT_AMPLITUDE, null, null);
        }
        if (hasAmp) {
            long[] timings = new long[n * 2 - 1];
            int[] amplitudes = new int[timings.length];
            for (int i = 0; i < n; i++) {
                int idx = i * 2;
                timings[idx] = dur;
                amplitudes[idx] = a;
                if (i < n - 1) {
                    timings[idx + 1] = gap;
                    amplitudes[idx + 1] = 0;
                }
            }
            return new KeyHapticsPattern(false, 0, 0, timings, amplitudes);
        }
        // no-amplitude overload: first segment is OFF delay, then ON/OFF...
        long[] timings = new long[n * 2];
        timings[0] = 0;
        for (int i = 0; i < n; i++) {
            timings[i * 2 + 1] = dur;
            if (i < n - 1) timings[i * 2 + 2] = gap;
        }
        return new KeyHapticsPattern(false, 0, 0, timings, null);
    }
}
