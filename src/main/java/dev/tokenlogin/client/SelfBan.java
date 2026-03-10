package dev.tokenlogin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;

import java.util.Collection;

/**
 * Selfban — toggle + multi-cheat packet sender.
 *
 * Button flow: OFF → press → "Sure?" → press → ON → press → OFF
 * When ON + in-game:
 *   1. Wait 1s, check tab — if already in hub, blast immediately
 *   2. /skyblock → wait for transfer + world load (retry with backoff on error)
 *   3. /hub → wait for transfer + world load (retry on warp error, detect already-in-hub)
 *   4. Verify "Area: Hub" in tab → blast
 */
@Environment(EnvType.CLIENT)
public class SelfBan {

    public enum State { OFF, CONFIRMING, ON }

    private enum Phase {
        INITIAL_WAIT,
        CHECK_AREA,
        SEND_SKYBLOCK,
        WAIT_SKYBLOCK_MSG,
        RETRY_SKYBLOCK,
        WAIT_SKYBLOCK_WORLD,
        SEND_HUB,
        WAIT_HUB_MSG,
        RETRY_HUB,
        WAIT_HUB_WORLD,
        VERIFY_HUB,
        BLASTING
    }

    private static volatile State state = State.OFF;
    private static Phase phase = Phase.INITIAL_WAIT;
    private static boolean blasting = false;
    private static double lastY = 0;
    private static int lobbyDetectTicks = 0;

    // ── Chat detection flags (set by ChatHudMixin) ─────────────────────────
    private static volatile boolean serverTransferDetected = false;
    private static volatile boolean skyblockJoinError      = false;
    private static volatile boolean hubWarpError           = false;

    // ── Timing ─────────────────────────────────────────────────────────────
    private static long waitStartedAt = 0L;
    /** For retry phases: the epoch ms when the retry delay ends. */
    private static long retryUntil    = 0L;

    // ── Position snapshot for world change detection ───────────────────────
    private static double snapshotX = 0, snapshotY = 0, snapshotZ = 0;

    // ── Retry counters ─────────────────────────────────────────────────────
    private static int skyblockRetries = 0;
    private static int hubRetries      = 0;

    // ── Constants ──────────────────────────────────────────────────────────
    private static final long   INITIAL_DELAY_MS     = 1000;
    private static final long   FALLBACK_TIMEOUT_MS  = 4000;
    private static final long   HUB_RETRY_DELAY_MS   = 5000;
    private static final double WORLD_CHANGE_DIST_SQ  = 50.0 * 50.0;

    /** Backoff schedule for /skyblock retries: 5s, 10s, 20s, then 20s forever. */
    private static final long[] SKYBLOCK_BACKOFF_MS = { 5000, 10000, 20000 };

    // ── Public API ─────────────────────────────────────────────────────────

    public static boolean isEnabled() { return state == State.ON; }
    public static State   getState()  { return state; }

    public static State toggle() {
        state = switch (state) {
            case OFF        -> State.CONFIRMING;
            case CONFIRMING -> State.ON;
            case ON         -> State.OFF;
        };
        if (state != State.ON) reset();
        TokenLoginClient.LOGGER.info("SelfBan state: {}", state);
        return state;
    }

    public static void disable() {
        state = State.OFF;
        reset();
    }

    private static void reset() {
        phase = Phase.INITIAL_WAIT;
        blasting = false;
        lastY = 0;
        lobbyDetectTicks = 0;
        serverTransferDetected = false;
        skyblockJoinError = false;
        hubWarpError = false;
        waitStartedAt = 0L;
        retryUntil = 0L;
        snapshotX = snapshotY = snapshotZ = 0;
        skyblockRetries = 0;
        hubRetries = 0;
    }

    // ── Chat callbacks (called from ChatHudMixin) ──────────────────────────

    public static void onServerTransferMessage() {
        serverTransferDetected = true;
    }

    public static void onSkyblockJoinError() {
        skyblockJoinError = true;
    }

    public static void onHubWarpError() {
        hubWarpError = true;
    }

    // ── Tab list helper ────────────────────────────────────────────────────

