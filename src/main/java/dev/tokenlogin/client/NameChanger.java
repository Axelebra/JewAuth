package dev.tokenlogin.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;
import dev.tokenlogin.mixin.MinecraftClientAccessor;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Scanner;
import java.util.UUID;

/**
 * Handles real Mojang API name changes (IGN mode).
 * For client-side-only visual name hiding, see {@link NickHider}.
 */
@Environment(EnvType.CLIENT)
public class NameChanger {

    // ── Status state (read from UI) ──────────────────────────────────────────
    private static volatile String statusMessage = "";
    private static volatile int    statusColor   = 0xFF888888;

    public static String getStatusMessage() { return statusMessage; }
    public static int    getStatusColor()   { return statusColor; }

    public record ChangeResult(boolean success, String newName, String uuid) {}

    /**
     * Attempts to change the account's IGN via the Mojang API.
     * Uses the token from the current session.
     *
     * BLOCKING — always call from a background thread.
     */
    public static ChangeResult changeName(String newName) {
        MinecraftClient client = MinecraftClient.getInstance();
        String token = client.getSession().getAccessToken();

        if (token == null || token.isBlank()) {
            statusMessage = "No token loaded";
            statusColor   = 0xFFFF5555;
            return new ChangeResult(false, null, null);
        }

        if (newName == null || newName.isBlank()) {
            statusMessage = "Enter a name first";
            statusColor   = 0xFFFF5555;
            return new ChangeResult(false, null, null);
        }

        if (!newName.matches("^[a-zA-Z0-9_]{3,16}$")) {
            statusMessage = "Invalid name (3-16 chars, a-z/0-9/_)";
            statusColor   = 0xFFFF5555;
            return new ChangeResult(false, null, null);
        }

        statusMessage = "Changing name...";
        statusColor   = 0xFFFFAA00;

        try {
            URL url = new URL("https://api.minecraftservices.com/minecraft/profile/name/" + newName);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.flush();
            }

            int code = conn.getResponseCode();

            if (code == 200) {
                String body = readStream(conn.getInputStream());
                conn.disconnect();

                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                String name = json.has("name") ? json.get("name").getAsString() : newName;
                String id   = json.has("id")   ? json.get("id").getAsString()   : null;

                statusMessage = "Changed to: " + name;
                statusColor   = 0xFF55FF55;

                return new ChangeResult(true, name, id);
            }

            String errorBody = readStream(conn.getErrorStream());
            conn.disconnect();

            return switch (code) {
                case 400 -> {
                    statusMessage = "Invalid name";
                    statusColor   = 0xFFFF5555;
                    yield new ChangeResult(false, null, null);
                }
                case 401 -> {
                    statusMessage = "Token expired or invalid";
                    statusColor   = 0xFFFF5555;
                    yield new ChangeResult(false, null, null);
                }
                case 403 -> {
                    String detail = parseForbiddenReason(errorBody);
                    statusMessage = detail;
                    statusColor   = 0xFFFF5555;
                    yield new ChangeResult(false, null, null);
                }
                case 429 -> {
                    statusMessage = "Rate limited — try later";
                    statusColor   = 0xFFFF5555;
                    yield new ChangeResult(false, null, null);
                }
                default -> {
                    statusMessage = "API error (HTTP " + code + ")";
                    statusColor   = 0xFFFF5555;
                    yield new ChangeResult(false, null, null);
                }
            };

        } catch (Exception e) {
            TokenLoginClient.LOGGER.warn("Name change failed: {}", e.getMessage());
            statusMessage = "Error: " + e.getMessage();
            statusColor   = 0xFFFF5555;
            return new ChangeResult(false, null, null);
        }
    }

    /**
     * After a successful API name change, update the local session to reflect it.
     */
    public static void applyNewName(String newName, String rawUuid) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Session old = mc.getSession();

        UUID uuid;
        if (rawUuid != null && !rawUuid.isEmpty()) {
            uuid = uuidFromTrimmed(rawUuid);
        } else {
            uuid = old.getUuidOrNull();
        }

        Session updated = new Session(
                newName,
                uuid,
                old.getAccessToken(),
                Optional.empty(),
                Optional.empty()
        );

        ((MinecraftClientAccessor) mc).tokenlogin$setSession(updated);
        TokenLoginClient.LOGGER.info("Local session updated — new name: {}", newName);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String parseForbiddenReason(String body) {
        if (body == null || body.isBlank()) return "Name change forbidden";
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("details")) {
                JsonObject details = json.getAsJsonObject("details");
                if (details.has("status")) {
                    String status = details.get("status").getAsString();
                    if ("DUPLICATE".equalsIgnoreCase(status)) {
                        return "Username already taken";
                    }
                    if ("NOT_ALLOWED".equalsIgnoreCase(status)) {
                        return "Name not allowed";
                    }
                }
            }
            if (json.has("errorMessage")) {
                return json.get("errorMessage").getAsString();
            }
        } catch (Exception ignored) {}
        return "Name change on cooldown (30 days)";
    }

    private static String readStream(InputStream stream) {
        if (stream == null) return "";
        try (Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8)) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    private static UUID uuidFromTrimmed(String trimmed) {
        if (trimmed.contains("-")) return UUID.fromString(trimmed);
        return UUID.fromString(
                trimmed.replaceAll(
                        "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                        "$1-$2-$3-$4-$5"
                )
        );
    }
}