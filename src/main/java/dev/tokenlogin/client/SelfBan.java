package dev.tokenlogin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.BrandCustomPayload;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.network.packet.c2s.play.BoatPaddleStateC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PickItemFromEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdatePlayerAbilitiesC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * Selfban — toggle + profile-based packet blaster.
 *
 * Ported from the standalone "hypickle selfban" mod (Mojang/MC 26.1.2) and
 * translated to Yarn / MC 1.21.11.
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

        ServerInfo server = new ServerInfo(TARGET_SERVER_NAME, TARGET_SERVER_ADDRESS, ServerInfo.ServerType.OTHER);
        ConnectScreen.connect(
                parent,
                MinecraftClient.getInstance(),
                ServerAddress.parse(TARGET_SERVER_ADDRESS),
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

        MinecraftClient minecraft = MinecraftClient.getInstance();
        ClientPlayNetworkHandler connection = minecraft.getNetworkHandler();
        ClientPlayerEntity player = minecraft.player;
        if (connection == null || player == null) {
            if (reconnectAt > 0L && System.currentTimeMillis() >= reconnectAt) {
                reconnectAt = 0L;
                connect(minecraft.currentScreen);
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

    private static int runProfile(Profile profile, MinecraftClient minecraft, ClientPlayNetworkHandler connection, ClientPlayerEntity player, int budget) {
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

    private static void sendPreBlastCommand(ClientPlayNetworkHandler connection) {
        String command = PRE_BLAST_COMMAND.trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (!command.isEmpty()) {
            TokenLoginClient.LOGGER.info("SelfBan: sending pre-blast command /{}", command);
            connection.sendChatCommand(command);
        }
    }

    // ── Profiles ─────────────────────────────────────────────────────────────

    private static void openInventory(MinecraftClient minecraft, ClientPlayerEntity player) {
        if (!(minecraft.currentScreen instanceof InventoryScreen)) {
            minecraft.setScreen(new InventoryScreen(player));
        }
    }

    private static void sendClientBrand(ClientPlayNetworkHandler connection, int budget) {
        if (budget <= 0 || CLIENT_BRANDS.length == 0) {
            return;
        }

        String brand = CLIENT_BRANDS[brandIndex++ % CLIENT_BRANDS.length].trim();
        if (!brand.isEmpty()) {
            send(connection, new CustomPayloadC2SPacket(new BrandCustomPayload(brand)));
        }
    }

    private static void sendCreativeOnlyPackets(ClientPlayNetworkHandler connection, int budget) {
        // NOTE: the original also sent ServerboundChangeGameModePacket — no client→server
        // gamemode packet exists in 1.21.11, so only the creative-slot anomalies remain.
        if (budget-- > 0) {
            send(connection, new CreativeInventoryActionC2SPacket(36, new ItemStack(Items.DIAMOND_SWORD, 64)));
        }
        if (budget > 0) {
            send(connection, new CreativeInventoryActionC2SPacket(37, new ItemStack(Items.DIAMOND_PICKAXE, 64)));
        }
    }

    private static void sendFlightPackets(ClientPlayNetworkHandler connection, ClientPlayerEntity player, int budget) {
        if (budget-- > 0) {
            PlayerAbilities abilities = new PlayerAbilities();
            abilities.unpack(player.getAbilities().pack());
            abilities.flying = true;
            abilities.allowFlying = true;
            abilities.setFlySpeed(3.0F);
            send(connection, new UpdatePlayerAbilitiesC2SPacket(abilities));
        }
        if (budget-- > 0) {
            send(connection, new ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.START_FALL_FLYING, 0));
        }
        if (budget > 0) {
            send(connection, new PlayerMoveC2SPacket.Full(
                    player.getX(), player.getY() + 9.5, player.getZ(), player.getYaw(), player.getPitch(), false, player.horizontalCollision));
        }
    }

    private static void sendMovementAnomalies(ClientPlayNetworkHandler connection, ClientPlayerEntity player, int budget) {
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYaw();
        float pitch = player.getPitch();
        boolean collision = player.horizontalCollision;

        if (budget-- > 0) send(connection, new PlayerMoveC2SPacket.Full(x, y + 6.0, z, yaw, pitch, false, collision));
        if (budget-- > 0) send(connection, new PlayerMoveC2SPacket.Full(x + 8.0, y + 12.0, z, yaw, pitch, false, collision));
        if (budget-- > 0) send(connection, new PlayerMoveC2SPacket.Full(x - 8.0, y - 4.0, z, yaw, pitch, true, collision));
        if (budget > 0) send(connection, new PlayerMoveC2SPacket.Full(x + 30.0, y, z + 30.0, yaw, pitch, false, collision));
    }

    private static void sendRotationAnomalies(ClientPlayNetworkHandler connection, ClientPlayerEntity player, int budget) {
        boolean collision = player.horizontalCollision;
        float baseYaw = player.getYaw();

        if (budget-- > 0) send(connection, new PlayerMoveC2SPacket.LookAndOnGround(baseYaw + 720.0F, -90.0F, false, collision));
        if (budget-- > 0) send(connection, new PlayerMoveC2SPacket.LookAndOnGround(baseYaw - 720.0F, 90.0F, false, collision));
        if (budget > 0) send(connection, new PlayerMoveC2SPacket.LookAndOnGround(RANDOM.nextFloat() * 360.0F - 180.0F, RANDOM.nextBoolean() ? -90.0F : 90.0F, false, collision));
    }

    private static void sendCombatReach(MinecraftClient minecraft, ClientPlayNetworkHandler connection, ClientPlayerEntity player, int budget) {
        if (minecraft.world == null) {
            return;
        }

        for (Entity entity : minecraft.world.getEntities()) {
            if (budget <= 0) {
                return;
            }
            if (entity == player || player.squaredDistanceTo(entity) > 10000.0) {
                continue;
            }

            if (budget-- > 0) {
                send(connection, PlayerInteractEntityC2SPacket.attack(entity, player.isSneaking()));
            }
            if (budget-- > 0) {
                send(connection, PlayerInteractEntityC2SPacket.interact(entity, player.isSneaking(), Hand.MAIN_HAND));
            }
            if (budget-- > 0) {
                send(connection, new HandSwingC2SPacket(Hand.MAIN_HAND));
            }
        }
    }

    private static void sendFastActions(ClientPlayNetworkHandler connection, ClientPlayerEntity player, int budget) {
        BlockPos farPos = player.getBlockPos().add(12, 6, 12);
        if (budget-- > 0) send(connection, new HandSwingC2SPacket(Hand.MAIN_HAND));
        if (budget-- > 0) send(connection, new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, farPos, Direction.UP, sequence++));
        if (budget-- > 0) send(connection, new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, farPos, Direction.UP, sequence++));
        if (budget-- > 0) send(connection, new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN, sequence++));
        if (budget > 0) send(connection, new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, sequence++, player.getYaw() + 720.0F, -90.0F));
    }

    private static void sendEntityPickPackets(MinecraftClient minecraft, ClientPlayNetworkHandler connection, ClientPlayerEntity player, int budget) {
        // NOTE: the original also sent ServerboundSpectateEntityPacket — 1.21.11 has no
        // spectate-by-entity-id C2S packet, so only the pick anomaly remains.
        if (minecraft.world == null) {
            return;
        }

        for (Entity entity : minecraft.world.getEntities()) {
            if (budget <= 0) {
                return;
            }
            if (entity == player) {
                continue;
            }

            if (budget-- > 0) send(connection, new PickItemFromEntityC2SPacket(entity.getId(), true));
        }
    }

    private static void sendVehicleAnomalies(ClientPlayNetworkHandler connection, ClientPlayerEntity player, int budget) {
        Vec3d position = new Vec3d(player.getX() + 20.0, player.getY() + 8.0, player.getZ() - 20.0);
        if (budget-- > 0) send(connection, new VehicleMoveC2SPacket(position, player.getYaw() + 360.0F, -90.0F, false));
        if (budget > 0) send(connection, new BoatPaddleStateC2SPacket(true, true));
    }

    private static void send(ClientPlayNetworkHandler connection, Packet<?> packet) {
        connection.sendPacket(packet);
        packetsSentThisWindow++;
    }
}
