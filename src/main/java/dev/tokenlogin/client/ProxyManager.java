package dev.tokenlogin.client;

import io.netty.channel.ChannelHandler;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Environment(EnvType.CLIENT)
public class ProxyManager {

    public enum ProxyType {
        SOCKS5, SOCKS4, HTTP, NONE;

        public String displayName() {
            return switch (this) {
                case SOCKS5 -> "SOCKS5";
                case SOCKS4 -> "SOCKS4";
                case HTTP   -> "HTTP";
                case NONE   -> "None";
            };
        }
    }

    // ── State ────────────────────────────────────────────────────────────────
    private static volatile boolean enabled    = false;
    private static volatile String  host       = "";
    private static volatile int     port       = 0;
    private static volatile String  username   = "";
    private static volatile String  password   = "";
    private static volatile ProxyType activeType = ProxyType.NONE;

    // Status shown in the UI
    private static volatile String statusMessage = "";
    private static volatile int    statusColor   = 0xFF888888;

    // ── Getters / setters ────────────────────────────────────────────────────

    public static boolean isEnabled()        { return enabled && activeType != ProxyType.NONE; }
    public static ProxyType getActiveType()  { return activeType; }
    public static String getStatusMessage()  { return statusMessage; }
    public static int    getStatusColor()    { return statusColor; }
    public static String getHost()           { return host; }
    public static int    getPort()           { return port; }

    public static void disable() {
        enabled       = false;
        activeType    = ProxyType.NONE;
        statusMessage = "Proxy disabled";
        statusColor   = 0xFF888888;
        TokenLoginClient.LOGGER.info("Proxy disabled");
    }

    // ── Create the Netty handler for the active proxy type ───────────────────

    public static ChannelHandler createHandler() {
        InetSocketAddress addr = new InetSocketAddress(host, port);
        boolean hasAuth = username != null && !username.isEmpty();

        return switch (activeType) {
            case SOCKS5 -> hasAuth
                    ? new Socks5ProxyHandler(addr, username, password)
                    : new Socks5ProxyHandler(addr);
            case SOCKS4 -> hasAuth
                    ? new Socks4ProxyHandler(addr, username)
                    : new Socks4ProxyHandler(addr);
            case HTTP -> hasAuth
                    ? new HttpProxyHandler(addr, username, password)
                    : new HttpProxyHandler(addr);
            case NONE -> throw new IllegalStateException("No active proxy type");
        };
    }

    // ── Connection test — tries SOCKS5 → HTTP → SOCKS4 ──────────────────────

    /**
     * Attempts to connect through the proxy using all supported protocols.
     * Returns the first working ProxyType, or NONE if all fail.
     * <p>
     * This is a BLOCKING call — always run on a background thread.
     */
    public static ProxyType testAndConnect(String inputAddress, String user, String pass) {
        // Parse ip:port
        String proxyHost;
        int    proxyPort;
        try {
            String[] parts = inputAddress.trim().split(":");
            if (parts.length != 2) throw new IllegalArgumentException("Expected ip:port");
            proxyHost = parts[0];
            proxyPort = Integer.parseInt(parts[1]);
            if (proxyPort < 1 || proxyPort > 65535) throw new IllegalArgumentException("Port out of range");
        } catch (Exception e) {
            statusMessage = "Invalid address — use ip:port";
            statusColor   = 0xFFFF5555;
            return ProxyType.NONE;
        }

        // Store for later use
        host     = proxyHost;
        port     = proxyPort;
        username = user != null ? user.trim() : "";
        password = pass != null ? pass.trim() : "";

        statusMessage = "Testing proxy...";
        statusColor   = 0xFFFFAA00;

        // Try each type in order
        ProxyType[] tryOrder = { ProxyType.SOCKS5, ProxyType.HTTP, ProxyType.SOCKS4 };

        for (ProxyType type : tryOrder) {
            statusMessage = "Trying " + type.displayName() + "...";
            if (testSingleType(type, proxyHost, proxyPort, username, password)) {
                activeType    = type;
                enabled       = true;
                statusMessage = "Connected (" + type.displayName() + ")";
                statusColor   = 0xFF55FF55;
                TokenLoginClient.LOGGER.info("Proxy connected via {} → {}:{}", type, proxyHost, proxyPort);
                return type;
            }
        }

        // All failed
        activeType    = ProxyType.NONE;
        enabled       = false;
        statusMessage = "All proxy types failed";
        statusColor   = 0xFFFF5555;
        TokenLoginClient.LOGGER.warn("Proxy connection failed for {}:{}", proxyHost, proxyPort);
        return ProxyType.NONE;
    }

    // ── Individual protocol tests ────────────────────────────────────────────

