package dev.tokenlogin.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Browser screen that reads accounts directly from the current PrismLauncher
 * instance's accounts.json.  Same look & feel as TokenBrowserScreen.
 */
public class PrismBrowserScreen extends Screen {

    // ── Layout constants (same as TokenBrowserScreen) ─────────────────────────
    private static final int HEADER_H  = 22;
    private static final int FOOTER_H  = 34;
    private static final int ROW_H     = 28;
    private static final int ROW_PAD   =  2;

    private static final int BH        = 16;
    private static final int W_DEL     = 16;
    private static final int W_COPY    = 42;
    private static final int W_LOGIN   = 42;
    private static final int W_REFRESH = 52;
    private static final int GAP       =  2;

    // ── State ─────────────────────────────────────────────────────────────────
    private final Screen                 parent;
    private final Screen                 loginTargetScreen; // screen to return to on Login (multiplayer)
    private final Consumer<AccountEntry> onLoginNow;

    private final List<AccountEntry> prismAccounts = new CopyOnWriteArrayList<>();

    private ButtonWidget reloadButton;
    private ButtonWidget backButton;

    private String statusText  = "";
    private int    statusColor = 0xFFAAAAAA;

    private double  scrollOffset   = 0;
    private boolean initialLoadDone = false;

    private AccountEntry    selectedAccount = null;
    private TextFieldWidget notesField;

    // ── Search ────────────────────────────────────────────────────────────────
    private TextFieldWidget searchField;
    private String          searchQuery = "";

    // ── Constructor ───────────────────────────────────────────────────────────

    public PrismBrowserScreen(Screen parent, Screen loginTargetScreen, Consumer<AccountEntry> onLoginNow) {
        super(Text.literal("My Accounts"));
        this.parent            = parent;
        this.loginTargetScreen = loginTargetScreen;
        this.onLoginNow        = onLoginNow;
    }

    // ── Prism path detection ──────────────────────────────────────────────────
    //
    // The game runs from:  <PrismRoot>/instances/<name>/.minecraft/
    // accounts.json is at: <PrismRoot>/accounts.json
    //
    // Walk up from the game directory until we find a folder named "instances",
    // then take its parent as the Prism root.

    private static Path findPrismAccountsJson() {
        Path gameDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

        // Walk upward looking for the "instances" directory
        Path current = gameDir;
        while (current != null) {
            if (current.getFileName() != null
                    && current.getFileName().toString().equalsIgnoreCase("instances")) {
                Path prismRoot = current.getParent();
                if (prismRoot != null) {
                    Path accounts = prismRoot.resolve("accounts.json");
                    if (Files.exists(accounts)) return accounts;
                }
            }
            current = current.getParent();
        }

        TokenLoginClient.LOGGER.warn("PrismBrowser: could not locate accounts.json "
                + "by walking up from game dir: {}", gameDir);
        return null;
    }

    // ── Screen lifecycle ──────────────────────────────────────────────────────

