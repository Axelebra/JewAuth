package dev.tokenlogin.mixin;

import dev.tokenlogin.client.AutoReconnect;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the current server entry on any disconnect (kick or voluntary),
 * before Minecraft clears the server entry, so AutoReconnect knows where to
 * reconnect.
 */
@Environment(EnvType.CLIENT)
@Mixin(net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl.class)
public abstract class ServerCaptureMixin {

    // Fired when the server sends a disconnect packet (kick)
    @Inject(
            method = "handleDisconnect(Lnet/minecraft/network/protocol/common/ClientboundDisconnectPacket;)V",
            at = @At("HEAD")
    )
    private void tokenlogin$captureOnKick(ClientboundDisconnectPacket packet, CallbackInfo ci) {
        tokenlogin$capture();
    }

    // Fired on ALL disconnects (kick or voluntary) when the connection is fully closed
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void tokenlogin$captureOnDisconnected(net.minecraft.network.DisconnectionDetails info, CallbackInfo ci) {
        tokenlogin$capture();
    }

    @org.spongepowered.asm.mixin.Unique
    private void tokenlogin$capture() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getCurrentServer() != null) {
            AutoReconnect.setLastServer(mc.getCurrentServer());
        }
    }
}