    private static boolean testSingleType(ProxyType type, String proxyHost, int proxyPort,
                                          String user, String pass) {
        try {
            return switch (type) {
                case SOCKS5 -> testSocks5(proxyHost, proxyPort, user, pass);
                case SOCKS4 -> testSocks4(proxyHost, proxyPort, user);
                case HTTP   -> testHttpConnect(proxyHost, proxyPort, user, pass);
                case NONE   -> false;
            };
        } catch (Exception e) {
            TokenLoginClient.LOGGER.debug("{} test failed: {}", type, e.getMessage());
            return false;
        }
    }

    /**
     * SOCKS5 handshake test (RFC 1928).
     * Sends greeting → auth (if needed) → connect to a known host → checks reply.
     */
    private static boolean testSocks5(String proxyHost, int proxyPort,
                                      String user, String pass) throws Exception {
        boolean hasAuth = user != null && !user.isEmpty();

        try (Socket sock = createSocket(proxyHost, proxyPort)) {
            OutputStream out = sock.getOutputStream();
            InputStream  in  = sock.getInputStream();

            // ── Greeting ─────────────────────────────────────────────────
            if (hasAuth) {
                // offer NO_AUTH (0x00) and USER/PASS (0x02)
                out.write(new byte[]{ 0x05, 0x02, 0x00, 0x02 });
            } else {
                out.write(new byte[]{ 0x05, 0x01, 0x00 });
            }
            out.flush();

            int ver    = in.read(); // should be 0x05
            int method = in.read(); // chosen method
            if (ver != 0x05) return false;

            // ── Username / password auth (RFC 1929) ──────────────────────
            if (method == 0x02) {
                if (!hasAuth) return false;
                byte[] uBytes = user.getBytes(StandardCharsets.UTF_8);
                byte[] pBytes = pass.getBytes(StandardCharsets.UTF_8);
                byte[] auth = new byte[3 + uBytes.length + pBytes.length];
                auth[0] = 0x01;                               // sub-negotiation version
                auth[1] = (byte) uBytes.length;
                System.arraycopy(uBytes, 0, auth, 2, uBytes.length);
                auth[2 + uBytes.length] = (byte) pBytes.length;
                System.arraycopy(pBytes, 0, auth, 3 + uBytes.length, pBytes.length);
                out.write(auth);
                out.flush();

                int authVer    = in.read();
                int authStatus = in.read();
                if (authVer != 0x01 || authStatus != 0x00) return false;
            } else if (method == 0xFF) {
                return false; // no acceptable methods
            }

            // If we got this far, the SOCKS5 proxy accepted our greeting + auth
            return true;
        }
    }

    /**
     * SOCKS4 handshake test (de-facto standard).
     */
    private static boolean testSocks4(String proxyHost, int proxyPort, String user) throws Exception {
        try (Socket sock = createSocket(proxyHost, proxyPort)) {
            OutputStream out = sock.getOutputStream();
            InputStream  in  = sock.getInputStream();

            // CONNECT to 1.1.1.1:80 as a connectivity check
            byte[] userBytes = (user != null && !user.isEmpty())
                    ? user.getBytes(StandardCharsets.US_ASCII)
                    : new byte[0];

            byte[] request = new byte[9 + userBytes.length];
            request[0] = 0x04;       // SOCKS version
            request[1] = 0x01;       // CONNECT command
            request[2] = 0x00;       // port high byte (80)
            request[3] = 0x50;       // port low  byte (80)
            request[4] = 0x01;       // IP 1.1.1.1
            request[5] = 0x01;
            request[6] = 0x01;
            request[7] = 0x01;
            System.arraycopy(userBytes, 0, request, 8, userBytes.length);
            request[8 + userBytes.length] = 0x00; // null terminator

            out.write(request);
            out.flush();

            int nullByte = in.read(); // should be 0x00
            int status   = in.read(); // 0x5A = granted
            return nullByte == 0x00 && status == 0x5A;
        }
    }

    /**
     * HTTP CONNECT proxy test.
     */
    private static boolean testHttpConnect(String proxyHost, int proxyPort,
                                           String user, String pass) throws Exception {
        try (Socket sock = createSocket(proxyHost, proxyPort)) {
            OutputStream out = sock.getOutputStream();
            InputStream  in  = sock.getInputStream();

            StringBuilder req = new StringBuilder();
            req.append("CONNECT 1.1.1.1:80 HTTP/1.1\r\n");
            req.append("Host: 1.1.1.1:80\r\n");

            if (user != null && !user.isEmpty()) {
                String credentials = Base64.getEncoder()
                        .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
                req.append("Proxy-Authorization: Basic ").append(credentials).append("\r\n");
            }
            req.append("\r\n");

            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Read the status line
            StringBuilder response = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                response.append((char) b);
                if (response.toString().contains("\r\n")) break;
            }

            String statusLine = response.toString().trim();
            // HTTP/1.x 200 Connection established  (or 407 for auth required)
            return statusLine.contains("200");
        }
    }

    private static Socket createSocket(String host, int port) throws Exception {
        Socket sock = new Socket();
        sock.setSoTimeout(5000);
        sock.connect(new InetSocketAddress(host, port), 5000);
        return sock;
    }
}
