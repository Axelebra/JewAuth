package dev.tokenlogin.client;

import com.google.gson.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

    // ── Proxy rotation state (global across all accounts) ──────────────────
    private static final Object ROTATION_LOCK = new Object();
    private static int  globalRefreshCount   = 0;
    private static int  currentProxyIndex    = -1;
    private static boolean proxyIndexInitialized = false;

    /** The proxy currently being used by post(). Thread-local for concurrent refreshes. */
    private static final ThreadLocal<ProxyEntry> activeRefreshProxy = new ThreadLocal<>();

    /**
     * Runs all 4 steps and returns a new Minecraft JWT.
     * Routes HTTP calls through the given proxy (assigned at button-press time).
     * If a proxy fails with a network error, skips to the next proxy and retries.
     *
     * @param refreshToken The Microsoft refresh_token from the launcher file.
     * @param clientId     The OAuth client_id that originally issued the token.
     * @param proxy        The proxy to use (grabbed via grabProxy() at click time), or null.
     * @throws Exception with a human-readable message on any failure.
     */
    public static RefreshResult refresh(String refreshToken, String clientId, boolean liveAuth, ProxyEntry proxy) throws Exception {
        activeRefreshProxy.set(proxy);

        List<ProxyEntry> allProxies = ProxyConfig.getProxies();
        int maxSkips = allProxies.isEmpty() ? 0 : allProxies.size() - 1;
        int skips = 0;

        while (true) {
            try {
                setupProxyAuth(proxy);
                RefreshResult result = doRefresh(refreshToken, clientId, liveAuth);
                return result;
            } catch (Exception e) {
                if (isNetworkError(e) && proxy != null && skips < maxSkips) {
                    TokenLoginClient.LOGGER.warn("Proxy {} failed for refresh, skipping: {}",
                            proxy.address, e.getMessage());
                    proxy = skipToNextProxy();
                    activeRefreshProxy.set(proxy);
                    skips++;
                    continue;
                }
                throw e;
            } finally {
                clearProxyAuth();
            }
        }
    }

    /** The actual auth chain — separated so retry logic stays clean. */
    private static RefreshResult doRefresh(String refreshToken, String clientId, boolean liveAuth) throws Exception {
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

    // ── Proxy rotation helpers ────────────────────────────────────────────

    /**
     * Atomically grabs the next proxy slot. Each call counts toward the
     * 3-per-proxy limit. Call this from the UI thread at button-press time
     * so grouping is determined by click order, not background thread scheduling.
     */
    public static ProxyEntry grabProxy() {
        List<ProxyEntry> proxies = ProxyConfig.getProxies();
        if (proxies.isEmpty()) return null;

        synchronized (ROTATION_LOCK) {
            // First call: find the active proxy's index
            if (!proxyIndexInitialized) {
                proxyIndexInitialized = true;
                String activeKey = ProxyConfig.getActiveKey();
                for (int i = 0; i < proxies.size(); i++) {
                    if (proxies.get(i).key().equals(activeKey)) {
                        currentProxyIndex = i;
                        break;
                    }
                }
                if (currentProxyIndex < 0) currentProxyIndex = 0;
            }

            // Rotate if we've used this proxy 3 times
            if (globalRefreshCount >= 3) {
                globalRefreshCount = 0;
                currentProxyIndex = (currentProxyIndex + 1) % proxies.size();
                ProxyEntry next = proxies.get(currentProxyIndex);
                TokenLoginClient.LOGGER.info("Proxy rotated to: {} ({})",
                        next.name.isEmpty() ? next.address : next.name, next.address);
            }

            globalRefreshCount++;

            if (currentProxyIndex >= proxies.size()) currentProxyIndex = 0;
            ProxyEntry proxy = proxies.get(currentProxyIndex);
            TokenLoginClient.LOGGER.debug("Refresh #{} on proxy: {}", globalRefreshCount, proxy.address);
            return proxy;
        }
    }

    /**
     * Skips the current proxy (it failed) and moves to the next one.
     * Resets the refresh counter so the new proxy gets a full 3 uses.
     */
    private static ProxyEntry skipToNextProxy() {
        List<ProxyEntry> proxies = ProxyConfig.getProxies();
        if (proxies.isEmpty()) return null;

        synchronized (ROTATION_LOCK) {
            currentProxyIndex = (currentProxyIndex + 1) % proxies.size();
            globalRefreshCount = 0; // reset for the new proxy
            ProxyEntry next = proxies.get(currentProxyIndex);
            TokenLoginClient.LOGGER.info("Proxy skipped (failed), now using: {} ({})",
                    next.name.isEmpty() ? next.address : next.name, next.address);
            return next;
        }
    }

    /** Converts a ProxyEntry to a java.net.Proxy. */
    private static Proxy toJavaProxy(ProxyEntry entry) {
        if (entry == null || entry.address.isBlank()) return null;
        if (entry.lastType == ProxyManager.ProxyType.NONE) return null;

        try {
            String[] parts = entry.address.trim().split(":");
            if (parts.length != 2) return null;
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);

            Proxy.Type type = switch (entry.lastType) {
                case SOCKS5, SOCKS4 -> Proxy.Type.SOCKS;
                case HTTP           -> Proxy.Type.HTTP;
                case NONE           -> null;
            };
            if (type == null) return null;

            return new Proxy(type, new InetSocketAddress(host, port));
        } catch (Exception e) {
            TokenLoginClient.LOGGER.debug("Failed to parse proxy address {}: {}", entry.address, e.getMessage());
            return null;
        }
    }

    /**
     * Sets up authentication for the proxy. Uses ONLY auth properties/Authenticator,
     * never socksProxyHost/socksProxyPort (those would hijack ALL JVM connections).
     * Routing is handled by the Proxy object passed to openConnection().
     */
    private static void setupProxyAuth(ProxyEntry entry) {
        if (entry == null || entry.address.isBlank()) return;
        if (entry.username.isBlank()) return;

        // Set SOCKS auth system properties — Java's SocksSocketImpl reads these
        if (entry.lastType == ProxyManager.ProxyType.SOCKS5
                || entry.lastType == ProxyManager.ProxyType.SOCKS4) {
            System.setProperty("java.net.socks.username", entry.username);
            System.setProperty("java.net.socks.password", entry.password);
        }

        // Set Authenticator for both SOCKS and HTTP
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(
                        entry.username, entry.password.toCharArray());
            }
        });
    }

    private static void clearProxyAuth() {
        System.clearProperty("java.net.socks.username");
        System.clearProperty("java.net.socks.password");
        Authenticator.setDefault(null);
        activeRefreshProxy.remove();
    }

    /** Returns true if the exception looks like a network/proxy failure rather than an API error. */
    private static boolean isNetworkError(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) return true;
        if (e instanceof java.net.ConnectException) return true;
        if (e instanceof java.net.SocketException) return true;
        if (e instanceof java.net.UnknownHostException) return true;
        // Proxy handshake failures
        String msg = e.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            if (lower.contains("connect timed out")) return true;
            if (lower.contains("connection refused")) return true;
            if (lower.contains("proxy") || lower.contains("tunnel")) return true;
        }
        return false;
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

        // Route through proxy if one is active for this refresh
        Proxy javaProxy = toJavaProxy(activeRefreshProxy.get());
        HttpURLConnection conn = (javaProxy != null)
                ? (HttpURLConnection) url.openConnection(javaProxy)
                : (HttpURLConnection) url.openConnection();

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

            if (code == 429) {
                throw new Exception("Rate limited — try again later");
            }
            if (code == 400) {
                throw new Exception("Invalid microsoft (password changed or token expired)");
            }

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
