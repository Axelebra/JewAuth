package dev.tokenlogin.client;

import com.google.gson.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Fetches Skyblock profile data from the Hypixel API.
 * Returns SB level, coop status (filtering out members who left).
 *
 * Endpoint: GET https://api.hypixel.net/v2/skyblock/profiles?uuid={uuid}
 * Header:   API-Key: {key}
 */
@Environment(EnvType.CLIENT)
public class SkyblockFetcher {

    public record SkyblockInfo(
            /** Skyblock level on the highest-level profile. */
            int skyblockLevel,
            /** True if the highest-level profile has more than 1 active member. */
            boolean isCoop,
            /** Number of active (not left) coop members. 1 = solo. */
            int coopMembers,
            /** Profile cute name (Banana, Strawberry, etc.). */
            String profileName,
            /** Error message, or null if success. */
            String error
    ) {
        public static SkyblockInfo error(String msg) {
            return new SkyblockInfo(0, false, 0, "", msg);
        }
    }

    /**
     * Fetches skyblock profile data for the given UUID.
     * Returns the profile with the highest SB level.
     * Coop members who have left (coop_invitation not accepted, or deleted) are excluded.
     *
     * Blocking — call from a background thread.
     */
    public static SkyblockInfo fetch(String uuid, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return SkyblockInfo.error("No API key");
        if (uuid == null || uuid.isBlank()) return SkyblockInfo.error("No UUID");

        String cleanUuid = uuid.replace("-", "");

        try {
            URL url = new URL("https://api.hypixel.net/v2/skyblock/profiles?uuid=" + cleanUuid);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("API-Key", apiKey);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            int code = conn.getResponseCode();
            if (code == 403) return SkyblockInfo.error("Invalid API key");
            if (code == 429) return SkyblockInfo.error("Rate limited");
            if (code == 404) return SkyblockInfo.error("Not found");

            String body;
            try (InputStream stream = (code < 400) ? conn.getInputStream() : conn.getErrorStream();
                 Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8)) {
                scanner.useDelimiter("\\A");
                body = scanner.hasNext() ? scanner.next() : "{}";
            } finally {
                conn.disconnect();
            }

            if (code != 200) return SkyblockInfo.error("HTTP " + code);

            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("success") || !json.get("success").getAsBoolean()) {
                String cause = json.has("cause") ? json.get("cause").getAsString() : "Unknown";
                return SkyblockInfo.error(cause);
            }

            if (!json.has("profiles") || json.get("profiles").isJsonNull()) {
                return SkyblockInfo.error("No SB profiles");
            }

            JsonArray profiles = json.getAsJsonArray("profiles");
            if (profiles.isEmpty()) return SkyblockInfo.error("No SB profiles");

            int bestLevel = -1;
            boolean bestCoop = false;
            int bestCoopCount = 0;
            String bestName = "";

            for (JsonElement profEl : profiles) {
                JsonObject profile = profEl.getAsJsonObject();
                JsonObject members = profile.has("members") ? profile.getAsJsonObject("members") : new JsonObject();

                // Get this player's SB level
                int sbLevel = 0;
                if (members.has(cleanUuid) && members.get(cleanUuid).isJsonObject()) {
                    JsonObject member = members.getAsJsonObject(cleanUuid);
                    if (member.has("leveling") && member.get("leveling").isJsonObject()) {
                        JsonObject leveling = member.getAsJsonObject("leveling");
                        if (leveling.has("experience")) {
                            sbLevel = (int) (leveling.get("experience").getAsDouble() / 100.0);
                        }
                    }
                }

                // Count active coop members (filter out those who left)
                int activeMembers = 0;
                for (var entry : members.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    JsonObject memberObj = entry.getValue().getAsJsonObject();

                    // Check coop_invitation — if it exists and confirmed=false, they left or were never accepted
                    if (memberObj.has("coop_invitation") && memberObj.get("coop_invitation").isJsonObject()) {
                        JsonObject invite = memberObj.getAsJsonObject("coop_invitation");
                        if (invite.has("confirmed") && !invite.get("confirmed").getAsBoolean()) {
                            continue; // not confirmed = left or pending
                        }
                    }

                    // Check if the member's profile is deleted
                    if (memberObj.has("profile") && memberObj.get("profile").isJsonObject()) {
                        JsonObject profileData = memberObj.getAsJsonObject("profile");
                        if (profileData.has("deletion_notice") && profileData.get("deletion_notice").isJsonObject()) {
                            continue; // profile deleted = left
                        }
                    }

                    activeMembers++;
                }

                String cuteName = profile.has("cute_name") ? profile.get("cute_name").getAsString() : "";

                if (sbLevel > bestLevel) {
                    bestLevel = sbLevel;
                    bestCoop = activeMembers > 1;
                    bestCoopCount = activeMembers;
                    bestName = cuteName;
                }
            }

            if (bestLevel < 0) bestLevel = 0;
            return new SkyblockInfo(bestLevel, bestCoop, bestCoopCount, bestName, null);

        } catch (Exception e) {
            TokenLoginClient.LOGGER.debug("SkyblockFetcher error for {}: {}", uuid, e.getMessage());
            return SkyblockInfo.error(e.getMessage() != null ? e.getMessage() : "Unknown error");
        }
    }
}
