package dev.tokenlogin.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Full-screen account browser.
 *
 * Each row shows:
 *   [Badge] Username  (notes)   JWT: 3h12m | Refreshed 5m ago  [Refresh] [Login] [Copy] [✗]
 *
 * Click a row (not a button) to select it and edit notes in the bottom bar.
 */
public class TokenBrowserScreen extends Screen {

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final int HEADER_H  = 22;
    private static final int FOOTER_H  = 34;
    private static final int ROW_H     = 28;
    private static final int ROW_PAD   =  2;

    // Button geometry
    private static final int BH        = 16;
    private static final int W_DEL     = 16;
    private static final int W_COPY    = 42;
    private static final int W_LOGIN   = 42;
    private static final int W_REFRESH = 52;
    private static final int GAP       =  2;

    // ── State ─────────────────────────────────────────────────────────────────
    private final Screen                 parent;
    private final Consumer<String>       onPaste;
    private final Consumer<AccountEntry> onLoginNow;

    private ButtonWidget reloadButton;
    private ButtonWidget openFolderButton;
    private ButtonWidget myAccountsButton;
    private ButtonWidget backButton;

    // ── Hypixel API key ────────────────────────────────────────────────────
    private TextFieldWidget apiKeyField;

    private String statusText  = "";
    private int    statusColor = 0xFFAAAAAA;

    private double  scrollOffset   = 0;
    private boolean initialLoadDone = false;

    private AccountEntry     selectedAccount = null;
    private TextFieldWidget  notesField;

    // ── Search ────────────────────────────────────────────────────────────────
    private TextFieldWidget searchField;
    private String          searchQuery = "";

    // ── Constructor ───────────────────────────────────────────────────────────

    public TokenBrowserScreen(
            Screen parent,
            Consumer<String> onPaste,
            Consumer<AccountEntry> onLoginNow) {
        super(Text.literal("Token Browser"));
        this.parent     = parent;
        this.onPaste    = onPaste;
        this.onLoginNow = onLoginNow;
    }

    // ── Screen lifecycle ──────────────────────────────────────────────────────

