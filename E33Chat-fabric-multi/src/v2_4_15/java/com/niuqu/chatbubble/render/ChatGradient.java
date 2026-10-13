package com.niuqu.chatbubble.render;

import java.util.Arrays;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;

/**
 * Shifts a gradient that is already baked into a chat line.
 * A single fixed colour does not move. The received history is left untouched.
 *
 * Written with Mojang names so the 26.x source rewrite does not rename it twice.
 * Call only from the client thread: the scratch buffer is reused across lines.
 */
public final class ChatGradient {
    private static final class Bag {
        int count;
        int[] codePoints = new int[48];
        int[] rgb = new int[48];
        Style[] styles = new Style[48];

        void reset() {
            count = 0;
        }

        void add(Style style, int codePoint, int color) {
            if (count == codePoints.length) {
                int size = codePoints.length * 2;
                codePoints = Arrays.copyOf(codePoints, size);
                rgb = Arrays.copyOf(rgb, size);
                styles = Arrays.copyOf(styles, size);
            }
            codePoints[count] = codePoint;
            rgb[count] = color;
            styles[count] = style;
            count++;
        }
    }

    private static final Bag BAG = new Bag();

    private ChatGradient() {
    }

    public static FormattedCharSequence flow(FormattedCharSequence line) {
        if (line == null) {
            return FormattedCharSequence.EMPTY;
        }
        Bag bag = BAG;
        bag.reset();
        line.accept((index, style, codePoint) -> {
            TextColor color = style.getColor();
            bag.add(style, codePoint, color == null ? -1 : color.getValue());
            return true;
        });
        int count = bag.count;
        if (count < GradientMath.MIN_RUN) {
            return line;
        }
        int[] starts = new int[count];
        if (!GradientMath.markRuns(bag.rgb, count, starts)) {
            return line;
        }
        int[] codePoints = Arrays.copyOf(bag.codePoints, count);
        Style[] styles = Arrays.copyOf(bag.styles, count);
        int[] painted = new int[count];
        boolean[] flowing = new boolean[count];
        int[] lengths = new int[count];
        float shift = GradientMath.shift(System.currentTimeMillis());
        for (int i = 0; i < count; i++) {
            int origin = starts[i];
            if (origin < 0) {
                continue;
            }
            if (lengths[origin] == 0) {
                lengths[origin] = GradientMath.runLength(starts, count, origin);
            }
            int length = lengths[origin];
            int from = bag.rgb[origin];
            int to = bag.rgb[origin + length - 1];
            flowing[i] = true;
            painted[i] = GradientMath.colorAt(from, to, i - origin, length, shift);
        }
        return (FormattedCharSink sink) -> {
            int place = 0;
            for (int i = 0; i < count; i++) {
                Style style = styles[i];
                if (flowing[i]) {
                    style = style.withColor(TextColor.fromRgb(painted[i]));
                }
                if (!sink.accept(place, style, codePoints[i])) {
                    return false;
                }
                place++;
            }
            return true;
        };
    }
}
