package dev.tokenlogin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;

import java.util.Collection;
import java.util.Random;

/**
 * Selfban — toggle + multi-cheat packet sender.
 *
 * Button flow: OFF → press → "Sure?" → press → ON (auto-connects to Hypixel)
 * When ON + in-game:
 *   1. Wait 1s
 *   2. Send /skyblock, keep retrying every 5s until Area: appears in tab
 *   3. If Area: Hub  → blast immediately
 *      If Area: other → send /hub, retry /hub every 5s until Area: Hub
 *   4. Blast until kicked — then disable, never re-queue
 */
@Environment(EnvType.CLIENT)
public class SelfBan {

    public enum State { OFF, CONFIRMING, ON }

    private enum Phase {
        INITIAL_WAIT,
        SEND_SKYBLOCK,
        WAIT_FOR_AREA,
        SEND_HUB,
        WAIT_FOR_HUB,
        BLASTING
    }

    private static final Random RAND = new Random();

    private static final long INITIAL_DELAY_MS  = 1000L;
    private static final long SKYBLOCK_RETRY_MS = 5000L;
    private static final long HUB_RETRY_MS      = 5000L;

    private static volatile State state = State.OFF;
    private static Phase phase           = Phase.INITIAL_WAIT;
    private static boolean blasting      = false;
    private static int lobbyDetectTicks  = 0;
    private static long waitStartedAt    = 0L;

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
        TokenLoginClient.LOGGER.info("SelfBan disabled");
    }

    private static void reset() {
        phase           = Phase.INITIAL_WAIT;
        blasting        = false;
        lobbyDetectTicks = 0;
        waitStartedAt   = 0L;
    }

    // ── Tab list helpers ───────────────────────────────────────────────────

    private static String getAreaFromTab() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getNetworkHandler() == null) return null;
        Collection<PlayerListEntry> entries = mc.getNetworkHandler().getPlayerList();
        for (PlayerListEntry entry : entries) {
            if (entry.getDisplayName() == null) continue;
            String text = entry.getDisplayName().getString().trim();
            if (text.startsWith("Area: ")) return text.substring(6).trim();
        }
        return null;
    }

    private static boolean isInHub() {
        String area = getAreaFromTab();
        return area != null && area.equalsIgnoreCase("Hub");
    }

    // ── Tick ───────────────────────────────────────────────────────────────

    public static void tick() {
        if (!isEnabled()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;

        // Null during connecting or server transfer — just wait, DisconnectedScreenMixin handles real disconnects
        if (mc.getNetworkHandler() == null || mc.player == null) return;
        ClientPlayerEntity player = mc.player;

        // While blasting: if we leave Hub for 10+ consecutive ticks, stop completely
        if (blasting) {
            if (!isInHub()) {
                if (++lobbyDetectTicks > 10) {
                    TokenLoginClient.LOGGER.info("SelfBan: left Hub, stopping");
                    disable();
                }
            } else {
                lobbyDetectTicks = 0;
            }
        }

        switch (phase) {

            case INITIAL_WAIT -> {
                if (waitStartedAt == 0L) waitStartedAt = System.currentTimeMillis();
                if (System.currentTimeMillis() - waitStartedAt >= INITIAL_DELAY_MS) {
                    waitStartedAt = 0L;
                    phase = Phase.SEND_SKYBLOCK;
                }
            }

            case SEND_SKYBLOCK -> {
                TokenLoginClient.LOGGER.info("SelfBan: sending /skyblock");
                player.networkHandler.sendChatCommand("skyblock");
                waitStartedAt = System.currentTimeMillis();
                phase = Phase.WAIT_FOR_AREA;
            }

            case WAIT_FOR_AREA -> {
                String area = getAreaFromTab();
                if (area != null) {
                    // Area: appeared — we're in Skyblock
                    waitStartedAt = 0L;
                    if (area.equalsIgnoreCase("Hub")) {
                        TokenLoginClient.LOGGER.info("SelfBan: Area: Hub detected, blasting!");
                        blasting = true;
                        phase = Phase.BLASTING;
                    } else {
                        TokenLoginClient.LOGGER.info("SelfBan: Area: {} — sending /hub", area);
                        phase = Phase.SEND_HUB;
                    }
                } else if (System.currentTimeMillis() - waitStartedAt >= SKYBLOCK_RETRY_MS) {
                    // No Area: yet after 5s — retry /skyblock
                    TokenLoginClient.LOGGER.info("SelfBan: no Area: yet, retrying /skyblock");
                    phase = Phase.SEND_SKYBLOCK;
                }
            }

            case SEND_HUB -> {
                TokenLoginClient.LOGGER.info("SelfBan: sending /hub");
                player.networkHandler.sendChatCommand("hub");
                waitStartedAt = System.currentTimeMillis();
                phase = Phase.WAIT_FOR_HUB;
            }

            case WAIT_FOR_HUB -> {
                if (isInHub()) {
                    TokenLoginClient.LOGGER.info("SelfBan: Area: Hub — blasting!");
                    waitStartedAt = 0L;
                    blasting = true;
                    phase = Phase.BLASTING;
                } else if (System.currentTimeMillis() - waitStartedAt >= HUB_RETRY_MS) {
                    TokenLoginClient.LOGGER.info("SelfBan: not in Hub yet, retrying /hub");
                    phase = Phase.SEND_HUB;
                }
            }

            case BLASTING -> {
                sendFly(mc, player);
                sendKillaura(mc, player);
                sendAutoclick(mc);
                sendInvalidRotation(mc, player);
                sendRandomPackets(mc, player);
            }
        }
    }

    // ── Blast methods ──────────────────────────────────────────────────────

    private static void sendFly(MinecraftClient mc, ClientPlayerEntity player) {
        double x = player.getX(), y = player.getY(), z = player.getZ();
        float  yaw = player.getYaw(), pitch = player.getPitch();

        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                x + 3.0, y + 5.0, z, yaw, pitch, false, player.horizontalCollision));
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                x + 6.0, y + 10.0, z, yaw, pitch, false, player.horizontalCollision));
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                x + 6.0, y + 10.0, z, yaw, pitch, true, player.horizontalCollision));

        player.setPosition(x + 6.0, y + 10.0, z);
    }

    private static void sendKillaura(MinecraftClient mc, ClientPlayerEntity player) {
        for (Entity entity : mc.world.getEntities()) {
            if (entity == player) continue;
            if (player.squaredDistanceTo(entity) > 10000) continue;
            mc.getNetworkHandler().sendPacket(
                    PlayerInteractEntityC2SPacket.attack(entity, player.isSneaking()));
        }
    }

    private static void sendAutoclick(MinecraftClient mc) {
        for (int i = 0; i < 5; i++) {
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        }
    }

    private static void sendInvalidRotation(MinecraftClient mc, ClientPlayerEntity player) {
        float snappedYaw = player.getYaw() + 180f;
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                snappedYaw, -90f, false, player.horizontalCollision));
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                snappedYaw + 180f, 90f, false, player.horizontalCollision));
    }

    private static void sendRandomPackets(MinecraftClient mc, ClientPlayerEntity player) {
        double x = player.getX(), y = player.getY(), z = player.getZ();
        float  yaw = player.getYaw(), pitch = player.getPitch();

        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                x + (RAND.nextDouble() - 0.5) * 2,
                y + (RAND.nextDouble() - 0.5) * 2,
                z + (RAND.nextDouble() - 0.5) * 2,
                yaw, pitch, false, player.horizontalCollision));

        ClientCommandC2SPacket.Mode sprint = RAND.nextBoolean()
                ? ClientCommandC2SPacket.Mode.START_SPRINTING
                : ClientCommandC2SPacket.Mode.STOP_SPRINTING;
        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(player, sprint, 0));

        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                RAND.nextFloat() * 360f - 180f,
                RAND.nextFloat() * 180f - 90f,
                false, player.horizontalCollision));
    }
}
