package dev.tokenlogin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Full-screen proxy browser.
 *
 * Each row shows:
 *   Name    address    user    Protocol    Last connected    [Connect] [Edit] [X]
 *
 * Bottom bar: fields for adding/editing proxies.
 * Active proxy highlighted green.
 */
@Environment(EnvType.CLIENT)
public class ProxyBrowserScreen extends Screen {

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final int HEADER_H  = 22;
    private static final int FOOTER_H  = 52;
    private static final int ROW_H     = 28;
    private static final int ROW_PAD   =  2;

    // Button geometry
    private static final int BH        = 16;
    private static final int W_DEL     = 16;
    private static final int W_CONNECT = 52;
    private static final int W_EDIT    = 32;
    private static final int GAP       =  2;

    // ── State ─────────────────────────────────────────────────────────────────
    private final Screen           parent;
    private final Consumer<ProxyEntry> onSelect;       // row body click = populate fields
    private final Consumer<ProxyEntry> onConnectNow;   // Connect button = populate + auto-connect

    private Button backButton;
    private Button addNewButton;

    // Bottom bar — edit/add fields
    private EditBox     addressField;
    private EditBox     userField;
    private PasswordFieldWidget passField;
    private EditBox     nameField;
    private Button        saveButton;

    private String statusText  = "";
    private int    statusColor = 0xFFAAAAAA;

    private double  scrollOffset = 0;
    private boolean loaded       = false;

    /** Entry currently being edited in the bottom bar, or null for "add new" mode. */
    private ProxyEntry editingEntry = null;

    /** Entry pending delete confirmation, or null if no confirmation is showing. */
    private ProxyEntry pendingDelete = null;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ProxyBrowserScreen(Screen parent, Consumer<ProxyEntry> onSelect, Consumer<ProxyEntry> onConnectNow) {
        super(Component.literal("Proxy Browser"));
        this.parent       = parent;
        this.onSelect     = onSelect;
        this.onConnectNow = onConnectNow;
    }

    // ── Screen lifecycle ──────────────────────────────────────────────────────

    @Override
    protected void init() {
        backButton = Button.builder(
                Component.literal("< Back"),
                btn -> this.minecraft.setScreenAndShow(parent)
        ).bounds(4, 3, 50, 16).build();
        this.addRenderableWidget(backButton);

        addNewButton = Button.builder(
                Component.literal("Add New"),
                btn -> startAddNew()
        ).bounds(this.width - 62, 3, 58, 16).build();
        this.addRenderableWidget(addNewButton);

        // ── Bottom bar fields ─────────────────────────────────────────────────
        int h     = 14;
        int gap   = 2;
        int row1Y = this.height - FOOTER_H + 2;
        int row2Y = row1Y + h + gap;

        int nameW    = 100;
        int addrW    = 140;
        int halfW    = 80;
        int saveBtnW = 44;

        nameField = new EditBox(
                this.font, 4, row1Y, nameW, h, Component.literal("Name"));
        nameField.setMaxLength(64);
        nameField.setHint(Component.literal("Label..."));
        this.addRenderableWidget(nameField);

        addressField = new EditBox(
                this.font, 4 + nameW + gap, row1Y, addrW, h, Component.literal("Address"));
        addressField.setMaxLength(256);
        addressField.setHint(Component.literal("ip:port"));
        this.addRenderableWidget(addressField);

        userField = new EditBox(
                this.font, 4 + nameW + gap + addrW + gap, row1Y, halfW, h,
                Component.literal("User"));
        userField.setMaxLength(256);
        userField.setHint(Component.literal("User"));
        this.addRenderableWidget(userField);

        passField = new PasswordFieldWidget(
                this.font, 4 + nameW + gap + addrW + gap + halfW + gap, row1Y, halfW, h,
                Component.literal("Pass"));
        passField.setMaxLength(256);
        passField.setHint(Component.literal("Pass"));
        this.addRenderableWidget(passField);

        saveButton = Button.builder(
                Component.literal("Save"),
                btn -> handleSave()
        ).bounds(4 + nameW + gap + addrW + gap + halfW + gap + halfW + gap, row1Y, saveBtnW, h).build();
        this.addRenderableWidget(saveButton);

        if (!loaded) {
            loaded = true;
            ProxyConfig.load();
            int n = ProxyConfig.getProxies().size();
            setStatus(n == 0
                    ? "No proxies saved — add one below"
                    : n + " prox" + (n == 1 ? "y" : "ies") + " loaded",
                    n == 0 ? 0xFF888888 : 0xFF55FF55);
        }

        ScreenMouseEvents.beforeMouseClick(this).register((screen, click) ->
                tokenlogin$handleClick(click.x(), click.y(), click.button()));
    }

    // ── Bottom bar actions ────────────────────────────────────────────────────

