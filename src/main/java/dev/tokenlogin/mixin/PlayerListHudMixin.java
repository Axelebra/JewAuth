package dev.tokenlogin.mixin;

import dev.tokenlogin.client.NickHider;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts the tab list name rendering to replace the player's real name
 * with the fake name when NickHider is enabled.
 */
@Environment(EnvType.CLIENT)
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerListHudMixin {

    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void tokenlogin$replaceTabName(PlayerInfo entry, CallbackInfoReturnable<Component> cir) {
        if (NickHider.isEnabled()) {
            Component original = cir.getReturnValue();
            Component replaced = NickHider.replaceInText(original);
            if (replaced != original) {
                cir.setReturnValue(replaced);
            }
        }
    }
}
