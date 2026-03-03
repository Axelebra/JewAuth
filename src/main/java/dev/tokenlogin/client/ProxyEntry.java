package dev.tokenlogin.client;

/**
 * Represents a saved proxy configuration.
 * Instances are mutable — connection state updates in place.
 */
public class ProxyEntry {

    // ── Identity ──────────────────────────────────────────────────────────────
    public String name    = "";
    public String address = "";   // ip:port
    public String username = "";
    public String password = "";

    // ── State from last test ──────────────────────────────────────────────────
    /** The protocol that worked last time (NONE = never tested or failed). */
    public ProxyManager.ProxyType lastType = ProxyManager.ProxyType.NONE;

    /** Epoch-seconds of last successful connect; 0 = never. */
    public long lastConnected = 0L;

    // ── Transient UI state (never persisted) ──────────────────────────────────
    public transient ConnectState connectState = ConnectState.IDLE;
    public transient String       connectError = "";

    public enum ConnectState { IDLE, CONNECTING, SUCCESS, FAILED }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Stable key for dedup and identification.
     * Based on address since that's what uniquely identifies a proxy.
     */
    public String key() {
        return address.trim().toLowerCase();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProxyEntry other)) return false;
        return key().equals(other.key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }
}
