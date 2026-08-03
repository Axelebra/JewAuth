package dev.tokenlogin.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
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
            HoverLoot.tick(client);
            NoCursorReset.tick(client);
            autoConnectProxy(client);
            clearButtonFocus(client);
        });
    }

    /**
     * On first tick: read current session IGN, look up its bound proxy in
     * AccountStorage, fill the proxy fields and connect.
     */
    private static void autoConnectProxy(Minecraft client) {
        if (proxyAutoConnectAttempted) return;
        proxyAutoConnectAttempted = true;

        ProxyConfig.load();
        AccountStorage.load();

        String ign = client.getUser() != null ? client.getUser().getName() : "";
        if (ign.isEmpty()) return;

        String boundAddr = AccountStorage.getProxyBindingByUsername(ign);
        if (boundAddr == null || boundAddr.isBlank()) {
            LOGGER.info("No proxy binding for {} — skipping auto-connect", ign);
            return;
        }

        String user = "";
        String pass = "";
        for (ProxyEntry p : ProxyConfig.getProxies()) {
            if (p.key().equals(boundAddr.trim().toLowerCase())) {
                user = p.username;
                pass = p.password;
                break;
            }
        }

        final String fAddr = boundAddr, fUser = user, fPass = pass;
        LOGGER.info("Auto-connecting bound proxy for {}: {}", ign, fAddr);

        Thread t = new Thread(() -> {
            ProxyManager.ProxyType result = ProxyManager.testAndConnect(fAddr, fUser, fPass);
            if (result != ProxyManager.ProxyType.NONE) {
                String key = fAddr.trim().toLowerCase();
                ProxyEntry entry = null;
                for (ProxyEntry p : ProxyConfig.getProxies()) {
                    if (p.key().equals(key)) { entry = p; break; }
                }
                if (entry == null) {
                    entry = new ProxyEntry();
                    entry.address  = fAddr;
                    entry.username = fUser;
                    entry.password = fPass;
                    ProxyConfig.addProxy(entry);
                }
                ProxyConfig.markConnected(entry, result);
                LOGGER.info("Bound proxy connected via {}", result.displayName());
            } else {
                LOGGER.warn("Bound proxy failed: {}", fAddr);
            }
        }, "TokenLogin-ProxyAutoConnect");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Clears focus from buttons every tick so they don't keep the white
     * outline after being clicked with the mouse.
     */
    private static void clearButtonFocus(Minecraft client) {
        if (Screens.current(client) == null) return;
        if (Screens.current(client).getFocused() instanceof Button w && !w.isMouseOver(
                client.mouseHandler.getScaledXPos(client.getWindow()),
                client.mouseHandler.getScaledYPos(client.getWindow()))) {
            Screens.current(client).setFocused(null);
        }
    }
}
