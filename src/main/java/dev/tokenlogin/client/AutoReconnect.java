package dev.tokenlogin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Auto-reconnect — waits a short delay after disconnect then reconnects.
 * Ported from MeteorClient's AutoReconnect module.
 *
 * Last server is captured in AutoReconnectMixin when a disconnect packet arrives.
 * The countdown and reconnect happen in DisconnectedScreenMixin.
 */
@Environment(EnvType.CLIENT)
public class AutoReconnect {

    /** Reconnect countdown in ticks (20 ticks/sec). 20 = 1s */
    public static final int RECONNECT_DELAY_TICKS = 20;

    private static boolean enabled = false;
    private static ServerData lastServer = null;

    public static boolean isEnabled() { return enabled; }

    public static void toggle() {
        enabled = !enabled;
        TokenLoginClient.LOGGER.info("AutoReconnect {}", enabled ? "enabled" : "disabled");
    }

    public static void setLastServer(ServerData server) {
        if (server != null) lastServer = server;
    }

    public static ServerData getLastServer() { return lastServer; }

    public static void connect(Minecraft mc) {
        if (lastServer == null) return;
        ServerAddress addr = ServerAddress.parseString(lastServer.ip);
        mc.execute(() -> ConnectScreen.startConnecting(
                new JoinMultiplayerScreen(new TitleScreen()),
                mc, addr, lastServer, false, null
        ));
    }
}
