package com.niuqu.chatbubble;

import com.niuqu.chatbubble.ui.ChatEmojiPanel;
import net.minecraft.text.FontDescription;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/** Draw bundled color Emoji with white tint while preserving surrounding text styles. */
final class ColorEmojiText {
    private static final FontDescription.Resource FONT =
        new FontDescription.Resource(Identifier.of("e33chat", "emoji"));

    private ColorEmojiText() {}

    static Text decorate(Text input) {
        MutableText result = Text.empty();
        input.visit((style, text) -> {
            int run = 0;
            for (int i = 0; i < text.length();) {
                int cp = text.codePointAt(i);
                int next = i + Character.charCount(cp);
                if (ChatEmojiPanel.supports(cp)) {
                    if (i > run) result.append(Text.literal(text.substring(run, i)).fillStyle(style));
                    result.append(Text.literal(text.substring(i, next)).fillStyle(
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
