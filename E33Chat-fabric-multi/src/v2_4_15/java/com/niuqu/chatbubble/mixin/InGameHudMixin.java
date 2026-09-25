package com.niuqu.chatbubble.mixin;

import com.niuqu.chatbubble.render.HudVisibility;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the vanilla HUD while a translucent E33Chat screen is open (2.4.11).
 *
 * Previously the screens set {@code options.hudHidden}, which is the F1 flag —
 * {@code GameRenderer} also gates first-person hands/held items on it, so
 * opening the chat panel made the hand disappear. Cancelling the HUD render
 * here skips only the HUD layer.
 */
@Mixin(Gui.class)
public class InGameHudMixin {

    //#if MC >= 260200
    //$$ @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    //$$ private void e33chat$hideHudForTranslucentScreens(DeltaTracker tickCounter,
    //$$         boolean first, boolean second, CallbackInfo ci) {
    //$$     if (HudVisibility.shouldHideHud()) ci.cancel();
    //$$ }
    //#else
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void e33chat$hideHudForTranslucentScreens(DrawContext context, DeltaTracker tickCounter,
                                                      CallbackInfo ci) {
        if (HudVisibility.shouldHideHud()) ci.cancel();
    }
    //#endif
}