    private void startAddNew() {
        editingEntry = null;
        nameField.setValue("");
        addressField.setValue("");
        userField.setValue("");
        passField.setValue("");
        nameField.setFocused(true);
        setStatus("Fill in details and click Save", 0xFFFFAA00);
    }

    private void startEdit(ProxyEntry entry) {
        editingEntry = entry;
        nameField.setValue(entry.name);
        addressField.setValue(entry.address);
        userField.setValue(entry.username);
        passField.setValue(entry.password);
        nameField.setFocused(true);
        setStatus("Editing: " + (entry.name.isEmpty() ? entry.address : entry.name), 0xFFFFAA00);
    }

    private void handleSave() {
        String addr = addressField.getValue().trim();
        if (addr.isEmpty()) {
            setStatus("Address is required", 0xFFFF5555);
            return;
        }

        // Validate ip:port format
        String[] parts = addr.split(":");
        if (parts.length != 2) {
            setStatus("Invalid address — use ip:port", 0xFFFF5555);
            return;
        }
        try {
            int port = Integer.parseInt(parts[1]);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            setStatus("Invalid port number", 0xFFFF5555);
            return;
        }

        if (editingEntry != null) {
            // Update existing
            editingEntry.name     = nameField.getValue().trim();
            editingEntry.address  = addr;
            editingEntry.username = userField.getValue().trim();
            editingEntry.password = passField.getValue().trim();
            ProxyConfig.updateProxy(editingEntry);
            setStatus("Updated: " + (editingEntry.name.isEmpty() ? addr : editingEntry.name), 0xFF55FF55);
        } else {
            // Add new
            ProxyEntry entry = new ProxyEntry();
            entry.name     = nameField.getValue().trim();
            entry.address  = addr;
            entry.username = userField.getValue().trim();
            entry.password = passField.getValue().trim();
            ProxyConfig.addProxy(entry);
            setStatus("Added: " + (entry.name.isEmpty() ? addr : entry.name), 0xFF55FF55);
        }

        editingEntry = null;
        nameField.setValue("");
        addressField.setValue("");
        userField.setValue("");
        passField.setValue("");
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xC0101010);

        // Header
        ctx.fill(0, 0, this.width, HEADER_H, 0xDD000000);
        Gfx.centeredShadow(ctx, this.font,
                Component.literal("Proxy Browser"), this.width / 2, 7, 0xFF55FFFF);

        // Row list
        List<ProxyEntry> list = ProxyConfig.getProxies();
        int listTop    = HEADER_H;
        int listBottom = this.height - FOOTER_H;
        int listH      = listBottom - listTop;
        int totalH     = list.size() * (ROW_H + ROW_PAD);

        double maxScroll = Math.max(0, totalH - listH);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        ctx.enableScissor(0, listTop, this.width, listBottom);

        int rowX = 4;
        int rowW = this.width - 8;

        String activeKey = ProxyConfig.getActiveKey();

        for (int i = 0; i < list.size(); i++) {
            ProxyEntry entry = list.get(i);
            int rowY = listTop + i * (ROW_H + ROW_PAD) - (int) scrollOffset;
            if (rowY + ROW_H < listTop || rowY > listBottom) continue;

            boolean hovered = mouseX >= rowX && mouseX < rowX + rowW
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            boolean active  = entry.key().equals(activeKey) && ProxyManager.isEnabled();
            boolean editing = entry == editingEntry;

            renderRow(ctx, entry, rowX, rowY, rowW, ROW_H, mouseX, mouseY, hovered, active, editing);
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

        // Status text
        if (!statusText.isEmpty()) {
            Gfx.textShadow(ctx, this.font,
                    Component.literal(statusText), 4, this.height - FOOTER_H + 38, statusColor);
        }

        // Draw all widgets
        for (var element : this.children()) {
            if (element instanceof Renderable drawable) {
                drawable.extractRenderState(ctx, mouseX, mouseY, delta);
            }
        }

        // Confirmation dialog
        if (pendingDelete != null) {
            renderConfirmDialog(ctx, mouseX, mouseY);
        }
    }

    private static final int CONFIRM_W = 220;
    private static final int CONFIRM_H = 60;

    private void renderConfirmDialog(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
        ctx.fill(0, 0, this.width, this.height, 0x88000000);

        int cx = (this.width - CONFIRM_W) / 2;
        int cy = (this.height - CONFIRM_H) / 2;

        ctx.fill(cx - 1, cy - 1, cx + CONFIRM_W + 1, cy + CONFIRM_H + 1, 0xFF888888);
        ctx.fill(cx, cy, cx + CONFIRM_W, cy + CONFIRM_H, 0xFF1A1A1A);

        String label = pendingDelete.name.isEmpty() ? pendingDelete.address : pendingDelete.name;
        Gfx.centeredShadow(ctx, this.font,
                Component.literal("Delete " + truncate(label, 20) + "?"),
                this.width / 2, cy + 8, 0xFFFFAAAA);

        int btnW = 60;
        int btnY = cy + CONFIRM_H - 24;
        int yesX = cx + CONFIRM_W / 2 - btnW - 4;
        int noX  = cx + CONFIRM_W / 2 + 4;

        drawBtn(ctx, mouseX, mouseY, yesX, btnY, btnW, BH, "Yes", true);
        drawBtn(ctx, mouseX, mouseY, noX,  btnY, btnW, BH, "No",  true);
    }