    @Override
    protected void init() {
        backButton = ButtonWidget.builder(
                Text.literal("< Back"),
                btn -> this.client.setScreen(parent)
        ).dimensions(4, 3, 50, 16).build();
        this.addDrawableChild(backButton);

        // Header buttons (right side): My Accounts | Open Folder | Reload
        reloadButton = ButtonWidget.builder(
                Text.literal("Reload"),
                btn -> triggerReload(false)
        ).dimensions(this.width - 58, 3, 54, 16).build();
        this.addDrawableChild(reloadButton);

        openFolderButton = ButtonWidget.builder(
                Text.literal("Open Folder"),
                btn -> openConfigFolder()
        ).dimensions(this.width - 136, 3, 74, 16).build();
        this.addDrawableChild(openFolderButton);

        myAccountsButton = ButtonWidget.builder(
                Text.literal("My Accounts"),
                btn -> this.client.setScreen(new PrismBrowserScreen(this, this.parent, onLoginNow))
        ).dimensions(this.width - 218, 3, 78, 16).build();
        this.addDrawableChild(myAccountsButton);

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Manual Refresh"),
                btn -> this.client.setScreen(
                        new ManualRefreshScreen(this))
        ).dimensions(this.width - 312, 3, 90, 16).build());

        // ── Search field (centered in header) ─────────────────────────────────
        int searchW = Math.min(200, this.width - 440);
        int searchX = (this.width - searchW) / 2;
        searchField = new TextFieldWidget(
                this.textRenderer, searchX, 3, searchW, 16,
                Text.literal("Search"));
        searchField.setMaxLength(64);
        searchField.setPlaceholder(Text.literal("Search accounts..."));
        searchField.setChangedListener(this::onSearchChanged);
        this.addDrawableChild(searchField);

        // ── Hypixel API key field (after Back button) ─────────────────────────
        int apiKeyX = 58;
        int apiKeyW = searchX - apiKeyX - 6;
        if (apiKeyW < 60) apiKeyW = 60;
        apiKeyField = new PasswordFieldWidget(
                this.textRenderer, apiKeyX, 3, apiKeyW, 16,
                Text.literal("API Key"));
        apiKeyField.setMaxLength(128);
        apiKeyField.setPlaceholder(Text.literal("Hypixel API Key..."));
        apiKeyField.setText(ProxyConfig.getHypixelApiKey());
        apiKeyField.setChangedListener(key -> ProxyConfig.setHypixelApiKey(key));
        this.addDrawableChild(apiKeyField);

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
                tokenlogin$handleClick(click.x(), click.y(), click.button()));
    }

    private void openConfigFolder() {
        java.nio.file.Path dir = AccountStorage.getBaseDir();
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir);
        } catch (IOException e) {
            TokenLoginClient.LOGGER.warn("Failed to create config dir: {}", e.getMessage());
        }
        Util.getOperatingSystem().open(dir.toFile());
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private void onSearchChanged(String text) {
        searchQuery = text.toLowerCase().trim();
        scrollOffset = 0;
    }

    private List<AccountEntry> getFilteredAccounts() {
        List<AccountEntry> all = AccountManager.getAccounts();
        List<AccountEntry> result = new ArrayList<>();

        for (AccountEntry acc : all) {
            // Show only: valid JWT (not expired) OR has refresh capability
            // Hide:      expired/missing token with no refresh option
            boolean hasValidJwt = !acc.minecraftToken.isBlank() && !acc.isJwtExpired();
            if (!hasValidJwt && !acc.hasRefreshCapability()) continue;

            if (searchQuery.isEmpty() || (acc.username != null && acc.username.toLowerCase().contains(searchQuery))) {
                result.add(acc);
            }
        }

        // Sort by highest SB level first, accounts without SB data go to the bottom
        result.sort((a, b) -> {
            int lvlA = (a.skyblockInfo != null && a.skyblockInfo.error() == null) ? a.skyblockInfo.skyblockLevel() : -1;
            int lvlB = (b.skyblockInfo != null && b.skyblockInfo.error() == null) ? b.skyblockInfo.skyblockLevel() : -1;
            return Integer.compare(lvlB, lvlA);
        });

        return result;
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

    private void triggerReload(boolean silent) {
        reloadButton.active = false;
        if (!silent) setStatus("Scanning files...", 0xFFFFAA00);

        Thread t = new Thread(() -> {
            AccountManager.reload();
            this.client.execute(() -> {
                int n = AccountManager.getAccounts().size();
                setStatus(n == 0
                        ? "No accounts found — drop launcher files into config/tokenlogin/"
                        : n + " account" + (n == 1 ? "" : "s") + " loaded",
                        n == 0 ? 0xFF888888 : 0xFF55FF55);
                reloadButton.active = true;
                selectedAccount     = null;
                notesField.active   = false;
                notesField.setText("");
            });
        }, "TokenLogin-Reload");
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

        // Footer background
        ctx.fill(0, this.height - FOOTER_H, this.width, this.height, 0xAA000000);

        // Match count when searching
        if (!searchQuery.isEmpty()) {
            int total = AccountManager.getAccounts().size();
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
        String badge = "[" + acc.badge() + "]";
        int    bc    = acc.badgeColor();
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

        // Notes (grey, truncated)
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
            tx += this.textRenderer.getWidth(refreshString(acc)) + 8;
        }

        // Skyblock info: NW + coop
        String sbStr = skyblockString(acc);
        if (!sbStr.isEmpty() && tx < rx - 30) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("|"), tx, lY2, 0xFF444444);
            tx += this.textRenderer.getWidth("| ") + 2;
            if (tx < rx - 4) {
                ctx.drawTextWithShadow(this.textRenderer,
                        Text.literal(sbStr), tx, lY2, skyblockColor(acc));
            }
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

    private void tokenlogin$handleClick(double mx, double my, int button) {
        if (button != 0) return;
        // BUGFIX: skip header and footer — widgets there handle their own clicks.
        // Previously this handler also manually checked header buttons, causing
        // actions like openConfigFolder() to fire twice (once here, once in the widget).
        if (my < HEADER_H || my >= this.height - FOOTER_H) return;

        // Use filtered accounts for click handling
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
            boolean refreshed = false;
            try {
                AccountManager.refreshAccount(acc, proxy);
                refreshed = true;
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

            // Always fetch skyblock data (only needs UUID + API key, not a valid token)
            String apiKey = ProxyConfig.getHypixelApiKey();
            if (!apiKey.isBlank() && acc.uuid != null && !acc.uuid.isBlank()) {
                acc.skyblockFetching = true;
                SkyblockFetcher.SkyblockInfo info = SkyblockFetcher.fetch(acc.uuid, apiKey);
                acc.skyblockInfo = info;
                acc.skyblockFetching = false;
                AccountStorage.saveSkyblockInfo(acc);
            }
        }, "TokenLogin-Refresh");
        t.setDaemon(true);
        t.start();
    }

    private void doLoginNow(AccountEntry acc) {
        this.client.setScreen(parent);
        onLoginNow.accept(acc);
    }

    private void doCopy(AccountEntry acc) {
        this.client.keyboard.setClipboard(acc.minecraftToken);
        setStatus("Token copied to clipboard — " + acc.username, 0xFF55FF55);
    }

    private void doDelete(AccountEntry acc) {
        String name = acc.username;
        if (selectedAccount == acc) {
            selectedAccount   = null;
            notesField.active = false;
            notesField.setText("");
        }
        AccountManager.markDead(acc);
        setStatus(name + " removed. " + AccountManager.getAccounts().size() + " remaining.", 0xFFAAAAAA);
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

    private static String skyblockString(AccountEntry acc) {
        if (acc.skyblockFetching) return "SB: loading...";
        if (acc.skyblockInfo == null) return "";
        if (acc.skyblockInfo.error() != null) return "SB: " + truncate(acc.skyblockInfo.error(), 20);
        String coop = acc.skyblockInfo.isCoop()
                ? "Coop(" + acc.skyblockInfo.coopMembers() + ")"
                : "Solo";
        return "SB Lv" + acc.skyblockInfo.skyblockLevel() + " " + coop;
    }

    private static int skyblockColor(AccountEntry acc) {
        if (acc.skyblockFetching) return 0xFFFFAA00;
        if (acc.skyblockInfo == null) return 0xFF555555;
        if (acc.skyblockInfo.error() != null) return 0xFFFF5555;
        return acc.skyblockInfo.isCoop() ? 0xFFFFAA00 : 0xFF55AAFF;
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
