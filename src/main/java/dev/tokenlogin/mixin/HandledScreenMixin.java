package dev.tokenlogin.mixin;

import dev.tokenlogin.client.NoCursorReset;
import dev.tokenlogin.client.SoulboundHighlight;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives {@link NoCursorReset}: save the cursor position when an inventory screen
 * closes, restore it when the next one opens (if back-to-back).
 *
 * Also drives {@link SoulboundHighlight}, painting the soulbound slot overlay
 * after the container has drawn its slots.
 */
@Environment(EnvType.CLIENT)
@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenMixin {

    @Shadow protected int leftPos;
    @Shadow protected int topPos;

    @Inject(method = "removed", at = @At("HEAD"))
    private void tokenlogin$saveCursorOnClose(CallbackInfo ci) {
        NoCursorReset.save(Minecraft.getInstance());
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void tokenlogin$restoreCursorOnOpen(CallbackInfo ci) {
        NoCursorReset.restore(Minecraft.getInstance());
    }

    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("TAIL")
    )
    private void tokenlogin$soulboundOverlay(GuiGraphicsExtractor ctx,
                                             int mouseX, int mouseY, float delta,
                                             CallbackInfo ci) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        SoulboundHighlight.renderSlotOverlays(ctx, screen.getMenu().slots, leftPos, topPos);
    }
}
