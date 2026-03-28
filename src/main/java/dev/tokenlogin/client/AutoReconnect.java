package dev.tokenlogin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

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
    private static ServerInfo lastServer = null;

    public static boolean isEnabled() { return enabled; }

    public static void toggle() {
        enabled = !enabled;
        TokenLoginClient.LOGGER.info("AutoReconnect {}", enabled ? "enabled" : "disabled");
    }

    public static void setLastServer(ServerInfo server) {
        if (server != null) lastServer = server;
    }

    public static ServerInfo getLastServer() { return lastServer; }

    public static void connect(MinecraftClient mc) {
        if (lastServer == null) return;
        ServerAddress addr = ServerAddress.parse(lastServer.address);
        mc.execute(() -> ConnectScreen.connect(
                new MultiplayerScreen(new TitleScreen()),
                mc, addr, lastServer, false, null
        ));
    }
}
