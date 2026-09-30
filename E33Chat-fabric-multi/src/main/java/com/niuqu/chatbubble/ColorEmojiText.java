package com.niuqu.chatbubble;

import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;

/** Applies the bundled Noto bitmap font only to supported emoji codepoints. */
final class ColorEmojiText {
    private static final Identifier FONT =
        //#if MC >= 12000
        Identifier.of("e33chat", "emoji");
        //#else
        //$$ new Identifier("e33chat", "emoji");
        //#endif

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
                    int afterEmoji = next;
                    if (afterEmoji < text.length() && text.charAt(afterEmoji) == '\uFE0F')
                        next = afterEmoji + 1;
                    //#if MC >= 26000
                    result.append(Text.literal(text.substring(i, afterEmoji)).fillStyle(
                        style.withColor(Formatting.WHITE).withFont(new net.minecraft.text.FontDescription.Resource(FONT))));
                    //#else
                    //#if MC >= 12109
                    //$$ result.append(Text.literal(text.substring(i, afterEmoji)).fillStyle(
                    //$$     style.withColor(Formatting.WHITE).withFont(new net.minecraft.text.StyleSpriteSource.Font(FONT))));
                    //#else
                    //$$ result.append(Text.literal(text.substring(i, afterEmoji)).fillStyle(style.withColor(Formatting.WHITE).withFont(FONT)));
                    //#endif
                    //#endif
                    run = next;
                } else {
                    Style craft = ChatEmojiPanel.craftStyle(cp);
                    if (craft != null && style.getFont() == null) {
                        if (i > run) result.append(Text.literal(text.substring(run, i)).fillStyle(style));
                        result.append(Text.literal(text.substring(i, next)).fillStyle(craft));
                        run = next;
                    }
                }
                i = next;
            }
            if (run < text.length()) result.append(Text.literal(text.substring(run)).fillStyle(style));
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return result;
    }
}
