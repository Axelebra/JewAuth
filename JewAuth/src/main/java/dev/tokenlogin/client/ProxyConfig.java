package dev.tokenlogin.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Environment(EnvType.CLIENT)
public class ProxyConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("tokenlogin.json");

    private static String address  = "";
    private static String username = "";
    private static String password = "";

    public static String getAddress()  { return address; }
    public static String getUsername() { return username; }
    public static String getPassword() { return password; }

    public static void setAddress(String v)  { address  = v != null ? v : ""; }
    public static void setUsername(String v)  { username = v != null ? v : ""; }
    public static void setPassword(String v)  { password = v != null ? v : ""; }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) return;
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            if (obj.has("proxyAddress"))  address  = obj.get("proxyAddress").getAsString();
            if (obj.has("proxyUsername")) username = obj.get("proxyUsername").getAsString();
            if (obj.has("proxyPassword")) password = obj.get("proxyPassword").getAsString();

            TokenLoginClient.LOGGER.info("Loaded proxy config from {}", CONFIG_PATH);
        } catch (Exception e) {
            TokenLoginClient.LOGGER.warn("Failed to load proxy config: {}", e.getMessage());
        }
    }

    public static void save() {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("proxyAddress",  address);
            obj.addProperty("proxyUsername", username);
            obj.addProperty("proxyPassword", password);

            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (IOException e) {
            TokenLoginClient.LOGGER.warn("Failed to save proxy config: {}", e.getMessage());
        }
    }
}