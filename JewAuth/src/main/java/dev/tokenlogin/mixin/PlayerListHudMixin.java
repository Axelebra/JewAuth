package dev.tokenlogin.mixin;

import dev.tokenlogin.client.NickHider;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts the tab list name rendering to replace the player's real name
 * with the fake name when NickHider is enabled.
 */
@Environment(EnvType.CLIENT)
@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {

    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void tokenlogin$replaceTabName(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        if (NickHider.isEnabled()) {
            Text original = cir.getReturnValue();
            Text replaced = NickHider.replaceInText(original);
            if (replaced != original) {
                cir.setReturnValue(replaced);
            }
        }
    }
}
