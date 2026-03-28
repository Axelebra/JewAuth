package dev.tokenlogin.client;

import com.google.gson.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Universal launcher account file parser.
 *
 * Detects and parses four JSON formats automatically:
 *
 *   Format A  — "accounts" is a JSON ARRAY  + has formatVersion / ygg / profile
 *               (Prism, MultiMC, Lunar Prism-export, etc.)
 *               Token: ygg.token | Refresh: msa.refresh_token | ClientID: msa-client-id
 *
 *   Format B  — "accounts" is a JSON OBJECT + root has "activeAccountLocalId"
 *               (Official Minecraft Launcher, Lunar Client, etc.)
 *               Token: accessToken | Refresh: refreshToken | ClientID: from JWT aid claim
 *
 *   Format C  — "accounts" is a JSON ARRAY  + entries have nested "auth" object
 *               (Custom launchers with full OAuth state, e.g. some Badlion builds)
 *               Token: accessToken / auth.mcToken.value | Refresh: auth.refreshToken.value
 *               ClientID: extracted from auth.openUri
 *
 *   Format D  — "accounts" is a JSON OBJECT + NO "activeAccountLocalId"
 *               (GDLauncher and similar)
 *               Token: accessToken | Refresh: refreshToken | ClientID: hardcoded GDL
 *
 * Files without an "accounts" key (launcher_profiles.json, launcher.json, etc.)
 * are silently skipped.  Only .json files are accepted.
 *
 * Each parsed account gets a {@code parserPath} debug code (e.g. "A-1", "B-2")
 * shown in the UI badge so issues can be reported precisely.
 */
public class LauncherParser {

    // ── Known client IDs ──────────────────────────────────────────────────────
    // Official MC Launcher / Lunar Client — compact Windows Live app ID
    // Source: https://wiki.vg/Microsoft_Authentication_Scheme
    private static final String OFFICIAL_CLIENT_ID = "00000000402B5328";
    private static final String GDL_CLIENT_ID      = "b9336bf8-c6bb-4344-aabe-63d0bfa8db2e";

    // ══════════════════════════════════════════════════════════════════════════
    //  Public entry point
    // ══════════════════════════════════════════════════════════════════════════

    public static List<AccountEntry> parseFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();

        if (name.endsWith(".json")) {
            try {
                return parseJson(file);
            } catch (Exception e) {
                TokenLoginClient.LOGGER.warn("LauncherParser: failed to parse {}: {}", name, e.getMessage());
            }
            return List.of();
        }

        if (name.endsWith(".txt")) {
            try {
                return parseTxt(file);
            } catch (Exception e) {
                TokenLoginClient.LOGGER.warn("LauncherParser: failed to parse {}: {}", name, e.getMessage());
            }
            return List.of();
        }

        return List.of();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Plain-text — one JWT per line
    // ══════════════════════════════════════════════════════════════════════════

