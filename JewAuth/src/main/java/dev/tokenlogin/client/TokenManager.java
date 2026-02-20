package dev.tokenlogin.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;
import dev.tokenlogin.mixin.MinecraftClientAccessor;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.Scanner;
import java.util.UUID;

@Environment(EnvType.CLIENT)
public class TokenManager {

    /**
     * Holds result of a successful authentication.
     */
    public record SessionInfo(String username, UUID uuid, long expireEpochSeconds) {}

    /**
     * Holds expiry info decoded directly from a JWT without making any network call.
     */
    public record ExpiryInfo(long expireEpochSeconds, boolean expired, long secondsDifference) {
        /** How many seconds until expiry (positive) or since expiry (positive, use expired flag). */
        public String formatDuration() {
            long secs = secondsDifference;
            long hours   = secs / 3600;
            long minutes = (secs % 3600) / 60;
            long seconds = secs % 60;

            if (hours > 0)   return String.format("%dh %dm %ds", hours, minutes, seconds);
            if (minutes > 0) return String.format("%dm %ds", minutes, seconds);
            return String.format("%ds", seconds);
        }
    }

    // -------------------------------------------------------------------------
    // JWT decoding
    // -------------------------------------------------------------------------

    /**
     * Decodes the JWT payload and returns expiry info.
     * Returns null if the token is not a valid JWT.
     */
    public static ExpiryInfo decodeExpiry(String token) {
        if (token == null || token.isBlank()) return null;

        String[] parts = token.split("\\.");
        if (parts.length < 2) return null;

        try {
            // Add padding so Base64 decoder is happy
            String payload = parts[1];
            int mod = payload.length() % 4;
            if (mod != 0) payload += "=".repeat(4 - mod);

            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            String json = new String(decoded, StandardCharsets.UTF_8);

            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("exp")) return null;

            long exp = obj.get("exp").getAsLong();
            long now = System.currentTimeMillis() / 1000L;
            boolean expired = exp <= now;
            long diff = expired ? (now - exp) : (exp - now);

            return new ExpiryInfo(exp, expired, diff);

        } catch (Exception e) {
            TokenLoginClient.LOGGER.warn("Failed to decode JWT payload: {}", e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Authentication
    // -------------------------------------------------------------------------

    /**
     * Calls the Minecraft profile API with the given token and returns session info.
     * Throws an exception with a human-readable message on failure.
     *
     * This is a blocking network call — always call from a non-UI thread.
     */
    public static SessionInfo authenticate(String token) throws Exception {
        if (token == null || token.isBlank()) {
            throw new Exception("Token is empty");
        }

        // Quick sanity check: must be a JWT (header.payload.signature)
        if (token.split("\\.").length != 3) {
            throw new Exception("Not a valid JWT token");
        }

        URL url = new URL("https://api.minecraftservices.com/minecraft/profile");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);

        int responseCode = conn.getResponseCode();

        if (responseCode == 401) throw new Exception("Token rejected (401 Unauthorized)");
        if (responseCode == 403) throw new Exception("No Minecraft profile on this account");
        if (responseCode != 200) throw new Exception("API error (HTTP " + responseCode + ")");

        String body;
        try (InputStream stream = conn.getInputStream();
             Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8)) {
            scanner.useDelimiter("\\A");
            body = scanner.hasNext() ? scanner.next() : "";
        } finally {
            conn.disconnect();
        }

        JsonObject json = JsonParser.parseString(body).getAsJsonObject();

        if (!json.has("name") || !json.has("id")) {
            throw new Exception("Unexpected API response — missing name or id");
        }

        String username = json.get("name").getAsString();
        String rawUuid  = json.get("id").getAsString();

        // Minecraft profile UUIDs come without dashes — insert them
        UUID uuid = uuidFromTrimmed(rawUuid);

        return new SessionInfo(username, uuid, 0L);
    }

    // -------------------------------------------------------------------------
    // Session injection
    // -------------------------------------------------------------------------

    /**
     * Replaces the active Minecraft session with a new one built from the given token.
     * Must be called after a successful authenticate() call.
     */
    public static void applySession(SessionInfo info, String rawToken) {
        Session newSession = new Session(
                info.username(),
                info.uuid(),
                rawToken,
                Optional.empty(),
                Optional.empty()
        );

        MinecraftClient client = MinecraftClient.getInstance();
        ((MinecraftClientAccessor) client).tokenlogin$setSession(newSession);

        TokenLoginClient.LOGGER.info(
                "Session replaced — username: {}, uuid: {}",
                info.username(), info.uuid()
        );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Converts a trimmed (no-dash) UUID string to a UUID. */
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
