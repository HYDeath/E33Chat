package com.niuqu.chatbubble.mixin;

import com.niuqu.chatbubble.render.HudVisibility;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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
    // Gui.extractRenderState also draws the open screen. Cancelling the whole
    // method hid the settings page along with the hotbar.
    //$$ @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
    //$$     target = "Lnet/minecraft/client/gui/Hud;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
    //$$ private void e33chat$skipHud(net.minecraft.client.gui.Hud hud,
    //$$         net.minecraft.client.gui.GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
    //$$     if (!HudVisibility.shouldHideHud()) hud.extractRenderState(graphics, tickCounter);
    //$$ }
    //#else
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void e33chat$hideHudForTranslucentScreens(DrawContext context, DeltaTracker tickCounter,
                                                      CallbackInfo ci) {
        if (HudVisibility.shouldHideHud()) ci.cancel();
    }
    //#endif
}
