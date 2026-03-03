package dev.tokenlogin.client;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Manages the mod's own storage: a dead list (accounts the user dismissed),
 * a token cache (updated JWTs and refresh tokens after a successful refresh),
 * and per-account notes.
 *
 * Source files (launcher accounts.json etc.) are NEVER modified.
 * Everything written here goes to .minecraft/config/tokenlogin/.cache.json
 */
public class AccountStorage {

    private static final Path BASE_DIR = FabricLoader.getInstance()
            .getConfigDir().resolve("tokenlogin");
    private static final Path CACHE_FILE = BASE_DIR.resolve(".cache.json");

    /** deadKey strings for accounts the user has dismissed with the X button. */
    private static final Set<String> deadList   = new HashSet<>();

    /** deadKey → latest token snapshot saved by the mod after a refresh. */
    private static final Map<String, CachedTokens> tokenCache = new HashMap<>();

    /** deadKey → user-editable notes. */
    private static final Map<String, String> notesMap = new HashMap<>();

    public record CachedTokens(
            String minecraftToken,
            String refreshToken,    // may be null for JWT-only accounts
            long   lastRefreshed    // epoch seconds
    ) {}

    // ── Public API ────────────────────────────────────────────────────────────

    public static Path getBaseDir() { return BASE_DIR; }

    public static boolean isDead(AccountEntry entry) {
        return deadList.contains(entry.deadKey());
    }

    /** Add to dead list and persist. */
    public static void markDead(AccountEntry entry) {
        deadList.add(entry.deadKey());
        save();
    }

    /**
     * After a successful refresh, call this to persist the new tokens.
     * The entry should already have its fields updated before calling.
     */
    public static void updateTokens(AccountEntry entry) {
        tokenCache.put(entry.deadKey(), new CachedTokens(
                entry.minecraftToken,
                entry.refreshToken,
                entry.lastRefreshed
        ));
        save();
    }

    /** Save notes for an account. */
    public static void saveNotes(AccountEntry entry) {
        if (entry.notes != null && !entry.notes.isBlank()) {
            notesMap.put(entry.deadKey(), entry.notes);
        } else {
            notesMap.remove(entry.deadKey());
        }
        save();
    }

    /**
     * Apply any cached tokens and notes on top of a freshly parsed entry.
     * This makes the cached (fresher) tokens take precedence over the source file.
     */
    public static void applyCache(AccountEntry entry) {
        CachedTokens cached = tokenCache.get(entry.deadKey());
        if (cached != null) {
            if (cached.minecraftToken() != null && !cached.minecraftToken().isBlank()) {
                entry.minecraftToken = cached.minecraftToken();
                TokenManager.ExpiryInfo expiry = TokenManager.decodeExpiry(entry.minecraftToken);
                if (expiry != null) entry.jwtExpiry = expiry.expireEpochSeconds();
            }
            if (cached.refreshToken() != null) {
                entry.refreshToken = cached.refreshToken();
            }
            entry.lastRefreshed = cached.lastRefreshed();
        }

        String notes = notesMap.get(entry.deadKey());
        if (notes != null) entry.notes = notes;
    }

    // ── Load / Save ───────────────────────────────────────────────────────────

    public static void load() {
        ensureDir();
        if (!Files.exists(CACHE_FILE)) return;

        try {
            String json = Files.readString(CACHE_FILE, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            deadList.clear();
            if (root.has("dead")) {
                root.getAsJsonArray("dead").forEach(e -> deadList.add(e.getAsString()));
            }

            tokenCache.clear();
            if (root.has("cache")) {
                JsonObject cacheObj = root.getAsJsonObject("cache");
                for (Map.Entry<String, JsonElement> e : cacheObj.entrySet()) {
                    JsonObject v = e.getValue().getAsJsonObject();
                    tokenCache.put(e.getKey(), new CachedTokens(
                            strOr(v, "mc",   null),
                            strOr(v, "rt",   null),
                            v.has("ts") ? v.get("ts").getAsLong() : 0L
                    ));
                }
            }

            notesMap.clear();
            if (root.has("notes")) {
                JsonObject notesObj = root.getAsJsonObject("notes");
                for (Map.Entry<String, JsonElement> e : notesObj.entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        notesMap.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
        } catch (Exception e) {
            TokenLoginClient.LOGGER.warn("Failed to load account cache: {}", e.getMessage());
        }
    }

    public static void save() {
        ensureDir();
        try {
            JsonObject root = new JsonObject();

            JsonArray dead = new JsonArray();
            deadList.forEach(dead::add);
            root.add("dead", dead);

            JsonObject cache = new JsonObject();
            tokenCache.forEach((key, val) -> {
                JsonObject obj = new JsonObject();
                if (val.minecraftToken() != null) obj.addProperty("mc", val.minecraftToken());
                if (val.refreshToken()   != null) obj.addProperty("rt", val.refreshToken());
                obj.addProperty("ts", val.lastRefreshed());
                cache.add(key, obj);
            });
            root.add("cache", cache);

            if (!notesMap.isEmpty()) {
                JsonObject notes = new JsonObject();
                notesMap.forEach(notes::addProperty);
                root.add("notes", notes);
            }

            Files.writeString(CACHE_FILE,
                    new GsonBuilder().setPrettyPrinting().create().toJson(root),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            TokenLoginClient.LOGGER.warn("Failed to save account cache: {}", e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void ensureDir() {
        try { Files.createDirectories(BASE_DIR); } catch (Exception ignored) {}
    }

    private static String strOr(JsonObject obj, String key, String def) {
        return (obj.has(key) && obj.get(key).isJsonPrimitive())
                ? obj.get(key).getAsString() : def;
    }
}
