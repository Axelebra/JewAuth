package dev.tokenlogin.mixin;

import dev.tokenlogin.client.LobbyAnonymiser;
import dev.tokenlogin.client.NickHider;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Intercepts chat messages to replace the player's real name
 * with the fake name when NickHider is enabled, and to apply
 * LobbyAnonymiser replacements.
 */
@Environment(EnvType.CLIENT)
@Mixin(ChatHud.class)
public abstract class ChatHudMixin {

    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private Text tokenlogin$replaceChatName(Text message) {
        Text out = NickHider.isEnabled() ? NickHider.replaceInText(message) : message;
        return LobbyAnonymiser.replaceInText(out);
    }
}