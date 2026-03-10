package dev.tokenlogin.mixin;

import dev.tokenlogin.client.NickHider;
import dev.tokenlogin.client.SelfBan;
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
 * with the fake name when NickHider is enabled, and to detect
 * server transfers for SelfBan.
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
        // Detect server transfers and errors for SelfBan
        if (SelfBan.isEnabled()) {
            String raw = message.getString().toLowerCase();
            if (raw.contains("sending to server")) {
                SelfBan.onServerTransferMessage();
            }
            if (raw.contains("there was a problem joining skyblock")) {
                SelfBan.onSkyblockJoinError();
            }
            if (raw.contains("couldn't warp you")) {
                SelfBan.onHubWarpError();
            }
        }

        if (NickHider.isEnabled()) {
            return NickHider.replaceInText(message);
        }
        return message;
    }
}