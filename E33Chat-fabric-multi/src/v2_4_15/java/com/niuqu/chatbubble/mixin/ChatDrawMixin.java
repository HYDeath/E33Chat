package com.niuqu.chatbubble.mixin;

import com.niuqu.chatbubble.render.ChatGradient;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Vanilla HUD chat, both the focused pass and the background pass.
 * Mojang names on purpose: the 26.x rewriter would turn Yarn names into these
 * and must not be given a second pass. The panel draws through its own path.
 */
@Mixin(targets = {
        "net.minecraft.client.gui.components.ChatComponent$DrawingFocusedGraphicsAccess",
        "net.minecraft.client.gui.components.ChatComponent$DrawingBackgroundGraphicsAccess"
})
public class ChatDrawMixin {
    private static final boolean XINGCHEN = xingchenPresent();

    @ModifyVariable(method = "handleMessage", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private FormattedCharSequence e33$flow(FormattedCharSequence line) {
        if (XINGCHEN) {
            return line;
        }
        return ChatGradient.flow(line);
    }

    private static boolean xingchenPresent() {
        try {
            Class.forName("cn.xingchen.client.chat.ChatGradient");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
