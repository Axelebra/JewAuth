package dev.tokenlogin.mixin;

import dev.tokenlogin.client.NoCursorReset;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives {@link NoCursorReset}: save the cursor position when an inventory screen
 * closes, restore it when the next one opens (if back-to-back).
 */
@Environment(EnvType.CLIENT)
@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin {

    @Inject(method = "removed", at = @At("HEAD"))
    private void tokenlogin$saveCursorOnClose(CallbackInfo ci) {
        NoCursorReset.save(MinecraftClient.getInstance());
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void tokenlogin$restoreCursorOnOpen(CallbackInfo ci) {
        NoCursorReset.restore(MinecraftClient.getInstance());
    }
}
