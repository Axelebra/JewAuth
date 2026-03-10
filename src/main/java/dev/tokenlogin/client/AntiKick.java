package dev.tokenlogin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

/**
 * Anti-kick — auto-reconnects when kicked for "logged in from another location".
 *
 * The server closes the TCP socket on its end regardless, so we let the disconnect
 * happen then reconnect as fast as possible. If the reconnect fails for any reason,
 * retries every ~1s until the user turns it off.
 */
@Environment(EnvType.CLIENT)
public class AntiKick {

    private static volatile boolean enabled = false;
    private static int blockedCount = 0;
    private static long lastBlockedTime = 0L;

    // ── Reconnect state ──────────────────────────────────────────────────────
    private static volatile boolean pendingReconnect = false;
    private static volatile ServerInfo reconnectServer = null;
    private static volatile ServerInfo lastReconnectServer = null;
    private static volatile long reconnectAt = 0L;

    /** True while we're in the middle of a reconnect attempt. */
    private static volatile boolean reconnectInProgress = false;

    /** Epoch ms of the last reconnect attempt — used to avoid stale retries. */
    private static volatile long lastAttemptTime = 0L;

    /** Number of consecutive reconnect retries (resets on success or disable). */
    private static int reconnectRetries = 0;

    /** Delay before first reconnect (ms). */
    private static final long RECONNECT_DELAY_MS = 100;

    /** Delay between retries when reconnect fails (ms). */
    private static final long RETRY_DELAY_MS = 1000;

    /** If we've been connected for longer than this, don't count disconnects as failed retries. */
    private static final long RECONNECT_STALE_MS = 15000;

    /** Known disconnect reason patterns for duplicate-login kicks. */
    private static final String[] BLOCK_PATTERNS = {
            "logged in from another location",
            "you logged in from another location",
            "logged in from another",
            "duplicate login",
            "account is already logged in",
            "already connected",
            "already playing",
            "multiplayer.disconnect.duplicate_login",
            "disconnect.loginfailedinfo.duplicatelogin",
    };