    @Override
    protected void init() {
        backButton = ButtonWidget.builder(
                Text.literal("< Back"),
                btn -> this.client.setScreen(parent)
        ).dimensions(4, 3, 50, 16).build();
        this.addDrawableChild(backButton);

        reloadButton = ButtonWidget.builder(
                Text.literal("Reload"),
                btn -> triggerReload(false)
        ).dimensions(this.width - 58, 3, 54, 16).build();
        this.addDrawableChild(reloadButton);

        // ── Search field (centered in header) ─────────────────────────────────
        int searchW = Math.min(200, this.width - 180);
        int searchX = (this.width - searchW) / 2;
        searchField = new TextFieldWidget(
                this.textRenderer, searchX, 3, searchW, 16,
                Text.literal("Search"));
        searchField.setMaxLength(64);
        searchField.setPlaceholder(Text.literal("Search accounts..."));
        searchField.setChangedListener(this::onSearchChanged);
        this.addDrawableChild(searchField);

        // ── Notes field (bottom bar) ──────────────────────────────────────────
        int notesY = this.height - FOOTER_H + 2;
        int notesW = this.width - 8;
        notesField = new TextFieldWidget(
                this.textRenderer, 4, notesY, notesW, 14,
                Text.literal("Notes"));
        notesField.setMaxLength(256);
        notesField.setPlaceholder(Text.literal("Click a row to edit notes..."));
        notesField.active = false;
        notesField.setChangedListener(this::onNotesChanged);
        this.addDrawableChild(notesField);

        if (!initialLoadDone) {
            initialLoadDone = true;
            triggerReload(true);
        }

        ScreenMouseEvents.beforeMouseClick(this).register((screen, click) ->
                prism$handleClick(click.x(), click.y(), click.button()));
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private void onSearchChanged(String text) {
        searchQuery = text.toLowerCase().trim();
        scrollOffset = 0;
    }

    private List<AccountEntry> getFilteredAccounts() {
        if (searchQuery.isEmpty()) return prismAccounts;
        List<AccountEntry> filtered = new ArrayList<>();
        for (AccountEntry acc : prismAccounts) {
            if (acc.username != null && acc.username.toLowerCase().contains(searchQuery)) {
                filtered.add(acc);
            }
        }
        return filtered;
    }

    // ── Notes ─────────────────────────────────────────────────────────────────

    private void onNotesChanged(String text) {
        if (selectedAccount != null) {
            selectedAccount.notes = text;
            AccountStorage.saveNotes(selectedAccount);
        }
    }

    private void selectAccount(AccountEntry acc) {
        if (selectedAccount == acc) {
            selectedAccount = null;
            notesField.active = false;
            notesField.setText("");
            notesField.setPlaceholder(Text.literal("Click a row to edit notes..."));
            return;
        }
        selectedAccount = acc;
        notesField.active = true;
        notesField.setText(acc.notes != null ? acc.notes : "");
        notesField.setPlaceholder(Text.literal("Add notes for " + acc.username + "..."));
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private void triggerReload(boolean silent) {
        reloadButton.active = false;
        if (!silent) setStatus("Looking for Prism accounts...", 0xFFFFAA00);

        Thread t = new Thread(() -> {
            Path accountsJson = findPrismAccountsJson();
            List<AccountEntry> loaded = new ArrayList<>();

            if (accountsJson == null || !Files.exists(accountsJson)) {
                this.client.execute(() -> {
                    prismAccounts.clear();
                    setStatus("PrismLauncher accounts.json not found", 0xFFFF5555);
                    reloadButton.active = true;
                    selectedAccount = null;
                    notesField.active = false;
                    notesField.setText("");
                });
                return;
            }

            try {
                // Make sure the mod's cache (notes, dead list) is up to date
                AccountStorage.load();

                List<AccountEntry> parsed = LauncherParser.parseFile(accountsJson);
                for (AccountEntry entry : parsed) {
                    if (AccountStorage.isDead(entry)) continue;
                    AccountStorage.applyCache(entry);
                    loaded.add(entry);
                }
            } catch (Exception e) {
                TokenLoginClient.LOGGER.warn("PrismBrowser: failed to load: {}", e.getMessage());
            }

            List<AccountEntry> finalLoaded = loaded;
            this.client.execute(() -> {
                prismAccounts.clear();
                prismAccounts.addAll(finalLoaded);
                int n = prismAccounts.size();
                if (n == 0) {
                    setStatus("No accounts in PrismLauncher accounts.json", 0xFF888888);
                } else {
                    setStatus(n + " Prism account" + (n == 1 ? "" : "s") + " loaded", 0xFF55FF55);
                }
                reloadButton.active = true;
                selectedAccount = null;
                notesField.active = false;
                notesField.setText("");
            });
        }, "TokenLogin-PrismReload");
        t.setDaemon(true);
        t.start();
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xC0101010);

        // Header
        ctx.fill(0, 0, this.width, HEADER_H, 0xDD000000);

        // Row list
        List<AccountEntry> accounts = getFilteredAccounts();
        int listTop    = HEADER_H;
        int listBottom = this.height - FOOTER_H;
        int listH      = listBottom - listTop;
        int totalH     = accounts.size() * (ROW_H + ROW_PAD);

        double maxScroll = Math.max(0, totalH - listH);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        ctx.enableScissor(0, listTop, this.width, listBottom);

        int rowX = 4;
        int rowW = this.width - 8;

        for (int i = 0; i < accounts.size(); i++) {
            AccountEntry acc = accounts.get(i);
            int rowY = listTop + i * (ROW_H + ROW_PAD) - (int) scrollOffset;
            if (rowY + ROW_H < listTop || rowY > listBottom) continue;
            boolean hovered = mouseX >= rowX && mouseX < rowX + rowW
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            boolean selected = acc == selectedAccount;
            renderRow(ctx, acc, rowX, rowY, rowW, ROW_H, mouseX, mouseY, hovered, selected);
        }

        ctx.disableScissor();

        // Scrollbar
        if (totalH > listH && maxScroll > 0) {
            int sbX    = this.width - 4;
            int sbH    = listH;
            int thumbH = Math.max(20, sbH * listH / totalH);
            int thumbY = listTop + (int) (scrollOffset * (sbH - thumbH) / maxScroll);
            ctx.fill(sbX, listTop, sbX + 3, listBottom, 0xFF333333);
            ctx.fill(sbX, thumbY,  sbX + 3, thumbY + thumbH, 0xFF888888);
        }

        // Footer
        ctx.fill(0, this.height - FOOTER_H, this.width, this.height, 0xAA000000);

        if (!searchQuery.isEmpty()) {
            int total = prismAccounts.size();
            int shown = accounts.size();
            String matchText = shown + "/" + total + " matches";
            int matchColor = shown == 0 ? 0xFFFF5555 : 0xFF55FFFF;
            ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal(matchText),
                    this.width - this.textRenderer.getWidth(matchText) - 6,
                    this.height - FOOTER_H + 20, matchColor);
        }

        if (!statusText.isEmpty()) {
            ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal(statusText), 4, this.height - FOOTER_H + 20, statusColor);
        }

