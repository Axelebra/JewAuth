package dev.tokenlogin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Selfban — toggle + profile-based packet blaster.
 *
 * Ported from the standalone "hypickle selfban" mod and translated to Mojang
 * mappings / MC 26.x.
 *
 * Button flow: OFF → press → "Sure?" → press → ON (auto-connects to Hypixel).
 * When ON + in-game:
 *   1. Wait {@link #INITIAL_DELAY_MS}.
 *   2. Send the pre-blast command ({@code /play bedwars_eight_one}).
 *   3. Wait {@link #PRE_BLAST_DELAY_MS}.
 *   4. Blast a rotating, rate-limited sequence of cheat "profiles" until disconnected.
 * On disconnect, reconnects automatically unless a ban keyword is detected.
 */
@Environment(EnvType.CLIENT)
public final class SelfBan {

    public enum State { OFF, CONFIRMING, ON }

    private enum Profile {
        CLIENT_BRAND,
        CREATIVE_ONLY,
        FLIGHT,
        MOVEMENT,
        ROTATION,
        COMBAT_REACH,
        FAST_ACTIONS,
        INVENTORY_OPEN,
        ENTITY_PICK,
        VEHICLE
    }

    private static final Profile[] BLAST_SEQUENCE = {
            Profile.CLIENT_BRAND,
            Profile.ROTATION,
            Profile.CLIENT_BRAND,
            Profile.ROTATION,
            Profile.FLIGHT,
            Profile.INVENTORY_OPEN,
            Profile.CLIENT_BRAND,
            Profile.CREATIVE_ONLY,
            Profile.ROTATION,
            Profile.COMBAT_REACH,
            Profile.INVENTORY_OPEN,
            Profile.FAST_ACTIONS,
            Profile.ROTATION,
            Profile.MOVEMENT,
            Profile.ENTITY_PICK,
            Profile.VEHICLE
    };

    private static final Random RANDOM = new Random();
    private static final long INITIAL_DELAY_MS = Long.getLong("selfban.initialDelayMs", 1000L);
    private static final String PRE_BLAST_COMMAND = System.getProperty("selfban.preBlastCommand", "/play bedwars_eight_one");
    private static final long PRE_BLAST_DELAY_MS = Long.getLong("selfban.preBlastDelayMs", 15000L);
    private static final int MAX_PACKETS_PER_SECOND = Integer.getInteger("selfban.maxPacketsPerSecond", 24);
    private static final String TARGET_SERVER_NAME = System.getProperty("selfban.serverName", "hypickle");
    private static final String TARGET_SERVER_ADDRESS = System.getProperty("selfban.serverAddress", "hypixel.net:25565");
    private static final long RECONNECT_DELAY_MS = Long.getLong("selfban.reconnectDelayMs", 2000L);
    private static final int MAX_RECONNECT_ATTEMPTS = Integer.getInteger("selfban.maxReconnectAttempts", 0);
    private static final String[] CLIENT_BRANDS = System.getProperty("selfban.clientBrands", "Wurst,Vape")
            .split(",");
    private static final String[] BAN_KEYWORDS = System.getProperty("selfban.banKeywords", "ban,banned")
            .toLowerCase()
            .split(",");

    private static volatile State state = State.OFF;
    private static long enabledAt = 0L;
    private static long blastReadyAt = 0L;
    private static long reconnectAt = 0L;
    private static long rateWindowStartedAt = 0L;
    private static int packetsSentThisWindow = 0;
    private static int profileIndex = 0;
    private static int brandIndex = 0;
    private static int sequence = 0;
    private static int reconnectAttempts = 0;
    private static boolean preBlastCommandSent = false;

    private SelfBan() {
    }

    // ── Public API ─────────────────────────────────────────────────────────

    public static boolean isEnabled() {
        return state == State.ON;
    }

    public static State getState() {
        return state;
    }

    public static String getTargetServerName() {
        return TARGET_SERVER_NAME;
    }

    public static String getTargetServerAddress() {
        return TARGET_SERVER_ADDRESS;
    }

    public static void connect(Screen parent) {
        state = State.ON;
        resetRuntime();
        enabledAt = System.currentTimeMillis();

        ServerData server = new ServerData(TARGET_SERVER_NAME, TARGET_SERVER_ADDRESS, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(
                parent,
                Minecraft.getInstance(),
                ServerAddress.parseString(TARGET_SERVER_ADDRESS),
                server,
                false,
                null);
        TokenLoginClient.LOGGER.info("SelfBan connecting to {}", TARGET_SERVER_ADDRESS);
    }

    public static State toggle() {
        state = switch (state) {
            case OFF -> State.CONFIRMING;
            case CONFIRMING -> State.ON;
            case ON -> State.OFF;
        };

        if (state == State.ON) {
            resetRuntime();
            enabledAt = System.currentTimeMillis();
        } else {
            resetRuntime();
        }

        TokenLoginClient.LOGGER.info("SelfBan state: {}", state);
        return state;
    }

    public static void disable() {
        state = State.OFF;
        resetRuntime();
        TokenLoginClient.LOGGER.info("SelfBan disabled");
    }

    public static void handleDisconnect(String reason) {
        if (!isEnabled()) {
            return;
        }

        String normalized = reason == null ? "" : reason.toLowerCase();
        if (containsBanKeyword(normalized)) {
            TokenLoginClient.LOGGER.info("SelfBan: ban-like disconnect detected, stopping reconnect loop: {}", reason);
            disable();
            return;
        }

        if (MAX_RECONNECT_ATTEMPTS > 0 && reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            TokenLoginClient.LOGGER.info("SelfBan: max reconnect attempts reached ({})", MAX_RECONNECT_ATTEMPTS);
            disable();
            return;
        }

        reconnectAttempts++;
        reconnectAt = System.currentTimeMillis() + RECONNECT_DELAY_MS;
        resetBlastState();
        TokenLoginClient.LOGGER.info("SelfBan: non-ban disconnect, reconnecting in {} ms: {}", RECONNECT_DELAY_MS, reason);
    }

    // ── Tick ───────────────────────────────────────────────────────────────

    public static void tick() {
        if (!isEnabled()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        LocalPlayer player = minecraft.player;
        if (connection == null || player == null) {
            if (reconnectAt > 0L && System.currentTimeMillis() >= reconnectAt) {
                reconnectAt = 0L;
                connect(Screens.current(minecraft));
            }
            return;
        }

        if (System.currentTimeMillis() - enabledAt < INITIAL_DELAY_MS) {
            return;
        }

        long now = System.currentTimeMillis();
        if (!preBlastCommandSent) {
            sendPreBlastCommand(connection);
            preBlastCommandSent = true;
            blastReadyAt = now + PRE_BLAST_DELAY_MS;
            TokenLoginClient.LOGGER.info("SelfBan: waiting {} ms before blast", PRE_BLAST_DELAY_MS);
            return;
        }

        if (now < blastReadyAt) {
            return;
        }

        int budget = packetBudget();
        while (budget > 0) {
            Profile profile = BLAST_SEQUENCE[profileIndex++ % BLAST_SEQUENCE.length];
            budget -= runProfile(profile, minecraft, connection, player, budget);
        }
    }

    // ── Runtime state helpers ────────────────────────────────────────────────

    private static void resetRuntime() {
        enabledAt = 0L;
        blastReadyAt = 0L;
        reconnectAt = 0L;
        reconnectAttempts = 0;
        resetBlastState();
    }

    private static void resetBlastState() {
        rateWindowStartedAt = 0L;
        packetsSentThisWindow = 0;
        profileIndex = 0;
        brandIndex = 0;
        sequence = 0;
        preBlastCommandSent = false;
    }

    private static boolean containsBanKeyword(String reason) {
        for (String keyword : BAN_KEYWORDS) {
            String trimmed = keyword.trim();
            if (!trimmed.isEmpty() && reason.contains(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private static int packetBudget() {
        long now = System.currentTimeMillis();
        if (rateWindowStartedAt == 0L || now - rateWindowStartedAt >= 1000L) {
            rateWindowStartedAt = now;
            packetsSentThisWindow = 0;
        }

        int remainingThisSecond = Math.max(0, MAX_PACKETS_PER_SECOND - packetsSentThisWindow);
        int perTickCap = Math.max(1, (int) Math.ceil(MAX_PACKETS_PER_SECOND / 20.0));
        return Math.min(remainingThisSecond, perTickCap);
    }

    private static int runProfile(Profile profile, Minecraft minecraft, ClientPacketListener connection, LocalPlayer player, int budget) {
        int before = packetsSentThisWindow;

        switch (profile) {
            case CLIENT_BRAND -> sendClientBrand(connection, budget);
            case CREATIVE_ONLY -> sendCreativeOnlyPackets(connection, budget);
            case FLIGHT -> sendFlightPackets(connection, player, budget);
            case MOVEMENT -> sendMovementAnomalies(connection, player, budget);
            case ROTATION -> sendRotationAnomalies(connection, player, budget);
            case COMBAT_REACH -> sendCombatReach(minecraft, connection, player, budget);
            case FAST_ACTIONS -> sendFastActions(connection, player, budget);
            case INVENTORY_OPEN -> openInventory(minecraft, player);
            case ENTITY_PICK -> sendEntityPickPackets(minecraft, connection, player, budget);
            case VEHICLE -> sendVehicleAnomalies(connection, player, budget);
        }

        int sent = packetsSentThisWindow - before;
        return sent == 0 ? budget : sent;
    }

    private static void sendPreBlastCommand(ClientPacketListener connection) {
        String command = PRE_BLAST_COMMAND.trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (!command.isEmpty()) {
            TokenLoginClient.LOGGER.info("SelfBan: sending pre-blast command /{}", command);
            connection.sendCommand(command);
        }
    }

    // ── Profiles ─────────────────────────────────────────────────────────────

    private static void openInventory(Minecraft minecraft, LocalPlayer player) {
        if (!(Screens.current(minecraft) instanceof InventoryScreen)) {
            minecraft.setScreenAndShow(new InventoryScreen(player));
        }
    }

    private static void sendClientBrand(ClientPacketListener connection, int budget) {
        if (budget <= 0 || CLIENT_BRANDS.length == 0) {
            return;
        }

        String brand = CLIENT_BRANDS[brandIndex++ % CLIENT_BRANDS.length].trim();
        if (!brand.isEmpty()) {
            send(connection, new ServerboundCustomPayloadPacket(new BrandPayload(brand)));
        }
    }

    private static void sendCreativeOnlyPackets(ClientPacketListener connection, int budget) {
        // NOTE: the original also sent a gamemode packet — no client→server
        // gamemode packet exists, so only the creative-slot anomalies remain.
        if (budget-- > 0) {
            send(connection, new ServerboundSetCreativeModeSlotPacket(36, new ItemStack(Items.DIAMOND_SWORD, 64)));
        }
        if (budget > 0) {
            send(connection, new ServerboundSetCreativeModeSlotPacket(37, new ItemStack(Items.DIAMOND_PICKAXE, 64)));
        }
    }

    private static void sendFlightPackets(ClientPacketListener connection, LocalPlayer player, int budget) {
        if (budget-- > 0) {
            Abilities abilities = new Abilities();
            abilities.apply(player.getAbilities().pack());
            abilities.flying = true;
            abilities.mayfly = true;
            abilities.setFlyingSpeed(3.0F);
            send(connection, new ServerboundPlayerAbilitiesPacket(abilities));
        }
        if (budget-- > 0) {
            send(connection, new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING, 0));
        }
        if (budget > 0) {
            send(connection, new ServerboundMovePlayerPacket.PosRot(
                    player.getX(), player.getY() + 9.5, player.getZ(), player.getYRot(), player.getXRot(), false, player.horizontalCollision));
        }
    }

    private static void sendMovementAnomalies(ClientPacketListener connection, LocalPlayer player, int budget) {
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        boolean collision = player.horizontalCollision;

        if (budget-- > 0) send(connection, new ServerboundMovePlayerPacket.PosRot(x, y + 6.0, z, yaw, pitch, false, collision));
        if (budget-- > 0) send(connection, new ServerboundMovePlayerPacket.PosRot(x + 8.0, y + 12.0, z, yaw, pitch, false, collision));
        if (budget-- > 0) send(connection, new ServerboundMovePlayerPacket.PosRot(x - 8.0, y - 4.0, z, yaw, pitch, true, collision));
        if (budget > 0) send(connection, new ServerboundMovePlayerPacket.PosRot(x + 30.0, y, z + 30.0, yaw, pitch, false, collision));
    }

    private static void sendRotationAnomalies(ClientPacketListener connection, LocalPlayer player, int budget) {
        boolean collision = player.horizontalCollision;
        float baseYaw = player.getYRot();

        if (budget-- > 0) send(connection, new ServerboundMovePlayerPacket.Rot(baseYaw + 720.0F, -90.0F, false, collision));
        if (budget-- > 0) send(connection, new ServerboundMovePlayerPacket.Rot(baseYaw - 720.0F, 90.0F, false, collision));
        if (budget > 0) send(connection, new ServerboundMovePlayerPacket.Rot(RANDOM.nextFloat() * 360.0F - 180.0F, RANDOM.nextBoolean() ? -90.0F : 90.0F, false, collision));
    }

    private static void sendCombatReach(Minecraft minecraft, ClientPacketListener connection, LocalPlayer player, int budget) {
        if (minecraft.level == null) {
            return;
        }

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (budget <= 0) {
                return;
            }
            if (entity == player || player.distanceToSqr(entity) > 10000.0) {
                continue;
            }

            if (budget-- > 0) {
                send(connection, new ServerboundAttackPacket(entity.getId()));
            }
            if (budget-- > 0) {
                send(connection, new ServerboundInteractPacket(entity.getId(), InteractionHand.MAIN_HAND, entity.position(), player.isShiftKeyDown()));
            }
            if (budget-- > 0) {
                send(connection, new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            }
        }
    }

    private static void sendFastActions(ClientPacketListener connection, LocalPlayer player, int budget) {
        BlockPos farPos = player.blockPosition().offset(12, 6, 12);
        if (budget-- > 0) send(connection, new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        if (budget-- > 0) send(connection, new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, farPos, Direction.UP, sequence++));
        if (budget-- > 0) send(connection, new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, farPos, Direction.UP, sequence++));
        if (budget-- > 0) send(connection, new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN, sequence++));
        if (budget > 0) send(connection, new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence++, player.getYRot() + 720.0F, -90.0F));
    }

    private static void sendEntityPickPackets(Minecraft minecraft, ClientPacketListener connection, LocalPlayer player, int budget) {
        // NOTE: the original also sent a spectate packet — no spectate-by-entity-id
        // C2S packet exists, so only the pick anomaly remains.
        if (minecraft.level == null) {
            return;
        }

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (budget <= 0) {
                return;
            }
            if (entity == player) {
                continue;
            }

            if (budget-- > 0) send(connection, new ServerboundPickItemFromEntityPacket(entity.getId(), true));
        }
    }

    private static void sendVehicleAnomalies(ClientPacketListener connection, LocalPlayer player, int budget) {
        Vec3 position = new Vec3(player.getX() + 20.0, player.getY() + 8.0, player.getZ() - 20.0);
        if (budget-- > 0) send(connection, new ServerboundMoveVehiclePacket(position, player.getYRot() + 360.0F, -90.0F, false));
        if (budget > 0) send(connection, new ServerboundPaddleBoatPacket(true, true));
    }

    private static void send(ClientPacketListener connection, Packet<?> packet) {
        connection.send(packet);
        packetsSentThisWindow++;
    }
}