    // ── Toggle ───────────────────────────────────────────────────────────────

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) {
            blockedCount = 0;
            reconnectRetries = 0;
            reconnectInProgress = false;
            lastAttemptTime = 0L;
            cancelReconnect();
        }
        TokenLoginClient.LOGGER.info("AntiKick {}", enabled ? "enabled" : "disabled");
    }

    public static void enable() {
        enabled = true;
        TokenLoginClient.LOGGER.info("AntiKick enabled");
    }

    public static void disable() {
        enabled = false;
        blockedCount = 0;
        reconnectRetries = 0;
        reconnectInProgress = false;
        lastAttemptTime = 0L;
        cancelReconnect();
        TokenLoginClient.LOGGER.info("AntiKick disabled");
    }

    public static int getBlockedCount() {
        return blockedCount;
    }

    public static long getLastBlockedTime() {
        return lastBlockedTime;
    }

    public static boolean hasPendingReconnect() {
        return pendingReconnect;
    }

    public static boolean isReconnectInProgress() {
        if (!reconnectInProgress) return false;
        // Don't consider it "in progress" if the last attempt was long ago
        // (means we successfully reconnected and have been playing)
        if (lastAttemptTime > 0 && System.currentTimeMillis() - lastAttemptTime > RECONNECT_STALE_MS) {
            reconnectInProgress = false;
            reconnectRetries = 0;
            return false;
        }
        return true;
    }

    public static int getReconnectRetries() {
        return reconnectRetries;
    }

    private static void cancelReconnect() {
        pendingReconnect = false;
        reconnectServer = null;
        lastReconnectServer = null;
    }

    /**
     * Clears reconnect state without disabling AntiKick.
     * Called when the user navigates away from the disconnect screen
     * (e.g. to join a different server).
     */
    public static void cancelReconnectState() {
        pendingReconnect = false;
        reconnectInProgress = false;
        reconnectRetries = 0;
        reconnectServer = null;
        lastReconnectServer = null;
        lastAttemptTime = 0L;
        TokenLoginClient.LOGGER.info("AntiKick: reconnect state cleared (user navigated away)");
    }

    // ── Detection ────────────────────────────────────────────────────────────

    public static boolean isDuplicateLoginKick(Text reason) {
        if (!enabled) return false;
        if (reason == null) return false;

        String raw = reason.getString().toLowerCase().trim();

        for (String pattern : BLOCK_PATTERNS) {
            if (raw.contains(pattern)) {
                blockedCount++;
                lastBlockedTime = System.currentTimeMillis();
                TokenLoginClient.LOGGER.warn(
                        "AntiKick detected duplicate-login kick #{}: \"{}\"",
                        blockedCount, reason.getString()
                );
                return true;
            }
        }

        return false;
    }

    // ── Reconnect scheduling ─────────────────────────────────────────────────

    public static void scheduleReconnect() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ServerInfo server = mc.getCurrentServerEntry();

        if (server == null) {
            TokenLoginClient.LOGGER.warn("AntiKick: no server info available, cannot reconnect");
            return;
        }

        reconnectServer = server;
        reconnectAt = System.currentTimeMillis() + RECONNECT_DELAY_MS;
        pendingReconnect = true;
        reconnectInProgress = true;

        TokenLoginClient.LOGGER.info(
                "AntiKick: reconnect scheduled to {} in {}ms",
                server.address, RECONNECT_DELAY_MS
        );
    }

    /**
     * Called when a disconnect happens while we're in the middle of reconnecting.
     * Schedules another retry after RETRY_DELAY_MS.
     */
    public static void scheduleRetry() {
        if (!enabled) return;

        // Use the last server we tried to connect to
        ServerInfo server = lastReconnectServer;
        if (server == null) {
            MinecraftClient mc = MinecraftClient.getInstance();
            server = mc.getCurrentServerEntry();
            if (server == null) {
                TokenLoginClient.LOGGER.warn("AntiKick: no server info for retry");
                reconnectInProgress = false;
                return;
            }
        }

        reconnectServer = server;
        reconnectRetries++;
        reconnectAt = System.currentTimeMillis() + RETRY_DELAY_MS;
        pendingReconnect = true;

        TokenLoginClient.LOGGER.info(
                "AntiKick: reconnect failed, retry #{} in {}ms to {}",
                reconnectRetries, RETRY_DELAY_MS, server.address
        );
    }

    /**
     * Called when a connection succeeds (player joins server).
     * Resets reconnect state.
     */
    public static void onConnectSuccess() {
        if (reconnectInProgress) {
            TokenLoginClient.LOGGER.info("AntiKick: reconnect successful after {} retries", reconnectRetries);
        }
        reconnectInProgress = false;
        reconnectRetries = 0;
    }

    /** True while tickReconnect is actively initiating a connection. Don't cancel during this. */
    private static volatile boolean connectingNow = false;

    public static boolean isConnectingNow() { return connectingNow; }

    public static void tickReconnect() {
        if (!pendingReconnect || reconnectServer == null) return;
        if (System.currentTimeMillis() < reconnectAt) return;

        pendingReconnect = false;
        ServerInfo server = reconnectServer;
        reconnectServer = null;

        // Save for retries — scheduleRetry() uses this
        lastReconnectServer = server;
        lastAttemptTime = System.currentTimeMillis();

        MinecraftClient mc = MinecraftClient.getInstance();
        ServerAddress addr = ServerAddress.parse(server.address);

        TokenLoginClient.LOGGER.info("AntiKick: reconnecting to {} (attempt #{})...",
                server.address, reconnectRetries + 1);

        connectingNow = true;
        mc.execute(() -> {
            ConnectScreen.connect(
                    new MultiplayerScreen(new TitleScreen()),
                    mc,
                    addr,
                    server,
                    false,
                    null
            );
            connectingNow = false;
        });
    }

    /**
     * Returns a status string for display on the disconnect screen.
     */
    public static String getStatusText() {
        if (!enabled) return "AntiKick: OFF";
        if (pendingReconnect) {
            long remaining = reconnectAt - System.currentTimeMillis();
            if (remaining > 0) return "Reconnecting in " + (remaining / 1000 + 1) + "s...";
            return "Reconnecting...";
        }
        if (reconnectInProgress) {
            return "Retry #" + reconnectRetries + " — reconnecting...";
        }
        return "AntiKick: ON (blocked " + blockedCount + ")";
    }

    public static int getStatusColor() {
        if (!enabled) return 0xFF888888;
        if (pendingReconnect || reconnectInProgress) return 0xFFFFAA00;
        return 0xFF55FF55;
    }
}