    private void renderRow(GuiGraphicsExtractor ctx, ProxyEntry entry,
                           int x, int y, int w, int h,
                           int mouseX, int mouseY,
                           boolean hovered, boolean active, boolean editing) {
        // Row background
        if (active)       ctx.fill(x, y, x + w, y + h, 0x3355FF55);
        else if (editing) ctx.fill(x, y, x + w, y + h, 0x33FFFF55);
        else if (hovered) ctx.fill(x, y, x + w, y + h, 0x22FFFFFF);
        ctx.fill(x, y + h - 1, x + w, y + h, 0x44FFFFFF);

        int right = x + w - 2;
        int by    = y + (h - BH) / 2;

        // Buttons (right-aligned)
        int dx = right          - W_DEL;
        int ex = dx   - GAP - W_EDIT;
        int cx = ex   - GAP - W_CONNECT;

        boolean canConnect = entry.connectState != ProxyEntry.ConnectState.CONNECTING;
        String connectLbl = entry.connectState == ProxyEntry.ConnectState.CONNECTING
                ? "..." : "Connect";

        drawBtn(ctx, mouseX, mouseY, cx, by, W_CONNECT, BH, connectLbl, canConnect);
        drawBtn(ctx, mouseX, mouseY, ex, by, W_EDIT,    BH, "Edit",     true);
        drawBtn(ctx, mouseX, mouseY, dx, by, W_DEL,     BH, "X",        true);

        int lY1 = y + 4;
        int lY2 = y + 15;

        // Line 1: active dot + name + address + user
        int tx = x + 2;

        if (active) {
            Gfx.textShadow(ctx, this.font,
                    Component.literal("\u25C9 ").withStyle(s -> s.withColor(0x55FF55)), tx, lY1, 0xFF55FF55);
            tx += this.font.width("\u25C9 ");
        }

        // Name (or address if no name)
        String displayName = entry.name.isEmpty() ? entry.address : entry.name;
        if (displayName.length() > 24) displayName = displayName.substring(0, 22) + "..";
        final int nameColor;
        if (entry.connectState == ProxyEntry.ConnectState.CONNECTING)    nameColor = 0xFFFFAA00;
        else if (entry.connectState == ProxyEntry.ConnectState.FAILED)   nameColor = 0xFFFF7777;
        else if (active)                                                 nameColor = 0xFF55FF55;
        else                                                             nameColor = 0xFFFFFFFF;

        Gfx.textShadow(ctx, this.font,
                Component.literal(displayName).withStyle(s -> s.withColor(nameColor)), tx, lY1, nameColor);
        tx += this.font.width(displayName) + 8;

        // Address (if name is set, show address separately)
        if (!entry.name.isEmpty() && tx < cx - 60) {
            String addrStr = entry.address;
            if (addrStr.length() > 21) addrStr = addrStr.substring(0, 19) + "..";
            Gfx.textShadow(ctx, this.font,
                    Component.literal(addrStr).withStyle(s -> s.withColor(0xAAAAAA)), tx, lY1, 0xFFAAAAAA);
            tx += this.font.width(addrStr) + 8;
        }

        // Username indicator
        if (!entry.username.isEmpty() && tx < cx - 40) {
            Gfx.textShadow(ctx, this.font,
                    Component.literal("@" + entry.username).withStyle(s -> s.withColor(0x8888FF)),
                    tx, lY1, 0xFF8888FF);
        }

        // Line 2: protocol + last connected + error
        tx = x + 2;
        if (active) tx += this.font.width("\u25C9 ");

        // Protocol badge
        if (entry.lastType != ProxyManager.ProxyType.NONE) {
            String proto = entry.lastType.displayName();
            int protoColor = 0xFF55AAFF;
            Gfx.textShadow(ctx, this.font,
                    Component.literal(proto).withStyle(s -> s.withColor(protoColor)), tx, lY2, protoColor);
            tx += this.font.width(proto) + 6;
        }

        // Last connected
        if (entry.lastConnected > 0 && tx < cx - 40) {
            long ago = System.currentTimeMillis() / 1000L - entry.lastConnected;
            String agoStr = ago < 0 ? "just now" : fmt(ago) + " ago";
            Gfx.textShadow(ctx, this.font,
                    Component.literal(agoStr).withStyle(s -> s.withColor(0x888888)), tx, lY2, 0xFF888888);
            tx += this.font.width(agoStr) + 6;
        } else if (entry.lastConnected == 0 && entry.connectState == ProxyEntry.ConnectState.IDLE) {
            Gfx.textShadow(ctx, this.font,
                    Component.literal("Never tested").withStyle(s -> s.withColor(0x666666)), tx, lY2, 0xFF666666);
            tx += this.font.width("Never tested") + 6;
        }

        // Connect state / error
        if (entry.connectState == ProxyEntry.ConnectState.CONNECTING) {
            Gfx.textShadow(ctx, this.font,
                    Component.literal("Testing...").withStyle(s -> s.withColor(0xFFAA00)), tx, lY2, 0xFFFFAA00);
        } else if (entry.connectState == ProxyEntry.ConnectState.FAILED && !entry.connectError.isEmpty()) {
            String err = truncate(entry.connectError, 30);
            Gfx.textShadow(ctx, this.font,
                    Component.literal("X " + err).withStyle(s -> s.withColor(0xFF5555)), tx, lY2, 0xFFFF5555);
        } else if (entry.connectState == ProxyEntry.ConnectState.SUCCESS) {
            Gfx.textShadow(ctx, this.font,
                    Component.literal("Connected").withStyle(s -> s.withColor(0x55FF55)), tx, lY2, 0xFF55FF55);
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

    // ── Input ─────────────────────────────────────────────────────────────────

    private void tokenlogin$handleClick(double mx, double my, int button) {
        if (button != 0) return;

        // Confirmation dialog intercept
        if (pendingDelete != null) {
            int cx = (this.width - CONFIRM_W) / 2;
            int cy = (this.height - CONFIRM_H) / 2;
            int btnW = 60;
            int btnY = cy + CONFIRM_H - 24;
            int yesX = cx + CONFIRM_W / 2 - btnW - 4;
            int noX  = cx + CONFIRM_W / 2 + 4;

            if (mx >= yesX && mx < yesX + btnW && my >= btnY && my < btnY + BH) {
                confirmDelete();
            }
            // Any click dismisses the dialog
            pendingDelete = null;
            return;
        }

        if (my >= this.height - FOOTER_H) return;  // footer handles its own widgets

        if (backButton != null && hitWidget(backButton, mx, my)) {
            this.minecraft.setScreenAndShow(parent);
            return;
        }
        if (addNewButton != null && hitWidget(addNewButton, mx, my)) {
            startAddNew();
            return;
        }

        List<ProxyEntry> list = ProxyConfig.getProxies();
        int listTop = HEADER_H;
        int rowW    = this.width - 8;

        for (int i = 0; i < list.size(); i++) {
            ProxyEntry entry = list.get(i);
            int rowY = listTop + i * (ROW_H + ROW_PAD) - (int) scrollOffset;
            if (my < rowY || my >= rowY + ROW_H) continue;

            int x     = 4;
            int right = x + rowW - 2;
            int by    = rowY + (ROW_H - BH) / 2;

            int dx = right          - W_DEL;
            int ex = dx   - GAP - W_EDIT;
            int cx = ex   - GAP - W_CONNECT;

            if (hit(dx, W_DEL,     mx, my, by))                                         { doDelete(entry);  return; }
            if (hit(ex, W_EDIT,    mx, my, by))                                         { startEdit(entry); return; }
            if (hit(cx, W_CONNECT, mx, my, by))                                         { doConnect(entry); return; }

            // Click on row body = select for multiplayer & go back
            doSelectAndBack(entry);
            return;
        }
    }

    private static boolean hitWidget(Button w, double mx, double my) {
        return mx >= w.getX() && mx < w.getX() + w.getWidth()
                && my >= w.getY() && my < w.getY() + w.getHeight();
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

    private void doConnect(ProxyEntry entry) {
        // Go back to multiplayer screen and trigger connect from there
        this.minecraft.setScreenAndShow(parent);
        onConnectNow.accept(entry);
    }

    private void doSelectAndBack(ProxyEntry entry) {
        if (onSelect != null) onSelect.accept(entry);
        this.minecraft.setScreenAndShow(parent);
    }

    private void doDelete(ProxyEntry entry) {
        pendingDelete = entry;
    }

    private void confirmDelete() {
        if (pendingDelete == null) return;
        String label = pendingDelete.name.isEmpty() ? pendingDelete.address : pendingDelete.name;
        if (editingEntry == pendingDelete) {
            editingEntry = null;
            nameField.setValue("");
            addressField.setValue("");
            userField.setValue("");
            passField.setValue("");
        }
        ProxyConfig.removeProxy(pendingDelete);
        setStatus(label + " removed. " + ProxyConfig.getProxies().size() + " remaining.", 0xFFAAAAAA);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