    private static List<AccountEntry> parseTxt(Path file) throws Exception {
        List<AccountEntry> result = new ArrayList<>();
        String fname = file.getFileName().toString();

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int idx = 0;
        for (String raw : lines) {
            String token = raw.strip();
            if (token.isBlank() || token.startsWith("#")) continue;

            // Must look like a JWT (three base64url segments)
            String[] parts = token.split("\\.");
            if (parts.length != 3) continue;

            AccountEntry entry = new AccountEntry();
            entry.sourceFile = fname;
            entry.sourceType = AccountEntry.SourceType.UNKNOWN;
            entry.minecraftToken = token;
            entry.parserPath = "T-" + (++idx);

            // Decode username / uuid / expiry from the JWT payload
            TokenManager.ExpiryInfo expiry = TokenManager.decodeExpiry(token);
            if (expiry != null) entry.jwtExpiry = expiry.expireEpochSeconds();

            // Try to extract username + UUID from the JWT payload directly
            try {
                String payload = parts[1];
                int pad = (4 - payload.length() % 4) % 4;
                payload = payload + "=".repeat(pad);
                payload = payload.replace('-', '+').replace('_', '/');
                byte[] decoded = Base64.getDecoder().decode(payload);
                JsonObject obj = JsonParser.parseString(
                        new String(decoded, StandardCharsets.UTF_8)).getAsJsonObject();

                // pfd[0].name — the actual MC username in Minecraft JWTs
                if (obj.has("pfd") && obj.get("pfd").isJsonArray()) {
                    JsonArray pfd = obj.getAsJsonArray("pfd");
                    if (!pfd.isEmpty() && pfd.get(0).isJsonObject()) {
                        JsonObject p = pfd.get(0).getAsJsonObject();
                        if (p.has("name")) entry.username = p.get("name").getAsString();
                        if (p.has("id"))   entry.uuid     = p.get("id").getAsString();
                    }
                }
                // Fallback: top-level name
                if (entry.username.isBlank() && obj.has("name") && obj.get("name").isJsonPrimitive()) {
                    entry.username = obj.get("name").getAsString();
                }
                // UUID fallback: sub claim
                if (entry.uuid.isBlank() && obj.has("sub") && obj.get("sub").isJsonPrimitive()) {
                    entry.uuid = obj.get("sub").getAsString();
                }
            } catch (Exception ignored) {}

            if (entry.username.isBlank()) entry.username = "Token #" + idx;

            result.add(entry);
        }

        logResult("T", result.size(), fname);
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  JSON routing
    // ══════════════════════════════════════════════════════════════════════════

    private static List<AccountEntry> parseJson(Path file) throws Exception {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        JsonElement root = JsonParser.parseString(content);
        if (!root.isJsonObject()) return List.of();

        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("accounts")) return List.of();

        String fname = file.getFileName().toString();
        JsonElement accountsEl = obj.get("accounts");

        // ── accounts is an ARRAY → Format A or C ──────────────────────────
        if (accountsEl.isJsonArray()) {
            return routeArrayAccounts(accountsEl.getAsJsonArray(), fname);
        }

        // ── accounts is an OBJECT → Format B or D ─────────────────────────
        if (accountsEl.isJsonObject()) {
            return routeObjectAccounts(obj, accountsEl.getAsJsonObject(), fname);
        }

        return List.of();
    }

    // ── Array routing (A vs C) ────────────────────────────────────────────────

    private static List<AccountEntry> routeArrayAccounts(JsonArray arr, String fname) {
        if (arr.isEmpty()) return List.of();

        JsonObject first = arr.get(0).isJsonObject() ? arr.get(0).getAsJsonObject() : null;
        if (first == null) return List.of();

        // Format C fingerprint: entry has "auth" with nested token objects
        if (first.has("auth") && first.get("auth").isJsonObject()) {
            return parseFormatC(arr, fname);
        }

        // Format A fingerprint: entry has "ygg", "profile", or "msa"
        if (first.has("ygg") || first.has("profile") || first.has("msa")) {
            return parseFormatA(arr, fname);
        }

        // Unknown array format — log and skip
        TokenLoginClient.LOGGER.debug("LauncherParser: array accounts in {} didn't match A or C", fname);
        return List.of();
    }

    // ── Object routing (B vs D) ───────────────────────────────────────────────

