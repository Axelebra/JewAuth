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

    /** deadKey → cached skyblock info. */
    private static final Map<String, SkyblockFetcher.SkyblockInfo> skyblockCache = new HashMap<>();

    /** deadKey → bound proxy address (null = no binding). */
    private static final Map<String, String> proxyBindings = new HashMap<>();

    public record CachedTokens(
            String minecraftToken,
            String refreshToken,    // may be null for JWT-only accounts
            long   lastRefreshed    // epoch seconds
    ) {}

    // ── Public API ────────────────────────────────────────────────────────────

    public static Path getBaseDir() { return BASE_DIR; }

    public static boolean isDead(AccountEntry entry) {
        return deadList.contains(entry.deadKey() + "|" + entry.sourceFile);
    }

    /** Add to dead list and persist. */
    public static void markDead(AccountEntry entry) {
        deadList.add(entry.deadKey() + "|" + entry.sourceFile);
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
     * Returns the bound proxy address for the given IGN, or null if none.
     * Searches by username prefix of the deadKey (username|uuid).
     */
    public static String getProxyBindingByUsername(String username) {
        String prefix = username.toLowerCase() + "|";
        for (Map.Entry<String, String> e : proxyBindings.entrySet()) {
            if (e.getKey().startsWith(prefix)) return e.getValue();
        }
        return null;
    }

    /** Save the proxy binding for an account. Pass null proxyAddress to clear. */
    public static void saveProxyBinding(AccountEntry entry) {
        if (entry.boundProxyAddress != null && !entry.boundProxyAddress.isBlank()) {
            proxyBindings.put(entry.deadKey(), entry.boundProxyAddress);
        } else {
            proxyBindings.remove(entry.deadKey());
        }
        save();
    }

    /** Save skyblock info for an account (including error results like "No SB profiles"). */
    public static void saveSkyblockInfo(AccountEntry entry) {
        if (entry.skyblockInfo != null) {
            skyblockCache.put(entry.deadKey(), entry.skyblockInfo);
        }
        save();
    }

    /**
     * Apply any cached tokens, notes, and skyblock info on top of a freshly parsed entry.
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

        SkyblockFetcher.SkyblockInfo sbInfo = skyblockCache.get(entry.deadKey());
        if (sbInfo != null) entry.skyblockInfo = sbInfo;

        entry.boundProxyAddress = proxyBindings.get(entry.deadKey());
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

            proxyBindings.clear();
            if (root.has("proxybindings")) {
                JsonObject pb = root.getAsJsonObject("proxybindings");
                for (Map.Entry<String, JsonElement> e : pb.entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        proxyBindings.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }

            skyblockCache.clear();
            if (root.has("skyblock")) {
                JsonObject sbObj = root.getAsJsonObject("skyblock");
                for (Map.Entry<String, JsonElement> e : sbObj.entrySet()) {
                    if (!e.getValue().isJsonObject()) continue;
                    JsonObject v = e.getValue().getAsJsonObject();
                    int level = v.has("level") ? v.get("level").getAsInt() : 0;
                    boolean coop = v.has("coop") && v.get("coop").getAsBoolean();
                    int members = v.has("members") ? v.get("members").getAsInt() : 1;
                    String pName = v.has("profile") ? v.get("profile").getAsString() : "";
                    String error = v.has("error") ? v.get("error").getAsString() : null;
                    skyblockCache.put(e.getKey(),
                            new SkyblockFetcher.SkyblockInfo(level, coop, members, pName, error));
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

            if (!proxyBindings.isEmpty()) {
                JsonObject pb = new JsonObject();
                proxyBindings.forEach(pb::addProperty);
                root.add("proxybindings", pb);
            }

            if (!skyblockCache.isEmpty()) {
                JsonObject sb = new JsonObject();
                skyblockCache.forEach((key, info) -> {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("level", info.skyblockLevel());
                    obj.addProperty("coop", info.isCoop());
                    obj.addProperty("members", info.coopMembers());
                    obj.addProperty("profile", info.profileName());
                    if (info.error() != null) obj.addProperty("error", info.error());
                    sb.add(key, obj);
                });
                root.add("skyblock", sb);
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
