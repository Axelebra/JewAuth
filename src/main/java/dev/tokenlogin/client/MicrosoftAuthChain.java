package dev.tokenlogin.client;

import com.google.gson.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Implements the full Microsoft → Minecraft authentication chain.
 *
 * Steps:
 *   1. Exchange a Microsoft refresh_token for a new MS access_token
 *   2. Authenticate with Xbox Live (XBL) using the MS access_token
 *   3. Authorize with XSTS using the XBL token
 *   4. Exchange XSTS + userHash for a Minecraft access_token
 *
 * All methods are blocking — always call from a background thread.
 */
public class MicrosoftAuthChain {

    public record RefreshResult(
            String minecraftToken,
            String newRefreshToken,
            long   expiresEpoch       // absolute epoch seconds
    ) {}

    /**
     * Runs all 4 steps and returns a new Minecraft JWT.
     *
     * @param refreshToken The Microsoft refresh_token from the launcher file.
     * @param clientId     The OAuth client_id that originally issued the token.
     * @throws Exception with a human-readable message on any failure.
     */
    public static RefreshResult refresh(String refreshToken, String clientId, boolean liveAuth) throws Exception {
        // Step 1
        MSTokenResult ms = refreshMsToken(refreshToken, clientId, liveAuth);

        // Step 2
        XBLResult xbl = authenticateXBL(ms.accessToken());

        // Step 3
        String xstsToken = authenticateXSTS(xbl.token());

        // Step 4
        MCAuthResult mc = authenticateMinecraft(xbl.userHash(), xstsToken);

        long expiresEpoch = System.currentTimeMillis() / 1000L + mc.expiresIn();
        return new RefreshResult(mc.accessToken(), ms.refreshToken(), expiresEpoch);
    }

    // ── Step 1: Microsoft OAuth token refresh ─────────────────────────────────
    //
    // liveAuth=true  → GDLauncher accounts → login.live.com
    //   scope: offline_access xboxlive.signin xboxlive.offline_access
    //   (as per GDLauncher source, constants.js + api.js msOAuthRefresh)
    //
    // liveAuth=false → Prism/MultiMC     → login.microsoftonline.com
    //   client_id and scope as-is from the launcher file

    private record MSTokenResult(String accessToken, String refreshToken) {}

    private static MSTokenResult refreshMsToken(String refreshToken, String clientId, boolean liveAuth) throws Exception {
        final String endpoint;
        final String body;

        if (liveAuth) {
            endpoint = "https://login.live.com/oauth20_token.srf";
            body = "client_id="     + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                 + "&grant_type=refresh_token"
                 + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
                 + "&scope="         + URLEncoder.encode("offline_access xboxlive.signin xboxlive.offline_access", StandardCharsets.UTF_8);
        } else {
            endpoint = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
            body = "client_id="     + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                 + "&grant_type=refresh_token"
                 + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8);
        }

        JsonObject json = postForm(endpoint, body);

        if (!json.has("access_token")) {
            throw new Exception("MS refresh failed: " + extractError(json));
        }

        String newRefresh = json.has("refresh_token")
                ? json.get("refresh_token").getAsString()
                : refreshToken;

