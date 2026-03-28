package dev.tokenlogin.client;

/**
 * Represents a single Minecraft account loaded from a launcher file or manual JWT.
 * Instances are mutable — refresh state and token data update in place.
 */
public class AccountEntry {

    /**
     * Parser format that produced this entry.
     *
     *   FORMAT_A  — array accounts, ygg.token, msa.refresh_token  (Prism, MultiMC, Lunar export, etc.)
     *   FORMAT_B  — object accounts, activeAccountLocalId          (Official Launcher, Lunar Client, etc.)
     *   FORMAT_C  — array accounts, nested auth.mcToken             (custom launchers with OAuth state)
     *   FORMAT_D  — object accounts, no activeAccountLocalId        (GDLauncher, etc.)
     *   UNKNOWN   — fallback / could not determine
     */
    public enum SourceType {
        FORMAT_A, FORMAT_B, FORMAT_C, FORMAT_D, UNKNOWN;

        /**
         * Human-readable launcher name shown in the UI badge.
         *
         *   FORMAT_A = Prism / MultiMC / PolyMC (array accounts, ygg.token)
         *   FORMAT_B = Lunar Client (object accounts, activeAccountLocalId)
         *              The vanilla MC launcher stores tokens outside JSON files
         *              that would end up here, so FORMAT_B entries are Lunar.
         *   FORMAT_C = Badlion / custom (array accounts, nested auth object)
         *   FORMAT_D = GDLauncher (object accounts, no activeAccountLocalId)
         *   UNKNOWN  = Plain-text JWT file or unrecognized format
         */
        public String displayName() {
            return switch (this) {
                case FORMAT_A -> "Prism";
                case FORMAT_B -> "Lunar";
                case FORMAT_C -> "Badlion";
                case FORMAT_D -> "GDL";
                case UNKNOWN  -> "Text";
            };
        }

        public int badgeColor() {
            return switch (this) {
                case FORMAT_A -> 0xFF6EC6FF;   // light blue
                case FORMAT_B -> 0xFF6BFFC8;   // green
                case FORMAT_C -> 0xFFFFD700;   // gold
                case FORMAT_D -> 0xFF4FC3F7;   // cyan
                case UNKNOWN  -> 0xFF888888;   // gray
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

    /**
     * Debug parser path, e.g. "A-1", "B-2", "C-1", "D-1".
     * Shown in the UI badge so the user can report exactly which code path
     * was taken when something goes wrong.
     */
    public String parserPath = "?";

    /** Returns the badge text shown in the UI (launcher name). */
    public String badge() {
        return sourceType.displayName();
    }

    /** Returns the badge color based on the format. */
    public int badgeColor() {
        return sourceType.badgeColor();
    }

    /** Epoch-seconds of last successful refresh by this mod; 0 = never. */
    public long lastRefreshed = 0L;

    /** User-editable notes for this account. */
    public String notes = "";

    /** Address of the proxy bound to this account, or null if none. */
    public String boundProxyAddress = null;

    // ── Transient UI state (never persisted) ──────────────────────────────────
    public transient RefreshState refreshState = RefreshState.IDLE;
    public transient String       refreshError = "";

    // ── Transient Skyblock data (fetched from Hypixel API) ─────────────────
    public transient SkyblockFetcher.SkyblockInfo skyblockInfo = null;
    public transient boolean skyblockFetching = false;

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
