package dev.tokenlogin.mixin;

import dev.tokenlogin.client.ProxyManager;
import dev.tokenlogin.client.TokenLoginClient;
import io.netty.channel.Channel;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Targets the anonymous ChannelInitializer inside ClientConnection.connect().
 *
 * We inject at TAIL of initChannel — Minecraft's handlers are already in the
 * pipeline, then we prepend the proxy handler so it intercepts the connect event.
 *
 * IMPORTANT: Do NOT extend ChannelInitializer here. Doing so merges an empty
 * initChannel override into the target, destroying the real pipeline setup.
 */
@Environment(EnvType.CLIENT)
@Mixin(targets = "net.minecraft.network.ClientConnection$1")
public abstract class ClientConnectionChannelInitMixin {

    @Inject(method = "initChannel(Lio/netty/channel/Channel;)V", at = @At("TAIL"))
    private void tokenlogin$injectProxyHandler(Channel channel, CallbackInfo ci) {
        if (ProxyManager.isEnabled()) {
            try {
                channel.pipeline().addFirst(
                        "tokenlogin_proxy",
                        ProxyManager.createHandler()
                );
                TokenLoginClient.LOGGER.debug(
                        "Injected {} proxy handler into channel pipeline",
                        ProxyManager.getActiveType().displayName()
                );
            } catch (Exception e) {
                TokenLoginClient.LOGGER.error("Failed to inject proxy handler", e);
            }
        }
    }
}