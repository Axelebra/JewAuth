package dev.tokenlogin.mixin;

import dev.tokenlogin.client.AntiKick;
import dev.tokenlogin.client.TokenLoginClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts disconnect packets in ClientCommonNetworkHandler.
 *
 * Detects duplicate-login kicks and schedules the initial reconnect.
 * If the reconnect itself fails (any reason), DisconnectedScreenMixin
 * handles scheduling retries when the disconnect screen opens.
 */
@Environment(EnvType.CLIENT)
@Mixin(net.minecraft.client.network.ClientCommonNetworkHandler.class)
public abstract class AntiKickMixin {

    @Inject(
            method = "onDisconnect(Lnet/minecraft/network/packet/s2c/common/DisconnectS2CPacket;)V",
            at = @At("HEAD")
    )
    private void tokenlogin$interceptDisconnect(DisconnectS2CPacket packet, CallbackInfo ci) {
        Text reason = packet.reason();

        if (AntiKick.isDuplicateLoginKick(reason)) {
            AntiKick.scheduleReconnect();
            TokenLoginClient.LOGGER.info(
                    "AntiKick: duplicate-login kick detected, auto-reconnect scheduled (reason: \"{}\")",
                    reason.getString()
            );
        }
    }
}
