package dev.tokenlogin.client;

/**
 * Represents a single Minecraft account loaded from a launcher file or manual JWT.
 * Instances are mutable — refresh state and token data update in place.
 */
public class AccountEntry {

    public enum SourceType {
        PRISM, MULTIMC, MODRINTH, ATLAUNCHER, GDLAUNCHER, MANUAL, UNKNOWN;

        public String badge() {
            return switch (this) {
                case PRISM      -> "Prism";
                case MULTIMC    -> "MultiMC";
                case MODRINTH   -> "Modrinth";
                case ATLAUNCHER -> "ATL";
                case GDLAUNCHER -> "GDL";
                case MANUAL     -> "Manual";
                case UNKNOWN    -> "?";
            };
        }

        public int badgeColor() {
            return switch (this) {
                case PRISM      -> 0xFF6EC6FF;
                case MULTIMC    -> 0xFF6BFFC8;
                case MODRINTH   -> 0xFF1BD96A;
                case ATLAUNCHER -> 0xFFFFD700;
                case GDLAUNCHER -> 0xFF4FC3F7;
                case MANUAL     -> 0xFFCCAAFF;
                case UNKNOWN    -> 0xFF888888;
            };
        }
    }

    public enum RefreshState { IDLE, REFRESHING, SUCCESS, FAILED }

    // ── Identity ──────────────────────────────────────────────────────────────
    public String username   = "";
    public String uuid       = "";

    // ── Tokens ───────────────────────────────────────────────────────────────
    /** Current Minecraft JWT access token. */
    public String minecraftToken = "";

    /** Epoch-seconds when the JWT expires; 0 = not decoded. */
    public long jwtExpiry = 0L;

    /** Microsoft refresh token — null means JWT-only, no refresh available. */
    public String refreshToken = null;

    /** OAuth client_id used to issue the refresh token — required for refresh. */
    public String clientId = null;

    /** True if this account's refresh token must go through login.live.com (e.g. GDLauncher). */
    public boolean liveAuth = false;

    // ── Metadata ──────────────────────────────────────────────────────────────
    public String     sourceFile = "";
    public SourceType sourceType = SourceType.UNKNOWN;

    /** Epoch-seconds of last successful refresh by this mod; 0 = never. */
    public long lastRefreshed = 0L;

    /** User-editable notes for this account. */
    public String notes = "";

    // ── Transient UI state (never persisted) ──────────────────────────────────
    public transient RefreshState refreshState = RefreshState.IDLE;
    public transient String       refreshError = "";

    // ── Computed helpers ──────────────────────────────────────────────────────

    public boolean hasRefreshCapability() {
        return refreshToken != null && !refreshToken.isBlank()
                && clientId   != null && !clientId.isBlank();
    }

    public boolean isJwtExpired() {
        if (jwtExpiry == 0L || minecraftToken.isBlank()) return false;
        return System.currentTimeMillis() / 1000L >= jwtExpiry;
    }

    /**
     * Stable key used for the dead list and token cache.
     * Uses username + uuid so same account in two files maps to one entry.
     */
    public String deadKey() {
        return username.toLowerCase() + "|" + uuid.toLowerCase();
    }
}
