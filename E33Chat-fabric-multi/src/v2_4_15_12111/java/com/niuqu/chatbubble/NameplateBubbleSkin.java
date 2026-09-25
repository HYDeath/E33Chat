package com.niuqu.chatbubble;

import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** A resource-pack bubble at its original pixel size, assembled for the message width. */
final class NameplateBubbleSkin {
    private static final char NEG_ONE = '\uf800';

    private final Identifier font;
    private final char left, right, middle, tail;
    private final float leftAdvance, rightAdvance, middleAdvance, tailAdvance;
    private final int height, ascent;

    private NameplateBubbleSkin(Identifier font, char left, float leftAdvance,
                                char right, float rightAdvance, char middle, float middleAdvance,
                                char tail, float tailAdvance, int height, int ascent) {
        this.font = font;
        this.left = left;
        this.leftAdvance = leftAdvance;
        this.right = right;
        this.rightAdvance = rightAdvance;
        this.middle = middle;
        this.middleAdvance = middleAdvance;
        this.tail = tail;
        this.tailAdvance = tailAdvance;
        this.height = height;
        this.ascent = ascent;
    }

    static NameplateBubbleSkin parse(String spec, int lineCount) {
        if (spec == null || spec.isEmpty() || lineCount < 1 || lineCount > 12) return null;
        try {
            String[] variants = spec.split("\\|", -1);
            if (lineCount >= variants.length || variants[lineCount].isEmpty()) return null;
            String[] fontParts = variants[0].split(":", 2);
            if (fontParts.length != 2) return null;
            Identifier font = Identifier.of(fontParts[0], fontParts[1]);
            String[] parts = variants[lineCount].split(",", -1);
            if (parts.length != 10) return null;
            char[] chars = new char[4];
            float[] advances = new float[4];
            for (int i = 0; i < 4; i++) {
                if (parts[i * 2].length() != 1) return null;
                chars[i] = parts[i * 2].charAt(0);
                advances[i] = Float.parseFloat(parts[i * 2 + 1]);
                if (!Float.isFinite(advances[i]) || advances[i] <= 0) return null;
            }
            int height = Integer.parseInt(parts[8]);
            int ascent = Integer.parseInt(parts[9]);
            if (height < 9 || height > 256 || ascent < 0 || ascent > height) return null;
            if (advances[2] <= 1f) return null;
            return new NameplateBubbleSkin(font, chars[0], advances[0], chars[1], advances[1],
                chars[2], advances[2], chars[3], advances[3], height, ascent);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    int height() { return height; }
    int ascent() { return ascent; }

    Text frame(int textWidth) {
        if (textWidth <= 0) return null;
        // Match BubbleImpl.createImage(advance, 1, 1). Every glyph is drawn 1:1.
        int middleCount = (int) Math.ceil((textWidth + 2f - (tailAdvance - 1f)) / (middleAdvance - 1f));
        if (middleCount > 512) return null;
        StringBuilder image = new StringBuilder(Math.max(8, middleCount * 2 + 6));
        image.append(left).append(NEG_ONE);
        if (middleCount <= 0) {
            image.append(tail).append(NEG_ONE);
        } else {
            for (int i = 0; i < middleCount; i++) {
                image.append(middle).append(NEG_ONE);
                if (i == middleCount / 2) image.append(tail).append(NEG_ONE);
            }
        }
        image.append(right);
        return Text.literal(image.toString())
            .setStyle(Style.EMPTY.withFont(new net.minecraft.text.StyleSpriteSource.Font(font)));
    }
}
