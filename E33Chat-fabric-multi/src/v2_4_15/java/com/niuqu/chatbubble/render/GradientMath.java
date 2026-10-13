package com.niuqu.chatbubble.render;

import java.util.Arrays;

/**
 * Colour-run detection for a chat line that is already a smooth gradient.
 * No Minecraft types, so the rules can be checked outside the game.
 *
 * A run flows only when it has at least four glyphs, neighbouring channels
 * stay within 110, and the first and last glyphs differ by more than 36.
 * The run then loops once every 2400 ms.
 */
public final class GradientMath {
    public static final int PERIOD_MS = 2400;
    public static final int SMOOTH_LIMIT = 110;
    public static final int DIFFER_LIMIT = 36;
    public static final int MIN_RUN = 4;

    private GradientMath() {
    }

    public static float shift(long nowMillis) {
        return (nowMillis % PERIOD_MS) / (float) PERIOD_MS;
    }

    /** {@code starts[i]} becomes the run origin, or -1 when glyph i stays put. */
    public static boolean markRuns(int[] rgb, int count, int[] starts) {
        Arrays.fill(starts, 0, count, -1);
        boolean any = false;
        int origin = 0;
        while (origin < count) {
            int end = origin + 1;
            while (end < count && smooth(rgb[end - 1], rgb[end])) {
                end++;
            }
            int size = end - origin;
            if (size >= MIN_RUN && rgb[origin] >= 0 && differs(rgb[origin], rgb[end - 1])) {
                for (int i = origin; i < end; i++) {
                    starts[i] = origin;
                }
                any = true;
            }
            origin = Math.max(end, origin + 1);
        }
        return any;
    }

    public static int runLength(int[] starts, int count, int index) {
        int origin = starts[index];
        int end = index + 1;
        while (end < count && starts[end] == origin) {
            end++;
        }
        return end - origin;
    }

    public static int colorAt(int from, int to, int indexInRun, int runLength, float shift) {
        float t = (indexInRun / (float) Math.max(1, runLength - 1) + shift) % 1f;
        if (t < 0f) {
            t += 1f;
        }
        return lerp(from, to, t);
    }

    public static boolean smooth(int left, int right) {
        if (left < 0 || right < 0) {
            return false;
        }
        return channel(left, 16) - channel(right, 16) <= SMOOTH_LIMIT
                && channel(left, 8) - channel(right, 8) <= SMOOTH_LIMIT
                && channel(left, 0) - channel(right, 0) <= SMOOTH_LIMIT
                && channel(right, 16) - channel(left, 16) <= SMOOTH_LIMIT
                && channel(right, 8) - channel(left, 8) <= SMOOTH_LIMIT
                && channel(right, 0) - channel(left, 0) <= SMOOTH_LIMIT;
    }

    public static boolean differs(int left, int right) {
        return Math.abs(channel(left, 16) - channel(right, 16))
                + Math.abs(channel(left, 8) - channel(right, 8))
                + Math.abs(channel(left, 0) - channel(right, 0)) > DIFFER_LIMIT;
    }

    public static int channel(int rgb, int shift) {
        return (rgb >> shift) & 0xFF;
    }

    public static int lerp(int from, int to, float t) {
        int red = mix(channel(from, 16), channel(to, 16), t);
        int green = mix(channel(from, 8), channel(to, 8), t);
        int blue = mix(channel(from, 0), channel(to, 0), t);
        return (red << 16) | (green << 8) | blue;
    }

    private static int mix(int from, int to, float t) {
        return Math.max(0, Math.min(255, Math.round(from + (to - from) * t)));
    }
}
