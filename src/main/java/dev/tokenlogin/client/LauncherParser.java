package dev.tokenlogin.client;

import com.google.gson.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses launcher account files.
 *
 * Supported formats:
 *   • Prism / MultiMC  — accounts[] ARRAY with ygg.token, msa.refresh_token, msa-client-id
 *   • GDLauncher / etc — accounts{} OBJECT keyed by localId with accessToken + refreshToken
 *   • .txt             — single raw JWT as plain text
 */
public class LauncherParser {

    public static List<AccountEntry> parseFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        try {
            if (name.endsWith(".txt"))  return parseTxt(file);
            if (name.endsWith(".json")) return parseJson(file);
        } catch (Exception e) {
            TokenLoginClient.LOGGER.warn("LauncherParser: failed to parse {}: {}", name, e.getMessage());
        }
        return List.of();
    }

    // ── Route JSON files ──────────────────────────────────────────────────────

    private static List<AccountEntry> parseJson(Path file) throws Exception {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        JsonElement root = JsonParser.parseString(content);
        if (!root.isJsonObject()) return List.of();
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("accounts")) return List.of();

        JsonElement accountsEl = obj.get("accounts");
        if (accountsEl.isJsonArray())  return parsePrism(accountsEl.getAsJsonArray(), file.getFileName().toString());
        if (accountsEl.isJsonObject()) return parseGdLauncher(accountsEl.getAsJsonObject(), file.getFileName().toString());
        return List.of();
    }

    // ── .txt: single JWT ──────────────────────────────────────────────────────

    private static List<AccountEntry> parseTxt(Path file) throws Exception {
        String content = Files.readString(file, StandardCharsets.UTF_8).trim();
        if (content.isBlank()) return List.of();

        if (content.split("\\.").length != 3) {
            TokenLoginClient.LOGGER.warn("LauncherParser: {} does not contain a valid JWT", file.getFileName());
            return List.of();
        }

        AccountEntry entry = new AccountEntry();
        entry.sourceFile     = file.getFileName().toString();
        entry.sourceType     = AccountEntry.SourceType.MANUAL;
        entry.minecraftToken = content;
        entry.username       = "Manual – " + file.getFileName();

        TokenManager.ExpiryInfo exp = TokenManager.decodeExpiry(content);
        if (exp != null) entry.jwtExpiry = exp.expireEpochSeconds();

        return List.of(entry);
    }

    // ── Prism Launcher ────────────────────────────────────────────────────────
    //
    // Fingerprint:
    //   - root is an object with "accounts" key
    //   - "accounts" is a JSON ARRAY (not an object — that's Mojang launcher)
    //   - has "formatVersion" (usually 3)
    //   - entries have "ygg" with "token", "profile" with "name"/"id",
    //     "msa-client-id", and "msa" with "refresh_token"

    // ── GDLauncher / object-accounts format ──────────────────────────────────
    //
    // No client ID field in the JSON — GDLauncher's own registered client ID is used.
    // Source: https://github.com/gorilla-devs/GDLauncher/blob/main/src/common/utils/constants.js
    private static final String GDL_CLIENT_ID = "b9336bf8-c6bb-4344-aabe-63d0bfa8db2e";

    private static List<AccountEntry> parseGdLauncher(JsonObject accountsObj, String fname) {
        boolean looksRight = false;
        for (Map.Entry<String, JsonElement> kv : accountsObj.entrySet()) {
            if (!kv.getValue().isJsonObject()) continue;
            JsonObject v = kv.getValue().getAsJsonObject();
            if (v.has("accessToken") || v.has("minecraftProfile")) { looksRight = true; break; }
        }
        if (!looksRight) return List.of();

        List<AccountEntry> result = new ArrayList<>();
        for (Map.Entry<String, JsonElement> kv : accountsObj.entrySet()) {
            if (!kv.getValue().isJsonObject()) continue;
            JsonObject acc = kv.getValue().getAsJsonObject();
            if (!acc.has("minecraftProfile")) continue;

            JsonObject profile = acc.getAsJsonObject("minecraftProfile");
            String username = strOr(profile, "name", "");
            String uuid     = strOr(profile, "id",   "");
            if (username.isBlank()) continue;

            AccountEntry entry = new AccountEntry();
            entry.sourceFile     = fname;
            entry.sourceType     = AccountEntry.SourceType.GDLAUNCHER;
            entry.username       = username;
            entry.uuid           = uuid;
            entry.minecraftToken = strOr(acc, "accessToken", "");

            String rt = strOr(acc, "refreshToken", null);
            if (rt != null && !rt.isBlank()) {
                entry.refreshToken = rt;
                entry.clientId     = GDL_CLIENT_ID;
                entry.liveAuth     = true;
            }

            decodeExpiry(entry);
            result.add(entry);
        }

        if (!result.isEmpty())
            TokenLoginClient.LOGGER.info("LauncherParser: parsed {} GDLauncher account(s) from {}", result.size(), fname);
        return result;
    }

    // ── Prism / MultiMC ───────────────────────────────────────────────────────

    private static List<AccountEntry> parsePrism(JsonArray accountsArr, String fname) {
        if (accountsArr.isEmpty()) return List.of();

        // Fingerprint check: first entry must have "ygg" or "profile"
        JsonObject first = accountsArr.get(0).isJsonObject() ? accountsArr.get(0).getAsJsonObject() : null;
        if (first == null) return List.of();
        if (!first.has("ygg") && !first.has("profile")) return List.of();

        List<AccountEntry> result = new ArrayList<>();

        for (JsonElement el : accountsArr) {
            if (!el.isJsonObject()) continue;
            JsonObject acc = el.getAsJsonObject();

            AccountEntry entry = new AccountEntry();
            entry.sourceFile = fname;
            entry.sourceType = AccountEntry.SourceType.PRISM;

            // ── Username & UUID from profile ──────────────────────────────
            if (!acc.has("profile")) continue;
            JsonObject profile = acc.getAsJsonObject("profile");
            entry.username = strOr(profile, "name", "");
            entry.uuid     = strOr(profile, "id",   "");
            if (entry.username.isBlank()) continue;

            // ── Minecraft JWT from ygg.token ──────────────────────────────
            if (acc.has("ygg") && acc.getAsJsonObject("ygg").has("token")) {
                entry.minecraftToken = acc.getAsJsonObject("ygg").get("token").getAsString();
            }

            // ── Client ID (msa-client-id) ─────────────────────────────────
            entry.clientId = strOr(acc, "msa-client-id", null);

            // ── Refresh token from msa.refresh_token ──────────────────────
            if (acc.has("msa") && acc.getAsJsonObject("msa").has("refresh_token")) {
                entry.refreshToken = acc.getAsJsonObject("msa").get("refresh_token").getAsString();
            }
            // Fallback: msa.extra.refresh_token (older Prism builds)
            if (entry.refreshToken == null && acc.has("msa")) {
                JsonObject msa = acc.getAsJsonObject("msa");
                if (msa.has("extra") && msa.getAsJsonObject("extra").has("refresh_token")) {
                    entry.refreshToken = msa.getAsJsonObject("extra").get("refresh_token").getAsString();
                }
            }

            decodeExpiry(entry);
            result.add(entry);
        }

        if (!result.isEmpty()) {
            TokenLoginClient.LOGGER.info("LauncherParser: parsed {} Prism account(s) from {}",
                    result.size(), fname);
        }

        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void decodeExpiry(AccountEntry entry) {
        if (entry.minecraftToken.isBlank()) return;
        TokenManager.ExpiryInfo exp = TokenManager.decodeExpiry(entry.minecraftToken);
        if (exp != null) entry.jwtExpiry = exp.expireEpochSeconds();
    }

    private static String strOr(JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key)) return def;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : def;
    }
}
