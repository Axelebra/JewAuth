package dev.tokenlogin.mixin;

import dev.tokenlogin.client.LobbyAnonymiser;
import dev.tokenlogin.client.NickHider;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Intercepts chat messages to replace the player's real name
 * with the fake name when NickHider is enabled, and to apply
 * LobbyAnonymiser replacements.
 */
@Environment(EnvType.CLIENT)
@Mixin(ChatComponent.class)
public abstract class ChatHudMixin {

    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private Component tokenlogin$replaceChatName(Component message) {
        Component out = NickHider.isEnabled() ? NickHider.replaceInText(message) : message;
        return LobbyAnonymiser.replaceInText(out);
    }
}