    private static String getAreaFromTab() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getNetworkHandler() == null) return null;

        Collection<PlayerListEntry> entries = mc.getNetworkHandler().getPlayerList();
        for (PlayerListEntry entry : entries) {
            if (entry.getDisplayName() == null) continue;
            String text = entry.getDisplayName().getString().trim();
            if (text.startsWith("Area: ")) {
                return text.substring(6).trim();
            }
        }
        return null;
    }

    private static boolean isInHub() {
        String area = getAreaFromTab();
        return area != null && area.equalsIgnoreCase("Hub");
    }

    // ── Backoff helper ─────────────────────────────────────────────────────

    private static long getSkyblockBackoffMs() {
        int idx = Math.min(skyblockRetries, SKYBLOCK_BACKOFF_MS.length - 1);
        return SKYBLOCK_BACKOFF_MS[Math.max(0, idx)];
    }

    // ── Tick ───────────────────────────────────────────────────────────────

    public static void tick() {
        if (!isEnabled()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.getNetworkHandler() == null) return;

        ClientPlayerEntity player = mc.player;

        // Detect lobby kick while blasting — restart the flow
        if (blasting) {
            double currentY = player.getY();
            if (Math.abs(currentY - lastY) > 50) {
                lobbyDetectTicks++;
                if (lobbyDetectTicks > 5) {
                    TokenLoginClient.LOGGER.info("SelfBan: lobby kick detected, re-queuing");
                    reset();
                    state = State.ON;
                    waitStartedAt = System.currentTimeMillis();
                    return;
                }
            } else {
                lobbyDetectTicks = 0;
            }
            lastY = currentY;
        }

        switch (phase) {

            // ── 1s initial delay ───────────────────────────────────────────
            case INITIAL_WAIT -> {
                if (waitStartedAt == 0L) {
                    waitStartedAt = System.currentTimeMillis();
                    TokenLoginClient.LOGGER.info("SelfBan: waiting 1s before starting...");
                }
                if (System.currentTimeMillis() - waitStartedAt >= INITIAL_DELAY_MS) {
                    phase = Phase.CHECK_AREA;
                }
            }

            // ── Check if already in hub ────────────────────────────────────
            case CHECK_AREA -> {
                if (isInHub()) {
                    TokenLoginClient.LOGGER.info("SelfBan: already in hub, blasting immediately!");
                    blasting = true;
                    lastY = player.getY();
                    phase = Phase.BLASTING;
                } else {
                    TokenLoginClient.LOGGER.info("SelfBan: not in hub (area={}), starting /skyblock flow",
                            getAreaFromTab());
                    phase = Phase.SEND_SKYBLOCK;
                }
            }

            // ── /skyblock ──────────────────────────────────────────────────
            case SEND_SKYBLOCK -> {
                serverTransferDetected = false;
                skyblockJoinError = false;
                player.networkHandler.sendChatCommand("skyblock");
                waitStartedAt = System.currentTimeMillis();
                phase = Phase.WAIT_SKYBLOCK_MSG;
                TokenLoginClient.LOGGER.info("SelfBan: sent /skyblock, waiting for transfer...");
            }

            case WAIT_SKYBLOCK_MSG -> {
                if (skyblockJoinError) {
                    // "There was a problem joining Skyblock, try again in a moment!"
                    skyblockJoinError = false;
                    long delay = getSkyblockBackoffMs();
                    skyblockRetries++;
                    retryUntil = System.currentTimeMillis() + delay;
                    phase = Phase.RETRY_SKYBLOCK;
                    TokenLoginClient.LOGGER.info("SelfBan: skyblock join error, retry #{} in {}ms",
                            skyblockRetries, delay);
                    return;
                }

                if (serverTransferDetected) {
                    serverTransferDetected = false;
                    snapshotX = player.getX();
                    snapshotY = player.getY();
                    snapshotZ = player.getZ();
                    waitStartedAt = System.currentTimeMillis();
                    phase = Phase.WAIT_SKYBLOCK_WORLD;
                    TokenLoginClient.LOGGER.info("SelfBan: skyblock transfer detected, waiting for world...");
                } else if (System.currentTimeMillis() - waitStartedAt >= FALLBACK_TIMEOUT_MS) {
                    TokenLoginClient.LOGGER.info("SelfBan: skyblock message timeout, proceeding to /hub");
                    phase = Phase.SEND_HUB;
                }
            }

            case RETRY_SKYBLOCK -> {
                if (System.currentTimeMillis() >= retryUntil) {
                    phase = Phase.SEND_SKYBLOCK;
                }
            }

            case WAIT_SKYBLOCK_WORLD -> {
                double dx = player.getX() - snapshotX;
                double dy = player.getY() - snapshotY;
                double dz = player.getZ() - snapshotZ;
                boolean jumped = (dx * dx + dy * dy + dz * dz) >= WORLD_CHANGE_DIST_SQ;

                if (jumped || System.currentTimeMillis() - waitStartedAt >= FALLBACK_TIMEOUT_MS) {
                    TokenLoginClient.LOGGER.info("SelfBan: skyblock world loaded (jumped={}), sending /hub", jumped);
                    phase = Phase.SEND_HUB;
                }
            }

            // ── /hub ───────────────────────────────────────────────────────
            case SEND_HUB -> {
                serverTransferDetected = false;
                hubWarpError = false;
                player.networkHandler.sendChatCommand("hub");
                waitStartedAt = System.currentTimeMillis();
                phase = Phase.WAIT_HUB_MSG;
                TokenLoginClient.LOGGER.info("SelfBan: sent /hub, waiting for transfer...");
            }

            case WAIT_HUB_MSG -> {
                if (hubWarpError) {
                    // "Couldn't warp you! Try again later."
                    hubWarpError = false;
                    hubRetries++;
                    retryUntil = System.currentTimeMillis() + HUB_RETRY_DELAY_MS;
                    phase = Phase.RETRY_HUB;
                    TokenLoginClient.LOGGER.info("SelfBan: hub warp error, retry #{} in 5s", hubRetries);
                    return;
                }

                if (serverTransferDetected) {
                    serverTransferDetected = false;
                    snapshotX = player.getX();
                    snapshotY = player.getY();
                    snapshotZ = player.getZ();
                    waitStartedAt = System.currentTimeMillis();
                    phase = Phase.WAIT_HUB_WORLD;
                    TokenLoginClient.LOGGER.info("SelfBan: hub transfer detected, waiting for world...");
                } else if (System.currentTimeMillis() - waitStartedAt >= FALLBACK_TIMEOUT_MS) {
                    // Nothing happened — probably already in hub
                    TokenLoginClient.LOGGER.info("SelfBan: no hub transfer, checking if already in hub");
                    phase = Phase.VERIFY_HUB;
                }
            }

            case RETRY_HUB -> {
                if (System.currentTimeMillis() >= retryUntil) {
                    phase = Phase.SEND_HUB;
                }
            }

            case WAIT_HUB_WORLD -> {
                double dx = player.getX() - snapshotX;
                double dy = player.getY() - snapshotY;
                double dz = player.getZ() - snapshotZ;
                boolean jumped = (dx * dx + dy * dy + dz * dz) >= WORLD_CHANGE_DIST_SQ;

                if (jumped || System.currentTimeMillis() - waitStartedAt >= FALLBACK_TIMEOUT_MS) {
                    TokenLoginClient.LOGGER.info("SelfBan: hub world loaded (jumped={}), verifying area", jumped);
                    phase = Phase.VERIFY_HUB;
                }
            }

            // ── Verify we're in hub before blasting ────────────────────────
            case VERIFY_HUB -> {
                if (isInHub()) {
                    TokenLoginClient.LOGGER.info("SelfBan: confirmed in hub, starting blast!");
                    blasting = true;
                    lastY = player.getY();
                    phase = Phase.BLASTING;
                } else {
                    String area = getAreaFromTab();
                    TokenLoginClient.LOGGER.info("SelfBan: not in hub (area={}), retrying /hub", area);
                    phase = Phase.SEND_HUB;
                }
            }

            // ── Blast everything ───────────────────────────────────────────
            case BLASTING -> {
                sendFly(mc, player);
                sendKillaura(mc, player);
                sendAutoclick(mc);
                sendInvalidRotation(mc, player);
            }
        }
    }

    // ── Blatant fly + speed ──────────────────────────────────────────────
    private static void sendFly(MinecraftClient mc, ClientPlayerEntity player) {
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYaw();
        float pitch = player.getPitch();

        mc.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.Full(
                        x + 3.0, y + 5.0, z, yaw, pitch,
                        false, player.horizontalCollision
                )
        );
        mc.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.Full(
                        x + 6.0, y + 10.0, z, yaw, pitch,
                        false, player.horizontalCollision
                )
        );
        mc.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.Full(
                        x + 6.0, y + 10.0, z, yaw, pitch,
                        true, player.horizontalCollision
                )
        );

        player.setPosition(x + 6.0, y + 10.0, z);
    }

    // ── Killaura — attack every entity within 100 blocks ─────────────────
    private static void sendKillaura(MinecraftClient mc, ClientPlayerEntity player) {
        for (Entity entity : mc.world.getEntities()) {
            if (entity == player) continue;
            if (player.squaredDistanceTo(entity) > 10000) continue;

            mc.getNetworkHandler().sendPacket(
                    PlayerInteractEntityC2SPacket.attack(entity, player.isSneaking())
            );
        }
    }

    // ── Autoclicker — spam arm swings (inhuman CPS) ──────────────────────
    private static void sendAutoclick(MinecraftClient mc) {
        for (int i = 0; i < 5; i++) {
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        }
    }

    // ── Invalid rotation — snap 180° every tick ──────────────────────────
    private static void sendInvalidRotation(MinecraftClient mc, ClientPlayerEntity player) {
        float snappedYaw = player.getYaw() + 180f;
        mc.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.LookAndOnGround(
                        snappedYaw, -90f, false, player.horizontalCollision
                )
        );
        mc.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.LookAndOnGround(
                        snappedYaw + 180f, 90f, false, player.horizontalCollision
                )
        );
    }
}
