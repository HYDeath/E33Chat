package com.niuqu.chatbubble;

import com.niuqu.chatbubble.ui.ChatEmojiPanel;
import net.minecraft.text.FontDescription;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/** Draw bundled color Emoji with white tint while preserving surrounding text styles. */
public final class ColorEmojiText {
    private static final FontDescription.Resource FONT =
        new FontDescription.Resource(Identifier.of("e33chat", "emoji"));

    private ColorEmojiText() {}

    public static Text decorate(Text input) {
        MutableText result = Text.empty();
        input.visit((style, text) -> {
            int run = 0;
            for (int i = 0; i < text.length();) {
                int cp = text.codePointAt(i);
                int next = i + Character.charCount(cp);
                if (ChatEmojiPanel.supports(cp)) {
                    if (i > run) result.append(Text.literal(text.substring(run, i)).fillStyle(style));
                    // The bundled bitmap is already the emoji presentation. Leaving
                    // U+FE0F for Minecraft's font produces a visible VS16 box.
                    int afterEmoji = next;
                    if (afterEmoji < text.length() && text.charAt(afterEmoji) == '\uFE0F')
                        next = afterEmoji + 1;
                    result.append(Text.literal(text.substring(i, afterEmoji)).fillStyle(
                        style.withColor(Formatting.WHITE).withFont(FONT)));
                    run = next;
                }
                i = next;
            }
            if (run < text.length()) result.append(Text.literal(text.substring(run)).fillStyle(style));
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return result;
    }
}
