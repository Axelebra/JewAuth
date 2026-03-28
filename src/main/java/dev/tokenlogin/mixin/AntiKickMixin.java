package dev.tokenlogin.mixin;

import dev.tokenlogin.client.AutoReconnect;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the current server entry on any disconnect (kick or voluntary),
 * before Minecraft clears the server entry.
 */
@Environment(EnvType.CLIENT)
@Mixin(net.minecraft.client.network.ClientCommonNetworkHandler.class)
public abstract class AntiKickMixin {

    // Fired when the server sends a disconnect packet (kick)
    @Inject(
            method = "onDisconnect(Lnet/minecraft/network/packet/s2c/common/DisconnectS2CPacket;)V",
            at = @At("HEAD")
    )
    private void tokenlogin$captureOnKick(DisconnectS2CPacket packet, CallbackInfo ci) {
        tokenlogin$capture();
    }

    // Fired on ALL disconnects (kick or voluntary) when the connection is fully closed
    @Inject(method = "onDisconnected", at = @At("HEAD"))
    private void tokenlogin$captureOnDisconnected(net.minecraft.network.DisconnectionInfo info, CallbackInfo ci) {
        tokenlogin$capture();
    }

    @org.spongepowered.asm.mixin.Unique
    private void tokenlogin$capture() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.getCurrentServerEntry() != null) {
            AutoReconnect.setLastServer(mc.getCurrentServerEntry());
        }
    }
}