    private static List<AccountEntry> routeObjectAccounts(JsonObject root, JsonObject accountsObj, String fname) {
        boolean hasActiveId = root.has("activeAccountLocalId");

        // Quick scan: do any entries look like account objects?
        boolean hasAccountData = false;
        for (Map.Entry<String, JsonElement> kv : accountsObj.entrySet()) {
            if (!kv.getValue().isJsonObject()) continue;
            JsonObject v = kv.getValue().getAsJsonObject();
            if (v.has("minecraftProfile") || v.has("accessToken") || v.has("refreshToken")) {
                hasAccountData = true;
                break;
            }
        }
        if (!hasAccountData) return List.of();

        // Format B: Official/Lunar — has activeAccountLocalId
        if (hasActiveId) {
            return parseFormatB(accountsObj, fname);
        }

        // Format D: GDLauncher — no activeAccountLocalId
        return parseFormatD(accountsObj, fname);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Format A — Prism / MultiMC / array-style launchers
    // ══════════════════════════════════════════════════════════════════════════

    private static List<AccountEntry> parseFormatA(JsonArray arr, String fname) {
        List<AccountEntry> result = new ArrayList<>();

        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject acc = el.getAsJsonObject();

            // ── Profile (required) ────────────────────────────────────────
            if (!acc.has("profile")) continue;
            JsonObject profile = acc.getAsJsonObject("profile");
            String username = strOr(profile, "name", "");
            String uuid     = strOr(profile, "id",   "");
            if (username.isBlank()) continue;

            AccountEntry entry = new AccountEntry();
            entry.sourceFile = fname;
            entry.sourceType = AccountEntry.SourceType.FORMAT_A;
            entry.username   = username;
            entry.uuid       = uuid;
            entry.liveAuth   = false;

            // ── Minecraft JWT from ygg.token ──────────────────────────────
            String path = "A-0";
            if (acc.has("ygg") && acc.getAsJsonObject("ygg").has("token")) {
                entry.minecraftToken = acc.getAsJsonObject("ygg").get("token").getAsString();
                path = "A-1";
            }

            // ── Client ID (msa-client-id) ─────────────────────────────────
            entry.clientId = strOr(acc, "msa-client-id", null);

            // ── Refresh token from msa.refresh_token ──────────────────────
            if (acc.has("msa") && acc.get("msa").isJsonObject()) {
                JsonObject msa = acc.getAsJsonObject("msa");
                if (msa.has("refresh_token")) {
                    entry.refreshToken = msa.get("refresh_token").getAsString();
                    if (path.equals("A-0")) path = "A-2";
                }
                // Fallback: msa.extra.refresh_token (older Prism builds)
                if (entry.refreshToken == null && msa.has("extra") && msa.get("extra").isJsonObject()) {
                    JsonObject extra = msa.getAsJsonObject("extra");
                    if (extra.has("refresh_token")) {
                        entry.refreshToken = extra.get("refresh_token").getAsString();
                        path = "A-3";
                    }
                }
            }

            entry.parserPath = path;
            decodeExpiry(entry);
            result.add(entry);
        }

        logResult("A", result.size(), fname);
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Format B — Official Minecraft Launcher / Lunar Client
    // ══════════════════════════════════════════════════════════════════════════

    private static List<AccountEntry> parseFormatB(JsonObject accountsObj, String fname) {
        List<AccountEntry> result = new ArrayList<>();

        for (Map.Entry<String, JsonElement> kv : accountsObj.entrySet()) {
            if (!kv.getValue().isJsonObject()) continue;
            JsonObject acc = kv.getValue().getAsJsonObject();

            // ── Username & UUID ───────────────────────────────────────────
            String username = "";
            String uuid     = "";
            String path     = "B-1";

            if (acc.has("minecraftProfile") && acc.get("minecraftProfile").isJsonObject()) {
                JsonObject profile = acc.getAsJsonObject("minecraftProfile");
                username = strOr(profile, "name", "");
                uuid     = strOr(profile, "id",   "");
            }
            // Fallback: top-level username
            if (username.isBlank()) {
                username = strOr(acc, "username", "");
                path = "B-2";
            }
            if (username.isBlank()) continue;

            AccountEntry entry = new AccountEntry();
            entry.sourceFile     = fname;
            entry.sourceType     = AccountEntry.SourceType.FORMAT_B;
            entry.username       = username;
            entry.uuid           = uuid;
            entry.minecraftToken = strOr(acc, "accessToken", "");

            // ── Refresh token ─────────────────────────────────────────────
            String rt = strOr(acc, "refreshToken", null);
            if (rt != null && !rt.isBlank()) {
                entry.refreshToken = rt;
            }

            // ── Client ID & liveAuth ──────────────────────────────────────
            // Format B = Official Launcher / Lunar Client.
            // Both use the same well-known legacy Windows Live app ID.
            // Must be compact format (no dashes) — login.live.com rejects UUID format.
            // liveAuth=true → same login.live.com path that works for GDLauncher.
            entry.clientId = OFFICIAL_CLIENT_ID;
            entry.liveAuth = true;

            entry.parserPath = path;
            decodeExpiry(entry);
            result.add(entry);
        }

        logResult("B", result.size(), fname);
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Format C — Custom launcher with nested auth object
    // ══════════════════════════════════════════════════════════════════════════

    private static List<AccountEntry> parseFormatC(JsonArray arr, String fname) {
        List<AccountEntry> result = new ArrayList<>();

        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject acc = el.getAsJsonObject();

            // ── Username & UUID (top-level) ───────────────────────────────
            String username = strOr(acc, "name", "");
            String uuid     = strOr(acc, "uuid", "");

            // Fallback: auth.profile.name
            if (username.isBlank() && acc.has("auth") && acc.get("auth").isJsonObject()) {
                JsonObject auth = acc.getAsJsonObject("auth");
                if (auth.has("profile") && auth.get("profile").isJsonObject()) {
                    JsonObject prof = auth.getAsJsonObject("profile");
                    username = strOr(prof, "name", "");
                    uuid     = strOr(prof, "id", uuid);
                }
            }
            if (username.isBlank()) continue;

            AccountEntry entry = new AccountEntry();
            entry.sourceFile = fname;
            entry.sourceType = AccountEntry.SourceType.FORMAT_C;
            entry.username   = username;
            entry.uuid       = uuid;
            entry.liveAuth   = true; // default for this format

            // ── MC token: top-level accessToken ───────────────────────────
            entry.minecraftToken = strOr(acc, "accessToken", "");
            String path = "C-1";

            // ── Dig into auth object ──────────────────────────────────────
            if (acc.has("auth") && acc.get("auth").isJsonObject()) {
                JsonObject auth = acc.getAsJsonObject("auth");

                // MC token fallback: auth.mcToken.value
                if (entry.minecraftToken.isBlank()) {
                    entry.minecraftToken = nestedValue(auth, "mcToken");
                    if (!entry.minecraftToken.isBlank()) path = "C-2";
                }

                // Refresh token: auth.refreshToken.value
                String rt = nestedValue(auth, "refreshToken");
                if (!rt.isBlank()) {
                    entry.refreshToken = rt;
                }

                // Client ID: extract from auth.openUri query param
                if (auth.has("openUri") && auth.get("openUri").isJsonPrimitive()) {
                    String uri = auth.get("openUri").getAsString();
                    entry.clientId = extractClientIdFromUri(uri);
                    entry.liveAuth = uri.contains("login.live.com");
                }

                // Fallback client ID from auth.redirectUri or top-level
                if (entry.clientId == null || entry.clientId.isBlank()) {
                    path += "f";
                }
            }

            entry.parserPath = path;
            decodeExpiry(entry);
            result.add(entry);
        }

        logResult("C", result.size(), fname);
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Format D — GDLauncher (object accounts, no activeAccountLocalId)
    // ══════════════════════════════════════════════════════════════════════════

    private static List<AccountEntry> parseFormatD(JsonObject accountsObj, String fname) {
        List<AccountEntry> result = new ArrayList<>();

        for (Map.Entry<String, JsonElement> kv : accountsObj.entrySet()) {
            if (!kv.getValue().isJsonObject()) continue;
            JsonObject acc = kv.getValue().getAsJsonObject();

            // ── Username & UUID ───────────────────────────────────────────
            String username = "";
            String uuid     = "";

            if (acc.has("minecraftProfile") && acc.get("minecraftProfile").isJsonObject()) {
                JsonObject profile = acc.getAsJsonObject("minecraftProfile");
                username = strOr(profile, "name", "");
                uuid     = strOr(profile, "id",   "");
            }
            if (username.isBlank()) {
                username = strOr(acc, "username", "");
            }
            if (username.isBlank()) continue;

            AccountEntry entry = new AccountEntry();
            entry.sourceFile     = fname;
            entry.sourceType     = AccountEntry.SourceType.FORMAT_D;
            entry.username       = username;
            entry.uuid           = uuid;
            entry.minecraftToken = strOr(acc, "accessToken", "");

            String rt = strOr(acc, "refreshToken", null);
            if (rt != null && !rt.isBlank()) {
                entry.refreshToken = rt;
                entry.clientId     = GDL_CLIENT_ID;
                entry.liveAuth     = true;
            }

            entry.parserPath = "D-1";
            decodeExpiry(entry);
            result.add(entry);
        }

        logResult("D", result.size(), fname);
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /** Decode JWT expiry and set it on the entry. */
    private static void decodeExpiry(AccountEntry entry) {
        if (entry.minecraftToken == null || entry.minecraftToken.isBlank()) return;
        TokenManager.ExpiryInfo exp = TokenManager.decodeExpiry(entry.minecraftToken);
        if (exp != null) entry.jwtExpiry = exp.expireEpochSeconds();
    }

    /** Safe string getter from a JsonObject. */
    private static String strOr(JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key)) return def;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : def;
    }

    /**
     * Extract a value from a nested token object like {@code { "value": "...", "expires": {...} }}.
     * Returns the "value" string, or "" if not found.
     */
    private static String nestedValue(JsonObject parent, String key) {
        if (!parent.has(key)) return "";
        JsonElement el = parent.get(key);
        if (!el.isJsonObject()) return "";
        return strOr(el.getAsJsonObject(), "value", "");
    }

    /**
     * Extract the "aid" (application/client ID) claim from a Minecraft JWT.
     * Returns null if the token is not a valid 3-part JWT or has no "aid" field.
     */
    private static String extractAidFromJwt(String jwt) {
        if (jwt == null || jwt.isBlank()) return null;
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;

            // Base64url decode the payload
            String payload = parts[1];
            // Pad if necessary
            int pad = (4 - payload.length() % 4) % 4;
            payload = payload + "=".repeat(pad);
            payload = payload.replace('-', '+').replace('_', '/');

            byte[] decoded = Base64.getDecoder().decode(payload);
            JsonObject obj = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8)).getAsJsonObject();

            if (obj.has("aid") && obj.get("aid").isJsonPrimitive()) {
                return obj.get("aid").getAsString();
            }
        } catch (Exception ignored) {
            // Not a standard JWT or missing aid — that's fine
        }
        return null;
    }

    /**
     * Extract client_id from an OAuth redirect URI query string.
     * Looks for "client_id=" parameter.
     */
    private static String extractClientIdFromUri(String uri) {
        if (uri == null || uri.isBlank()) return null;
        try {
            // URL-decode common escapes first
            String decoded = uri.replace("\\u003d", "=").replace("\\u0026", "&");
            int idx = decoded.indexOf("client_id=");
            if (idx < 0) return null;
            String sub = decoded.substring(idx + "client_id=".length());
            int end = sub.indexOf('&');
            if (end < 0) end = sub.length();
            String clientId = sub.substring(0, end).trim();
            return clientId.isBlank() ? null : clientId;
        } catch (Exception e) {
            return null;
        }
    }

    /** Log how many accounts were parsed from which format. */
    private static void logResult(String format, int count, String fname) {
        if (count > 0) {
            TokenLoginClient.LOGGER.info("LauncherParser: [{}] parsed {} account(s) from {}",
                    format, count, fname);
        }
    }

}
