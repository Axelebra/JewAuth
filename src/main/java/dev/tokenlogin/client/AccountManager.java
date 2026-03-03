package dev.tokenlogin.client;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Central manager for all loaded accounts.
 *
 * Scans .minecraft/config/tokenlogin/ for .json and .txt files,
 * parses them with LauncherParser, deduplicates by deadKey,
 * applies the mod's own cached tokens on top, and filters out
 * accounts the user has dismissed.
 */
public class AccountManager {

    /** Thread-safe list of currently visible accounts. */
    private static final List<AccountEntry> accounts = new CopyOnWriteArrayList<>();

    public static List<AccountEntry> getAccounts() {
        return Collections.unmodifiableList(accounts);
    }

    /**
     * Scans the config folder and rebuilds the account list.
     * Thread-safe — can be called from a background thread.
     */
    public static void reload() {
        AccountStorage.load();

        List<AccountEntry> loaded = new ArrayList<>();
        Path dir = AccountStorage.getBaseDir();

        if (!Files.exists(dir)) {
            try { Files.createDirectories(dir); } catch (Exception ignored) {}
            accounts.clear();
            return;
        }

        try (Stream<Path> files = Files.walk(dir)) {
            files
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String n = p.getFileName().toString();
                    return (n.endsWith(".json") || n.endsWith(".txt"))
                            && !n.startsWith(".");   // skip .cache.json
                })
                .sorted(Comparator.comparing(p -> p.toString()))
                .forEach(file -> {
                    List<AccountEntry> parsed = LauncherParser.parseFile(file);
                    for (AccountEntry entry : parsed) {
                        if (AccountStorage.isDead(entry)) continue;
                        AccountStorage.applyCache(entry);  // overlay any fresher cached tokens
                        loaded.add(entry);
                    }
                });
        } catch (Exception e) {
            TokenLoginClient.LOGGER.warn("AccountManager: scan failed: {}", e.getMessage());
        }

        // Deduplicate — same account appearing in multiple files keeps the first copy
        Set<String> seen = new LinkedHashSet<>();
        List<AccountEntry> deduped = new ArrayList<>();
        for (AccountEntry entry : loaded) {
            if (seen.add(entry.deadKey())) {
                deduped.add(entry);
            }
        }

        accounts.clear();
        accounts.addAll(deduped);

        TokenLoginClient.LOGGER.info("AccountManager: loaded {} account(s)", accounts.size());
    }

    /**
     * Runs the full MS auth refresh chain for the given account,
     * updates the entry in-place, and persists to the mod's cache.
     *
     * Blocking — call from a background thread.
     *
     * @throws Exception with a human-readable message on failure.
     */
    public static void refreshAccount(AccountEntry entry) throws Exception {
        if (!entry.hasRefreshCapability()) {
            throw new Exception("No refresh token available for this account");
        }

        MicrosoftAuthChain.RefreshResult result =
                MicrosoftAuthChain.refresh(entry.refreshToken, entry.clientId, entry.liveAuth);

        // Update entry in-place
        entry.minecraftToken = result.minecraftToken();
        entry.refreshToken   = result.newRefreshToken();
        entry.jwtExpiry      = result.expiresEpoch();
        entry.lastRefreshed  = System.currentTimeMillis() / 1000L;

        // Persist so the fresh token survives a reload
        AccountStorage.updateTokens(entry);
    }

    /**
     * Marks an account as dead (hidden forever) and removes it from the live list.
     */
    public static void markDead(AccountEntry entry) {
        accounts.remove(entry);
        AccountStorage.markDead(entry);
    }
}