        for (var element : this.children()) {
            if (element instanceof Drawable drawable) {
                drawable.render(ctx, mouseX, mouseY, delta);
            }
        }
    }

    private void renderRow(DrawContext ctx, AccountEntry acc,
                           int x, int y, int w, int h,
                           int mouseX, int mouseY, boolean hovered, boolean selected) {
        if (selected)     ctx.fill(x, y, x + w, y + h, 0x33FFFF55);
        else if (hovered) ctx.fill(x, y, x + w, y + h, 0x22FFFFFF);
        ctx.fill(x, y + h - 1, x + w, y + h, 0x44FFFFFF);

        int right = x + w - 2;
        int by    = y + (h - BH) / 2;

        int dx = right          - W_DEL;
        int px = dx   - GAP - W_COPY;
        int lx = px   - GAP - W_LOGIN;
        int rx = lx   - GAP - W_REFRESH;

        boolean canRefresh = acc.hasRefreshCapability()
                && acc.refreshState != AccountEntry.RefreshState.REFRESHING;
        boolean hasToken   = !acc.minecraftToken.isBlank();
        String  refreshLbl = acc.refreshState == AccountEntry.RefreshState.REFRESHING
                ? "..." : "Refresh";

        drawBtn(ctx, mouseX, mouseY, rx, by, W_REFRESH, BH, refreshLbl,  canRefresh);
        drawBtn(ctx, mouseX, mouseY, lx, by, W_LOGIN,   BH, "Login",     hasToken);
        drawBtn(ctx, mouseX, mouseY, px, by, W_COPY,    BH, "Copy",      hasToken);
        drawBtn(ctx, mouseX, mouseY, dx, by, W_DEL,     BH, "X",         true);

        int lY1 = y + 4;
        int lY2 = y + 15;

        // Badge
        String badge = "[" + acc.sourceType.badge() + "]";
        int    bc    = acc.sourceType.badgeColor();
        ctx.drawTextWithShadow(this.textRenderer,
                Text.literal(badge).styled(s -> s.withColor(bc)), x + 2, lY1, bc);
        int bw = this.textRenderer.getWidth(badge) + 4;

        // Username with search highlight
        int    nc   = nameColor(acc);
        String name = acc.username.length() > 20
                ? acc.username.substring(0, 18) + ".." : acc.username;

        if (!searchQuery.isEmpty()) {
            String nameLower = name.toLowerCase();
            int matchIdx = nameLower.indexOf(searchQuery);
            if (matchIdx >= 0) {
                int nameX = x + 2 + bw;
                String prefix = name.substring(0, matchIdx);
                if (!prefix.isEmpty()) {
                    ctx.drawTextWithShadow(this.textRenderer,
                            Text.literal(prefix).styled(s -> s.withColor(nc)), nameX, lY1, nc);
                    nameX += this.textRenderer.getWidth(prefix);
                }
                String match = name.substring(matchIdx, Math.min(matchIdx + searchQuery.length(), name.length()));
                ctx.drawTextWithShadow(this.textRenderer,
                        Text.literal(match).styled(s -> s.withColor(0xFFFF55)), nameX, lY1, 0xFFFFFF55);
                nameX += this.textRenderer.getWidth(match);
                String suffix = name.substring(Math.min(matchIdx + searchQuery.length(), name.length()));
                if (!suffix.isEmpty()) {
                    ctx.drawTextWithShadow(this.textRenderer,
                            Text.literal(suffix).styled(s -> s.withColor(nc)), nameX, lY1, nc);
                }
            } else {
                ctx.drawTextWithShadow(this.textRenderer,
                        Text.literal(name).styled(s -> s.withColor(nc)), x + 2 + bw, lY1, nc);
            }
        } else {
            ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal(name).styled(s -> s.withColor(nc)), x + 2 + bw, lY1, nc);
        }

        int nameEnd = x + 2 + bw + this.textRenderer.getWidth(name) + 6;

        // Notes
        if (acc.notes != null && !acc.notes.isBlank()) {
            int maxNotesW = rx - nameEnd - 8;
            if (maxNotesW > 20) {
                String noteStr = acc.notes;
                while (this.textRenderer.getWidth(noteStr) > maxNotesW && noteStr.length() > 1) {
                    noteStr = noteStr.substring(0, noteStr.length() - 1);
                }
                if (noteStr.length() < acc.notes.length()) noteStr += "..";
                ctx.drawTextWithShadow(this.textRenderer,
                        Text.literal(noteStr).styled(s -> s.withColor(0x999999)),
                        nameEnd, lY1, 0xFF999999);
            }
        }

        // Line 2: JWT timer | refresh status
        int tx = x + 2;
        String jwtStr = jwtString(acc);
        int    jwtCol = jwtColor(acc);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(jwtStr), tx, lY2, jwtCol);
        tx += this.textRenderer.getWidth(jwtStr) + 8;

        if (tx < rx - 30) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("|"), tx, lY2, 0xFF444444);
            tx += this.textRenderer.getWidth("| ") + 2;
        }
        if (tx < rx - 4) {
            ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal(refreshString(acc)), tx, lY2, refreshColor(acc));
        }
    }

    private void drawBtn(DrawContext ctx, int mouseX, int mouseY,
                         int bx, int by, int bw, int bh,
                         String label, boolean active) {
        boolean over = active
                && mouseX >= bx && mouseX < bx + bw
                && mouseY >= by && mouseY < by + bh;
        int border = !active ? 0xFF555555 : over ? 0xFFAAAAAA : 0xFF888888;
        int bg     = !active ? 0xFF444444 : over ? 0xFF888888 : 0xFF666666;
        int fg     = !active ? 0xFF888888 : 0xFFFFFFFF;
        ctx.fill(bx, by, bx + bw, by + bh, border);
        ctx.fill(bx + 1, by + 1, bx + bw - 1, by + bh - 1, bg);
        int tw = this.textRenderer.getWidth(label);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(label),
                bx + (bw - tw) / 2, by + (bh - 8) / 2, fg);
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    private void prism$handleClick(double mx, double my, int button) {
        if (button != 0) return;
        // Skip header and footer — widgets there handle their own clicks
        if (my < HEADER_H || my >= this.height - FOOTER_H) return;

        List<AccountEntry> accounts = getFilteredAccounts();
        int listTop = HEADER_H;
        int rowW    = this.width - 8;

        for (int i = 0; i < accounts.size(); i++) {
            AccountEntry acc = accounts.get(i);
            int rowY = listTop + i * (ROW_H + ROW_PAD) - (int) scrollOffset;
            if (my < rowY || my >= rowY + ROW_H) continue;

            int x     = 4;
            int right = x + rowW - 2;
            int by    = rowY + (ROW_H - BH) / 2;

            int dx = right          - W_DEL;
            int px = dx   - GAP - W_COPY;
            int lx = px   - GAP - W_LOGIN;
            int rx = lx   - GAP - W_REFRESH;

            if (hit(dx, W_DEL,     mx, my, by))                                    { doDelete(acc);   return; }
            if (hit(px, W_COPY,    mx, my, by) && !acc.minecraftToken.isBlank())    { doCopy(acc);     return; }
            if (hit(lx, W_LOGIN,   mx, my, by) && !acc.minecraftToken.isBlank())    { doLoginNow(acc); return; }
            if (hit(rx, W_REFRESH, mx, my, by) && acc.hasRefreshCapability()
                    && acc.refreshState != AccountEntry.RefreshState.REFRESHING)     { doRefresh(acc);  return; }

            selectAccount(acc);
            return;
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horiz, double vert) {
        scrollOffset -= vert * ROW_H;
        return true;
    }

    private boolean hit(int bx, int bw, double mx, double my, int by) {
        return mx >= bx && mx < bx + bw && my >= by && my < by + BH;
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void doRefresh(AccountEntry acc) {
        acc.refreshState = AccountEntry.RefreshState.REFRESHING;
        acc.refreshError = "";

        // Grab proxy NOW on the UI thread so grouping follows click order
        ProxyEntry proxy = MicrosoftAuthChain.grabProxy();

        Thread t = new Thread(() -> {
            try {
                AccountManager.refreshAccount(acc, proxy);
                this.client.execute(() -> {
                    acc.refreshState = AccountEntry.RefreshState.SUCCESS;
                    setStatus("Refreshed: " + acc.username, 0xFF55FF55);
                });
            } catch (Exception e) {
                TokenLoginClient.LOGGER.warn("Refresh failed [{}]: {}", acc.username, e.getMessage());
                this.client.execute(() -> {
                    acc.refreshState = AccountEntry.RefreshState.FAILED;
                    acc.refreshError = e.getMessage() != null ? e.getMessage() : "Unknown error";
                    setStatus("Refresh failed: " + acc.refreshError, 0xFFFF5555);
                });
            }
        }, "TokenLogin-PrismRefresh");
        t.setDaemon(true);
        t.start();
    }

    private void doLoginNow(AccountEntry acc) {
        this.client.setScreen(loginTargetScreen);
        onLoginNow.accept(acc);
    }

    private void doCopy(AccountEntry acc) {
        this.client.keyboard.setClipboard(acc.minecraftToken);
        setStatus("Token copied — " + acc.username, 0xFF55FF55);
    }

    private void doDelete(AccountEntry acc) {
        String name = acc.username;
        if (selectedAccount == acc) {
            selectedAccount   = null;
            notesField.active = false;
            notesField.setText("");
        }
        prismAccounts.remove(acc);
        AccountStorage.markDead(acc);
        setStatus(name + " hidden. " + prismAccounts.size() + " remaining.", 0xFFAAAAAA);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int nameColor(AccountEntry acc) {
        if (acc.refreshState == AccountEntry.RefreshState.REFRESHING) return 0xFFFFAA00;
        if (acc.refreshState == AccountEntry.RefreshState.FAILED)     return 0xFFFF7777;
        if (acc.isJwtExpired()) return 0xFFFF5555;
        return 0xFFFFFFFF;
    }

    private static String jwtString(AccountEntry acc) {
        if (acc.minecraftToken.isBlank()) return "JWT: none";
        if (acc.jwtExpiry == 0L)          return "JWT: ?";
        long diff = acc.jwtExpiry - System.currentTimeMillis() / 1000L;
        return diff <= 0 ? "JWT: expired" : "JWT: " + fmt(diff);
    }

    private static int jwtColor(AccountEntry acc) {
        if (acc.minecraftToken.isBlank() || acc.jwtExpiry == 0L) return 0xFF888888;
        long diff = acc.jwtExpiry - System.currentTimeMillis() / 1000L;
        if (diff <= 0 || diff < 300) return 0xFFFF5555;
        if (diff < 1800)             return 0xFFFFAA00;
        return 0xFF55FF55;
    }

    private static String refreshString(AccountEntry acc) {
        return switch (acc.refreshState) {
            case REFRESHING -> "Refreshing...";
            case FAILED     -> "X " + truncate(acc.refreshError, 28);
            default -> {
                if (!acc.hasRefreshCapability()) yield "No refresh";
                if (acc.lastRefreshed > 0)
                    yield "Refreshed " + fmt(System.currentTimeMillis() / 1000L - acc.lastRefreshed) + " ago";
                yield "Ready";
            }
        };
    }

    private static int refreshColor(AccountEntry acc) {
        return switch (acc.refreshState) {
            case REFRESHING -> 0xFFFFAA00;
            case FAILED     -> 0xFFFF5555;
            case SUCCESS    -> 0xFF55FF55;
            default -> acc.hasRefreshCapability() ? 0xFF55AAFF : 0xFF555555;
        };
    }

    private void setStatus(String msg, int color) {
        this.statusText  = msg;
        this.statusColor = color;
    }

    private static String fmt(long secs) {
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "..";
    }
}
