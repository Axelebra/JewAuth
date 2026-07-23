package dev.tokenlogin.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

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
    private static final int W_BIND    = 40;
    private static final int GAP       =  2;

    // Proxy picker overlay
    private static final int PICKER_W      = 220;
    private static final int PICKER_ROW_H  = 18;
    private static final int PICKER_PAD    = 4;

    // ── State ─────────────────────────────────────────────────────────────────
    private final Screen                 parent;
    private final Screen                 loginTargetScreen; // screen to return to on Login (multiplayer)
    private final Consumer<AccountEntry> onLoginNow;

    private final List<AccountEntry> prismAccounts = new CopyOnWriteArrayList<>();

    private Button reloadButton;
    private Button backButton;

    private String statusText  = "";
    private int    statusColor = 0xFFAAAAAA;

    private double  scrollOffset   = 0;
    private boolean initialLoadDone = false;

    /** Non-null while the proxy picker overlay is open for this account. */
    private AccountEntry bindingAccount = null;

    private AccountEntry    selectedAccount = null;
    private EditBox notesField;

    // ── Search ────────────────────────────────────────────────────────────────
    private EditBox searchField;
    private String          searchQuery = "";

    // ── Constructor ───────────────────────────────────────────────────────────

    public PrismBrowserScreen(Screen parent, Screen loginTargetScreen, Consumer<AccountEntry> onLoginNow) {
        super(Component.literal("My Accounts"));
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
        backButton = Button.builder(
                Component.literal("< Back"),
                btn -> this.minecraft.setScreenAndShow(parent)
        ).bounds(4, 3, 50, 16).build();
        this.addRenderableWidget(backButton);

        reloadButton = Button.builder(
                Component.literal("Reload"),
                btn -> triggerReload(false)
        ).bounds(this.width - 58, 3, 54, 16).build();
        this.addRenderableWidget(reloadButton);

        this.addRenderableWidget(Button.builder(
                Component.literal("Manual Refresh"),
                btn -> this.minecraft.setScreenAndShow(
                        new ManualRefreshScreen(this))
        ).bounds(this.width - 150, 3, 88, 16).build());

        // ── Search field (centered in header) ─────────────────────────────────
        int searchW = Math.min(200, this.width - 180);
        int searchX = (this.width - searchW) / 2;
        searchField = new EditBox(
                this.font, searchX, 3, searchW, 16,
                Component.literal("Search"));
        searchField.setMaxLength(64);
        searchField.setHint(Component.literal("Search accounts..."));
        searchField.setResponder(this::onSearchChanged);
        this.addRenderableWidget(searchField);

        // ── Notes field (bottom bar) ──────────────────────────────────────────
        int notesY = this.height - FOOTER_H + 2;
        int notesW = this.width - 8;
        notesField = new EditBox(
                this.font, 4, notesY, notesW, 14,
                Component.literal("Notes"));
        notesField.setMaxLength(256);
        notesField.setHint(Component.literal("Click a row to edit notes..."));
        notesField.active = false;
        notesField.setResponder(this::onNotesChanged);
        this.addRenderableWidget(notesField);

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
            notesField.setValue("");
            notesField.setHint(Component.literal("Click a row to edit notes..."));
            return;
        }
        selectedAccount = acc;
        notesField.active = true;
        notesField.setValue(acc.notes != null ? acc.notes : "");
        notesField.setHint(Component.literal("Add notes for " + acc.username + "..."));
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private void triggerReload(boolean silent) {
        reloadButton.active = false;
        if (!silent) setStatus("Looking for Prism accounts...", 0xFFFFAA00);

        Thread t = new Thread(() -> {
            Path accountsJson = findPrismAccountsJson();
            List<AccountEntry> loaded = new ArrayList<>();

            if (accountsJson == null || !Files.exists(accountsJson)) {
                this.minecraft.execute(() -> {
                    prismAccounts.clear();
                    setStatus("PrismLauncher accounts.json not found", 0xFFFF5555);
                    reloadButton.active = true;
                    selectedAccount = null;
                    notesField.active = false;
                    notesField.setValue("");
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
            this.minecraft.execute(() -> {
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
                notesField.setValue("");
            });
        }, "TokenLogin-PrismReload");
        t.setDaemon(true);
        t.start();
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
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

        // Suppress hover on rows while the picker overlay is open
        int rowMouseX = bindingAccount != null ? -1 : mouseX;
        int rowMouseY = bindingAccount != null ? -1 : mouseY;

        for (int i = 0; i < accounts.size(); i++) {
            AccountEntry acc = accounts.get(i);
            int rowY = listTop + i * (ROW_H + ROW_PAD) - (int) scrollOffset;
            if (rowY + ROW_H < listTop || rowY > listBottom) continue;
            boolean hovered = bindingAccount == null
                    && mouseX >= rowX && mouseX < rowX + rowW
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            boolean selected = acc == selectedAccount;
            renderRow(ctx, acc, rowX, rowY, rowW, ROW_H, rowMouseX, rowMouseY, hovered, selected);
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
            Gfx.textShadow(ctx, this.font,
                    Component.literal(matchText),
                    this.width - this.font.width(matchText) - 6,
                    this.height - FOOTER_H + 20, matchColor);
        }

        if (!statusText.isEmpty()) {
            Gfx.textShadow(ctx, this.font,
                    Component.literal(statusText), 4, this.height - FOOTER_H + 20, statusColor);
        }

        for (var element : this.children()) {
            if (element instanceof Renderable drawable) {
                drawable.extractRenderState(ctx, mouseX, mouseY, delta);
            }
        }

        // Proxy picker overlay
        if (bindingAccount != null) {
            renderProxyPicker(ctx, mouseX, mouseY);
        }
    }

    private List<ProxyEntry> getAvailableProxies() {
        // Collect addresses already bound to other accounts
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (AccountEntry acc : prismAccounts) {
            if (acc != bindingAccount && acc.boundProxyAddress != null) {
                taken.add(acc.boundProxyAddress.trim().toLowerCase());
            }
        }
        List<ProxyEntry> result = new ArrayList<>();
        for (ProxyEntry p : ProxyConfig.getProxies()) {
            if (!taken.contains(p.key())) result.add(p);
        }
        return result;
    }

    private void renderProxyPicker(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
        List<ProxyEntry> proxies = getAvailableProxies();
        int rows    = Math.max(1, proxies.size());
        int pickerH = PICKER_PAD + rows * PICKER_ROW_H + PICKER_PAD;
        int px      = (this.width - PICKER_W) / 2;
        int py      = (this.height - pickerH) / 2;

        // Dim background
        ctx.fill(0, 0, this.width, this.height, 0x88000000);

        // Panel
        ctx.fill(px - 1, py - 1, px + PICKER_W + 1, py + pickerH + 1, 0xFF888888);
        ctx.fill(px, py, px + PICKER_W, py + pickerH, 0xFF1A1A1A);

        // Title
        String title = "Bind proxy: " + truncate(bindingAccount.username, 16);
        Gfx.textShadow(ctx, this.font, Component.literal(title),
                px + PICKER_PAD, py - 11, 0xFFAAAAAA);

        if (proxies.isEmpty()) {
            Gfx.textShadow(ctx, this.font, Component.literal("No proxies available"),
                    px + PICKER_PAD, py + PICKER_PAD, 0xFF888888);
            return;
        }

        int ry = py + PICKER_PAD;
        for (ProxyEntry proxy : proxies) {
            boolean hover = mouseX >= px && mouseX < px + PICKER_W
                         && mouseY >= ry && mouseY < ry + PICKER_ROW_H;
            ctx.fill(px, ry, px + PICKER_W, ry + PICKER_ROW_H, hover ? 0xFF2A2A2A : 0xFF1A1A1A);
            String label = proxy.name.isBlank() ? proxy.address : proxy.name + "  " + proxy.address;
            Gfx.textShadow(ctx, this.font,
                    Component.literal(truncate(label, 28)), px + PICKER_PAD, ry + (PICKER_ROW_H - 8) / 2, 0xFFCCCCCC);
            ry += PICKER_ROW_H;
        }
    }

    private void renderRow(GuiGraphicsExtractor ctx, AccountEntry acc,
                           int x, int y, int w, int h,
                           int mouseX, int mouseY, boolean hovered, boolean selected) {
        if (selected)     ctx.fill(x, y, x + w, y + h, 0x33FFFF55);
        else if (hovered) ctx.fill(x, y, x + w, y + h, 0x22FFFFFF);
        ctx.fill(x, y + h - 1, x + w, y + h, 0x44FFFFFF);

        int right = x + w - 2;
        int by    = y + (h - BH) / 2;

        int dx  = right           - W_DEL;
        int px  = dx    - GAP - W_COPY;
        int lx  = px    - GAP - W_LOGIN;
        int rx  = lx    - GAP - W_REFRESH;
        int bndx = rx   - GAP - W_BIND;

        boolean canRefresh = acc.hasRefreshCapability()
                && acc.refreshState != AccountEntry.RefreshState.REFRESHING;
        boolean hasToken   = !acc.minecraftToken.isBlank();
        String  refreshLbl = acc.refreshState == AccountEntry.RefreshState.REFRESHING
                ? "..." : "Refresh";

        // Bind button — highlighted cyan if a proxy is already bound
        boolean hasBound = acc.boundProxyAddress != null && !acc.boundProxyAddress.isBlank();
        int bindBg = hasBound ? 0xFF005577 : 0xFF333333;
        drawBtnColored(ctx, mouseX, mouseY, bndx, by, W_BIND, BH, "Bind", true, hasBound ? 0xFF55FFFF : 0xFFFFFFFF, bindBg);

        drawBtn(ctx, mouseX, mouseY, rx,  by, W_REFRESH, BH, refreshLbl, canRefresh);
        drawBtn(ctx, mouseX, mouseY, lx,  by, W_LOGIN,   BH, "Login",    hasToken);
        drawBtn(ctx, mouseX, mouseY, px,  by, W_COPY,    BH, "Copy",     hasToken);
        drawBtn(ctx, mouseX, mouseY, dx,  by, W_DEL,     BH, "X",        true);

        int lY1 = y + 4;
        int lY2 = y + 15;

        // Badge
        String badge = "[" + acc.badge() + "]";
        int    bc    = acc.badgeColor();
        Gfx.textShadow(ctx, this.font,
                Component.literal(badge).withStyle(s -> s.withColor(bc)), x + 2, lY1, bc);
        int bw = this.font.width(badge) + 4;

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
                    Gfx.textShadow(ctx, this.font,
                            Component.literal(prefix).withStyle(s -> s.withColor(nc)), nameX, lY1, nc);
                    nameX += this.font.width(prefix);
                }
                String match = name.substring(matchIdx, Math.min(matchIdx + searchQuery.length(), name.length()));
                Gfx.textShadow(ctx, this.font,
                        Component.literal(match).withStyle(s -> s.withColor(0xFFFF55)), nameX, lY1, 0xFFFFFF55);
                nameX += this.font.width(match);
                String suffix = name.substring(Math.min(matchIdx + searchQuery.length(), name.length()));
                if (!suffix.isEmpty()) {
                    Gfx.textShadow(ctx, this.font,
                            Component.literal(suffix).withStyle(s -> s.withColor(nc)), nameX, lY1, nc);
                }
            } else {
                Gfx.textShadow(ctx, this.font,
                        Component.literal(name).withStyle(s -> s.withColor(nc)), x + 2 + bw, lY1, nc);
            }
        } else {
            Gfx.textShadow(ctx, this.font,
                    Component.literal(name).withStyle(s -> s.withColor(nc)), x + 2 + bw, lY1, nc);
        }

        int nameEnd = x + 2 + bw + this.font.width(name) + 6;

        // Notes
        if (acc.notes != null && !acc.notes.isBlank()) {
            int maxNotesW = rx - nameEnd - 8;
            if (maxNotesW > 20) {
                String noteStr = acc.notes;
                while (this.font.width(noteStr) > maxNotesW && noteStr.length() > 1) {
                    noteStr = noteStr.substring(0, noteStr.length() - 1);
                }
                if (noteStr.length() < acc.notes.length()) noteStr += "..";
                Gfx.textShadow(ctx, this.font,
                        Component.literal(noteStr).withStyle(s -> s.withColor(0x999999)),
                        nameEnd, lY1, 0xFF999999);
            }
        }

        // Line 2: JWT timer | refresh status | proxy binding
        int tx = x + 2;
        String jwtStr = jwtString(acc);
        int    jwtCol = jwtColor(acc);
        Gfx.textShadow(ctx, this.font, Component.literal(jwtStr), tx, lY2, jwtCol);
        tx += this.font.width(jwtStr) + 8;

        if (tx < bndx - 30) {
            Gfx.textShadow(ctx, this.font, Component.literal("|"), tx, lY2, 0xFF444444);
            tx += this.font.width("| ") + 2;
        }
        if (tx < bndx - 4) {
            Gfx.textShadow(ctx, this.font,
                    Component.literal(refreshString(acc)), tx, lY2, refreshColor(acc));
            tx += this.font.width(refreshString(acc)) + 8;
        }

        // Proxy binding indicator
        if (acc.boundProxyAddress != null && !acc.boundProxyAddress.isBlank() && tx < bndx - 4) {
            Gfx.textShadow(ctx, this.font, Component.literal("|"), tx, lY2, 0xFF444444);
            tx += this.font.width("| ") + 2;
            String proxyLabel = "→ " + truncate(acc.boundProxyAddress, 24);
            Gfx.textShadow(ctx, this.font,
                    Component.literal(proxyLabel).withStyle(s -> s.withColor(0xFF55FFFF)), tx, lY2, 0xFF55FFFF);
        }
    }

    private void drawBtn(GuiGraphicsExtractor ctx, int mouseX, int mouseY,
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
        int tw = this.font.width(label);
        Gfx.textShadow(ctx, this.font, Component.literal(label),
                bx + (bw - tw) / 2, by + (bh - 8) / 2, fg);
    }

    private void drawBtnColored(GuiGraphicsExtractor ctx, int mouseX, int mouseY,
                                 int bx, int by, int bw, int bh,
                                 String label, boolean active, int fg, int bgOverride) {
        boolean over = active
                && mouseX >= bx && mouseX < bx + bw
                && mouseY >= by && mouseY < by + bh;
        int border = over ? 0xFFAAAAAA : 0xFF888888;
        int bg     = over ? 0xFF337799 : bgOverride;
        ctx.fill(bx, by, bx + bw, by + bh, border);
        ctx.fill(bx + 1, by + 1, bx + bw - 1, by + bh - 1, bg);
        int tw = this.font.width(label);
        Gfx.textShadow(ctx, this.font, Component.literal(label),
                bx + (bw - tw) / 2, by + (bh - 8) / 2, fg);
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    private void prism$handleClick(double mx, double my, int button) {
        if (button != 0) return;

        // ── Proxy picker overlay ───────────────────────────────────────────────
        if (bindingAccount != null) {
            List<ProxyEntry> proxies = getAvailableProxies();
            int rows    = proxies.size();
            int pickerH = PICKER_PAD + rows * PICKER_ROW_H + PICKER_PAD;
            int px      = (this.width  - PICKER_W) / 2;
            int py      = (this.height - pickerH)  / 2;

            if (mx >= px && mx < px + PICKER_W && my >= py && my < py + pickerH) {
                int ry = py + PICKER_PAD;
                for (ProxyEntry proxy : proxies) {
                    if (my >= ry && my < ry + PICKER_ROW_H) {
                        bindingAccount.boundProxyAddress = proxy.address;
                        AccountStorage.saveProxyBinding(bindingAccount);
                        setStatus("Bound " + bindingAccount.username + " → " + proxy.address, 0xFF55FFFF);
                        bindingAccount = null;
                        return;
                    }
                    ry += PICKER_ROW_H;
                }
            } else {
                bindingAccount = null; // clicked outside, cancel
            }
            return;
        }

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

            int dx   = right          - W_DEL;
            int cpx  = dx   - GAP - W_COPY;
            int lx   = cpx  - GAP - W_LOGIN;
            int rx   = lx   - GAP - W_REFRESH;
            int bndx = rx   - GAP - W_BIND;

            if (hit(dx,   W_DEL,     mx, my, by))                                    { doDelete(acc);      return; }
            if (hit(cpx,  W_COPY,    mx, my, by) && !acc.minecraftToken.isBlank())    { doCopy(acc);        return; }
            if (hit(lx,   W_LOGIN,   mx, my, by) && !acc.minecraftToken.isBlank())    { doLoginNow(acc);    return; }
            if (hit(rx,   W_REFRESH, mx, my, by) && acc.hasRefreshCapability()
                    && acc.refreshState != AccountEntry.RefreshState.REFRESHING)       { doRefresh(acc);     return; }
            if (hit(bndx, W_BIND,    mx, my, by))                                    { openBindPicker(acc); return; }

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
                this.minecraft.execute(() -> {
                    acc.refreshState = AccountEntry.RefreshState.SUCCESS;
                    setStatus("Refreshed: " + acc.username, 0xFF55FF55);
                });
            } catch (Exception e) {
                TokenLoginClient.LOGGER.warn("Refresh failed [{}]: {}", acc.username, e.getMessage());
                this.minecraft.execute(() -> {
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
        this.minecraft.setScreenAndShow(loginTargetScreen);
        onLoginNow.accept(acc);
    }

    private void doCopy(AccountEntry acc) {
        this.minecraft.keyboardHandler.setClipboard(acc.minecraftToken);
        setStatus("Token copied — " + acc.username, 0xFF55FF55);
    }

    private void openBindPicker(AccountEntry acc) {
        if (acc.boundProxyAddress != null && !acc.boundProxyAddress.isBlank()) {
            // Already bound — clicking again unbinds directly
            acc.boundProxyAddress = null;
            AccountStorage.saveProxyBinding(acc);
            setStatus("Proxy unbound from " + acc.username, 0xFFAAAAAA);
        } else {
            bindingAccount = acc;
        }
    }

    private void doDelete(AccountEntry acc) {
        String name = acc.username;
        if (selectedAccount == acc) {
            selectedAccount   = null;
            notesField.active = false;
            notesField.setValue("");
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
