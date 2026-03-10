package dev.tokenlogin.client;

import com.google.gson.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Environment(EnvType.CLIENT)
public class ProxyConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("tokenlogin.json");

    // ── Proxy list ────────────────────────────────────────────────────────────
    private static final List<ProxyEntry> proxies = new CopyOnWriteArrayList<>();

    /** Key of the proxy that should auto-connect on startup and be shown as active. */
    private static String activeKey = "";

    // ── Legacy single-proxy fields (kept for quick-connect from multiplayer) ──
    private static String address  = "";
    private static String username = "";
    private static String password = "";

    // ── Hypixel API key ────────────────────────────────────────────────────
    private static String hypixelApiKey = "";

    // ── Public API — legacy fields ────────────────────────────────────────────

    public static String getAddress()  { return address; }
    public static String getUsername() { return username; }
    public static String getPassword() { return password; }

    public static void setAddress(String v)  { address  = v != null ? v : ""; }
    public static void setUsername(String v)  { username = v != null ? v : ""; }
    public static void setPassword(String v)  { password = v != null ? v : ""; }

    public static String getHypixelApiKey()        { return hypixelApiKey; }
    public static void   setHypixelApiKey(String v) { hypixelApiKey = v != null ? v : ""; save(); }

    // ── Public API — proxy list ───────────────────────────────────────────────

    public static List<ProxyEntry> getProxies() {
        return Collections.unmodifiableList(proxies);
    }

    public static String getActiveKey() { return activeKey; }

    public static void setActiveKey(String key) {
        activeKey = key != null ? key : "";
        save();
    }

    /** Returns the proxy marked as active, or null. */
    public static ProxyEntry getActiveProxy() {
        if (activeKey.isEmpty()) return null;
        for (ProxyEntry p : proxies) {
            if (p.key().equals(activeKey)) return p;
        }
        return null;
    }

    public static void addProxy(ProxyEntry entry) {
        // Dedup by key
        for (ProxyEntry existing : proxies) {
            if (existing.key().equals(entry.key())) {
                // Update in place
                existing.name     = entry.name;
                existing.username = entry.username;
                existing.password = entry.password;
                save();
                return;
            }
        }
        proxies.add(entry);
        save();
    }

    public static void updateProxy(ProxyEntry entry) {
        // Already in list (by reference from CopyOnWriteArrayList), just save
        save();
    }

    public static void removeProxy(ProxyEntry entry) {
        proxies.remove(entry);
        if (activeKey.equals(entry.key())) {
            activeKey = "";
        }
        save();
    }

    /** Stamp a successful connection result onto an entry and persist. */
    public static void markConnected(ProxyEntry entry, ProxyManager.ProxyType type) {
        entry.lastType      = type;
        entry.lastConnected = System.currentTimeMillis() / 1000L;
        activeKey = entry.key();

        // Also sync the legacy fields so the multiplayer screen stays in sync
        address  = entry.address;
        username = entry.username;
        password = entry.password;

        save();
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) return;
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            // Legacy single-proxy fields
            if (obj.has("proxyAddress"))  address  = obj.get("proxyAddress").getAsString();
            if (obj.has("proxyUsername")) username = obj.get("proxyUsername").getAsString();
            if (obj.has("proxyPassword")) password = obj.get("proxyPassword").getAsString();

            // Hypixel API key
            if (obj.has("hypixelApiKey")) hypixelApiKey = obj.get("hypixelApiKey").getAsString();

            // Active key
            if (obj.has("activeProxy")) activeKey = obj.get("activeProxy").getAsString();

            // Proxy list
            proxies.clear();
            if (obj.has("proxies") && obj.get("proxies").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("proxies")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject p = el.getAsJsonObject();
                    ProxyEntry entry = new ProxyEntry();
                    entry.name     = strOr(p, "name",     "");
                    entry.address  = strOr(p, "address",  "");
                    entry.username = strOr(p, "username",  "");
                    entry.password = strOr(p, "password",  "");
                    entry.lastType = parseType(strOr(p, "lastType", "NONE"));
                    entry.lastConnected = p.has("lastConnected") ? p.get("lastConnected").getAsLong() : 0L;

                    if (!entry.address.isBlank()) {
                        proxies.add(entry);
                    }
                }
            }

            // Migration: if we have legacy fields but no proxies list, create an entry
            if (proxies.isEmpty() && !address.isBlank()) {
                ProxyEntry migrated = new ProxyEntry();
                migrated.name     = "Migrated";
                migrated.address  = address;
                migrated.username = username;
                migrated.password = password;
                proxies.add(migrated);
                activeKey = migrated.key();
                TokenLoginClient.LOGGER.info("Migrated single proxy to proxy list");
            }

            TokenLoginClient.LOGGER.info("Loaded proxy config: {} proxies, active={}",
                    proxies.size(), activeKey.isEmpty() ? "none" : activeKey);
        } catch (Exception e) {
            TokenLoginClient.LOGGER.warn("Failed to load proxy config: {}", e.getMessage());
        }
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    public static void save() {
        try {
            JsonObject obj = new JsonObject();

            // Legacy fields (still written for backwards compat)
            obj.addProperty("proxyAddress",  address);
            obj.addProperty("proxyUsername", username);
            obj.addProperty("proxyPassword", password);

            // Hypixel API key
            obj.addProperty("hypixelApiKey", hypixelApiKey);

            // Active key
            obj.addProperty("activeProxy", activeKey);

            // Proxy list
            JsonArray arr = new JsonArray();
            for (ProxyEntry entry : proxies) {
                JsonObject p = new JsonObject();
                p.addProperty("name",          entry.name);
                p.addProperty("address",       entry.address);
                p.addProperty("username",      entry.username);
                p.addProperty("password",      entry.password);
                p.addProperty("lastType",      entry.lastType.name());
                p.addProperty("lastConnected", entry.lastConnected);
                arr.add(p);
            }
            obj.add("proxies", arr);

            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (IOException e) {
            TokenLoginClient.LOGGER.warn("Failed to save proxy config: {}", e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String strOr(JsonObject obj, String key, String def) {
        return (obj.has(key) && obj.get(key).isJsonPrimitive())
                ? obj.get(key).getAsString() : def;
    }

    private static ProxyManager.ProxyType parseType(String s) {
        try { return ProxyManager.ProxyType.valueOf(s); }
        catch (Exception e) { return ProxyManager.ProxyType.NONE; }
    }
}