        return new MSTokenResult(json.get("access_token").getAsString(), newRefresh);
    }

    // ── Step 2: Xbox Live authentication ──────────────────────────────────────

    private record XBLResult(String token, String userHash) {}

    private static XBLResult authenticateXBL(String msAccessToken) throws Exception {
        JsonObject props = new JsonObject();
        props.addProperty("AuthMethod", "RPS");
        props.addProperty("SiteName",   "user.auth.xboxlive.com");
        props.addProperty("RpsTicket",  "d=" + msAccessToken);

        JsonObject reqBody = new JsonObject();
        reqBody.add("Properties",   props);
        reqBody.addProperty("RelyingParty", "http://auth.xboxlive.com");
        reqBody.addProperty("TokenType",    "JWT");

        JsonObject response = postJson(
                "https://user.auth.xboxlive.com/user/authenticate",
                reqBody.toString()
        );

        String token    = response.get("Token").getAsString();
        String userHash = response
                .getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui")
                .get(0).getAsJsonObject()
                .get("uhs").getAsString();

        return new XBLResult(token, userHash);
    }

    // ── Step 3: XSTS authorization ────────────────────────────────────────────

    private static String authenticateXSTS(String xblToken) throws Exception {
        JsonObject props = new JsonObject();
        props.addProperty("SandboxId", "RETAIL");
        JsonArray tokens = new JsonArray();
        tokens.add(xblToken);
        props.add("UserTokens", tokens);

        JsonObject reqBody = new JsonObject();
        reqBody.add("Properties", props);
        reqBody.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        reqBody.addProperty("TokenType",    "JWT");

        JsonObject response = postJson(
                "https://xsts.auth.xboxlive.com/xsts/authorize",
                reqBody.toString()
        );

        // XErr 2148916233 = no Xbox account; 2148916238 = child account
        if (response.has("XErr")) {
            long xErr = response.get("XErr").getAsLong();
            throw new Exception("XSTS error XErr=" + xErr + interpretXErr(xErr));
        }

        return response.get("Token").getAsString();
    }

    // ── Step 4: Minecraft authentication ─────────────────────────────────────

    private record MCAuthResult(String accessToken, long expiresIn) {}

    private static MCAuthResult authenticateMinecraft(String userHash, String xstsToken) throws Exception {
        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);

        JsonObject response = postJson(
                "https://api.minecraftservices.com/authentication/login_with_xbox",
                reqBody.toString()
        );

        if (!response.has("access_token")) {
            throw new Exception("MC auth failed: " + extractError(response));
        }

        long expiresIn = response.has("expires_in")
                ? response.get("expires_in").getAsLong()
                : 86400L;

        return new MCAuthResult(response.get("access_token").getAsString(), expiresIn);
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private static JsonObject postJson(String urlStr, String body) throws Exception {
        return post(urlStr, body, "application/json");
    }

    private static JsonObject postForm(String urlStr, String body) throws Exception {
        return post(urlStr, body, "application/x-www-form-urlencoded");
    }

    private static JsonObject post(String urlStr, String body, String contentType) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", contentType);
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream stream = (code < 400) ? conn.getInputStream() : conn.getErrorStream();
        String response = readStream(stream);
        conn.disconnect();

        // Always log errors so we can see exactly what the server rejected
        if (code >= 400) {
            TokenLoginClient.LOGGER.warn("HTTP {} from {}, body: {}", code, urlStr,
                    response.substring(0, Math.min(500, response.length())));
            throw new Exception("HTTP " + code + " from " + urlStr + ": " + response.substring(0, Math.min(200, response.length())));
        }

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(response);
        } catch (Exception e) {
            throw new Exception("Non-JSON response (HTTP " + code + ") from " + urlStr);
        }

        if (!parsed.isJsonObject()) {
            throw new Exception("Unexpected response format from " + urlStr);
        }

        return parsed.getAsJsonObject();
    }

    private static String readStream(InputStream stream) {
        if (stream == null) return "{}";
        try (Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8)) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "{}";
        }
    }

    private static String extractError(JsonObject json) {
        if (json.has("error_description")) return json.get("error_description").getAsString();
        if (json.has("error"))             return json.get("error").getAsString();
        if (json.has("message"))           return json.get("message").getAsString();
        if (json.has("Message"))           return json.get("Message").getAsString();
        return json.toString().substring(0, Math.min(120, json.toString().length()));
    }

    private static String interpretXErr(long code) {
        return switch ((int) code) {
            case -2146920055 -> " (no Xbox account on this Microsoft account)";
            case -2146920054 -> " (account is a child — parental controls active)";
            default -> "";
        };
    }
}
