package dev.tokenlogin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;

/**
 * Selfban — toggle + multi-cheat packet sender.
 *
 * Button flow: OFF → press → "Sure?" → press → ON → press → OFF
 * When ON + in-game:
 *   1. Sends /skyblock
 *   2. Waits for load
 *   3. Every tick sends a mix of:
 *      - Blatant fly/speed (movement)
 *      - Killaura (attack all nearby entities from any distance)
 *      - Autoclicker (spam arm swings)
 *      - Invalid rotations (snap 180° every tick)
 *      - Timer (extra movement packets to simulate speedhack)
 */
@Environment(EnvType.CLIENT)
public class SelfBan {

    public enum State { OFF, CONFIRMING, ON }

    private static volatile State state = State.OFF;
    private static int tickCounter = 0;
    private static boolean sentCommand = false;
    private static boolean blasting = false;
    private static double lastY = 0;
    private static int lobbyDetectTicks = 0;

    private static final int WAIT_TICKS = 80;

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
        tickCounter = 0;
        sentCommand = false;
        blasting = false;
        lastY = 0;
        lobbyDetectTicks = 0;
    }

    public static void tick() {
        if (!isEnabled()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.getNetworkHandler() == null) return;

        ClientPlayerEntity player = mc.player;

        // Detect lobby kick
        if (blasting) {
            double currentY = player.getY();
            if (Math.abs(currentY - lastY) > 50) {
                lobbyDetectTicks++;
                if (lobbyDetectTicks > 5) {
                    TokenLoginClient.LOGGER.info("SelfBan: lobby kick detected, re-queuing");
                    sentCommand = false;
                    blasting = false;
                    tickCounter = 0;
                    lobbyDetectTicks = 0;
                }
            } else {
                lobbyDetectTicks = 0;
            }
            lastY = currentY;
        }

        // Step 1: /skyblock
        if (!sentCommand) {
            player.networkHandler.sendChatCommand("skyblock");
            sentCommand = true;
            tickCounter = 0;
            return;
        }

        // Step 2: wait
        tickCounter++;
        if (tickCounter < WAIT_TICKS) return;

        // Step 3: blast everything
        blasting = true;
        lastY = player.getY();

        sendFly(mc, player);
        sendKillaura(mc, player);
        sendAutoclick(mc);
        sendInvalidRotation(mc, player);
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
            if (player.squaredDistanceTo(entity) > 10000) continue; // 100 block range

            // Attack from impossible distance = killaura flag
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