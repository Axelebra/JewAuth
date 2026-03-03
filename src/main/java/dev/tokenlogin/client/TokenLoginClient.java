package dev.tokenlogin.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public class TokenLoginClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("tokenlogin");

    private static boolean proxyAutoConnectAttempted = false;

    @Override
    public void onInitializeClient() {
        LOGGER.info("TokenLogin initialized");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            SelfBan.tick();
            autoConnectProxy();
        });
    }

    /**
     * Auto-connects to the last active proxy on game startup.
     * Runs once, on the first tick after the client is ready.
     */
    private static void autoConnectProxy() {
        if (proxyAutoConnectAttempted) return;
        proxyAutoConnectAttempted = true;

        // Load config to find the active proxy
        ProxyConfig.load();
        ProxyEntry active = ProxyConfig.getActiveProxy();

        if (active == null || active.address.isBlank()) {
            LOGGER.info("No active proxy configured — skipping auto-connect");
            return;
        }

        LOGGER.info("Auto-connecting to proxy: {} ({})", 
                active.name.isEmpty() ? active.address : active.name, active.address);

        Thread t = new Thread(() -> {
            ProxyManager.ProxyType result = ProxyManager.testAndConnect(
                    active.address, active.username, active.password);

            if (result != ProxyManager.ProxyType.NONE) {
                ProxyConfig.markConnected(active, result);
                LOGGER.info("Proxy auto-connected via {}: {}", result.displayName(), active.address);
            } else {
                LOGGER.warn("Proxy auto-connect failed for: {}", active.address);
            }
        }, "TokenLogin-ProxyAutoConnect");
        t.setDaemon(true);
        t.start();
    }
}
