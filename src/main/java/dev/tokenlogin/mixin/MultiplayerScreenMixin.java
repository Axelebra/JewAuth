package dev.tokenlogin.mixin;

import dev.tokenlogin.client.AccountEntry;
import dev.tokenlogin.client.AccountManager;
import dev.tokenlogin.client.AccountStorage;
import dev.tokenlogin.client.AutoReconnect;
import dev.tokenlogin.client.NameChanger;
import dev.tokenlogin.client.NickHider;
import dev.tokenlogin.client.ProxyConfig;
import dev.tokenlogin.client.ProxyEntry;
import dev.tokenlogin.client.ProxyManager;
import dev.tokenlogin.client.ProxyBrowserScreen;
import dev.tokenlogin.client.PasswordFieldWidget;
import dev.tokenlogin.client.SelfBan;
import dev.tokenlogin.client.TokenBrowserScreen;
import dev.tokenlogin.client.TokenLoginClient;
import dev.tokenlogin.client.TokenManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.User;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractStringWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.UUID;

@Environment(EnvType.CLIENT)
@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {

    // =====================================================================
    // Token login widgets (bottom-left)
    // =====================================================================

    @Unique private EditBox tokenlogin$tokenField;
    @Unique private Button  tokenlogin$loginButton;
    @Unique private Button  tokenlogin$restoreButton;
    @Unique private Button  tokenlogin$browseButton;

    @Unique private static long   tokenlogin$expiryEpoch  = 0L;
    @Unique private static String tokenlogin$errorMessage = "";

    @Unique private volatile boolean tokenlogin$loginInProgress = false;

    @Unique private static User  tokenlogin$originalSession      = null;

    // =====================================================================
    // Proxy widgets (top-right)
    // =====================================================================

    @Unique private EditBox             tokenlogin$proxyAddressField;
    @Unique private EditBox             tokenlogin$proxyUserField;
    @Unique private PasswordFieldWidget tokenlogin$proxyPassField;
    @Unique private Button              tokenlogin$proxyConnectButton;
    @Unique private Button              tokenlogin$proxyDisconnectButton;
    @Unique private Button              tokenlogin$proxyBrowseButton;

    @Unique private volatile boolean tokenlogin$proxyTestInProgress = false;

    // =====================================================================
    // IGN changer widgets (top-left)
    // =====================================================================

    @Unique private EditBox tokenlogin$nameField;
    @Unique private Button  tokenlogin$nameChangeButton;
    @Unique private Button  tokenlogin$nameModeButton;

    @Unique private static boolean tokenlogin$nameApiMode = false;

    @Unique private volatile boolean tokenlogin$nameChangeInProgress = false;

    // =====================================================================
    // Selfban + AutoReconnect toggles (bottom-right)
    // =====================================================================

    @Unique private Button tokenlogin$selfbanButton;
    @Unique private Button tokenlogin$autoRecButton;

    protected MultiplayerScreenMixin(Component title) { super(title); }

    // =====================================================================
    // Layout
    //
    // The vanilla screen owns the whole window: a 33px header band with the
    // title, the server list, and a 60px footer band holding the two rows of
    // vanilla buttons (centred, 308px wide).  Absolute-positioned widgets in
    // the corners therefore land *on top of* the vanilla buttons at anything
    // but a very wide window — and since vanilla's widgets are registered
    // first, they swallow every click, so ours look dead.
    //
    // Instead we reserve two bands from the layout itself (header grows by
    // tokenlogin$topBand, footer by tokenlogin$botBand) and pull the vanilla
    // content back to the edges, so the server list shrinks and our rows get
    // space nothing else draws in.  "Hide UI" gives the bands back.
    // =====================================================================

    @Shadow @Final private HeaderAndFooterLayout layout;

    @Unique private static final int TL_ROW_H    = 14;
    @Unique private static final int TL_GAP      = 2;
    @Unique private static final int TL_PAD      = 4;
    @Unique private static final int TL_TEXT_H   = 10;
    @Unique private static final int TL_CLUSTER_GAP = 8;

    // bottom band clusters
    @Unique private static final int TL_TOKEN_W   = 100;
    @Unique private static final int TL_LOGIN_W   = 40;
    @Unique private static final int TL_RESTORE_W = 46;
    @Unique private static final int TL_BROWSE_W  = 44;
    @Unique private static final int TL_AUTOREC_W = 72;
    @Unique private static final int TL_SELFBAN_W = 72;

    // top band clusters
    @Unique private static final int TL_NAME_W    = 110;
    @Unique private static final int TL_CHANGE_W  = 46;
    @Unique private static final int TL_MODE_W    = 32;
    @Unique private static final int TL_ADDR_W    = 110;
    @Unique private static final int TL_CONNECT_W = 48;
    @Unique private static final int TL_PBROWSE_W = 44;

    @Unique private static final int TL_TOGGLE_W  = 52;

    @Unique private static final int TL_VANILLA_HEADER = 33;
    @Unique private static final int TL_VANILLA_FOOTER = 60;

    @Unique private static boolean tokenlogin$uiHidden = false;

    @Unique private Button tokenlogin$toggleButton;

    @Unique private final java.util.List<AbstractWidget> tokenlogin$ourWidgets = new java.util.ArrayList<>();

    @Unique private int     tokenlogin$topBand;
    @Unique private int     tokenlogin$botBand;
    @Unique private boolean tokenlogin$stackTop;
    @Unique private boolean tokenlogin$stackBottom;

    // text anchors, filled in by tokenlogin$layoutWidgets
    @Unique private int tokenlogin$nameStatusY;
    @Unique private int tokenlogin$proxyStatusY;
    @Unique private int tokenlogin$infoY;

    // =====================================================================
    // init
    // =====================================================================

    @Inject(method = "init", at = @At("TAIL"))
    private void tokenlogin$onInit(CallbackInfo ci) {

        ProxyConfig.load();

        if (tokenlogin$originalSession == null && this.minecraft != null) {
            tokenlogin$originalSession = this.minecraft.getUser();
        }

        tokenlogin$ourWidgets.clear();

        int h = TL_ROW_H;

        // ── Token login row ─────────────────────────────────────────────────
        tokenlogin$tokenField = new EditBox(
                this.font, 0, 0, TL_TOKEN_W, h,
                Component.literal("Token"));
        tokenlogin$tokenField.setMaxLength(4096);
        tokenlogin$tokenField.setHint(Component.literal("Paste token..."));
        tokenlogin$add(tokenlogin$tokenField);

        tokenlogin$loginButton = Button.builder(
                Component.literal("Login"),
                btn -> tokenlogin$handleLogin()
        ).bounds(0, 0, TL_LOGIN_W, h).build();
        tokenlogin$add(tokenlogin$loginButton);

        tokenlogin$restoreButton = Button.builder(
                Component.literal("Restore"),
                btn -> tokenlogin$handleRestore()
        ).bounds(0, 0, TL_RESTORE_W, h).build();
        tokenlogin$restoreButton.active = tokenlogin$originalSession != null;
        tokenlogin$add(tokenlogin$restoreButton);

        tokenlogin$browseButton = Button.builder(
                Component.literal("Browse..."),
                btn -> tokenlogin$openBrowser()
        ).bounds(0, 0, TL_BROWSE_W, h).build();
        tokenlogin$add(tokenlogin$browseButton);

        // ── AutoRec + Selfban ───────────────────────────────────────────────
        tokenlogin$autoRecButton = Button.builder(
                tokenlogin$autoRecLabel(),
                btn -> { AutoReconnect.toggle(); btn.setMessage(tokenlogin$autoRecLabel()); }
        ).bounds(0, 0, TL_AUTOREC_W, h).build();
        tokenlogin$add(tokenlogin$autoRecButton);

        tokenlogin$selfbanButton = Button.builder(
                tokenlogin$selfbanLabel(),
                btn -> tokenlogin$handleSelfbanToggle()
        ).bounds(0, 0, TL_SELFBAN_W, h).build();
        tokenlogin$add(tokenlogin$selfbanButton);

        // ── Proxy ───────────────────────────────────────────────────────────
        int halfW = (TL_ADDR_W - TL_GAP) / 2;

        tokenlogin$proxyAddressField = new EditBox(
                this.font, 0, 0, TL_ADDR_W, h,
                Component.literal("Address"));
        tokenlogin$proxyAddressField.setMaxLength(256);
        tokenlogin$proxyAddressField.setHint(Component.literal("ip:port"));
        tokenlogin$proxyAddressField.setValue(ProxyConfig.getAddress());
        tokenlogin$add(tokenlogin$proxyAddressField);

        tokenlogin$proxyConnectButton = Button.builder(
                Component.literal("Connect"),
                btn -> tokenlogin$handleProxyConnect()
        ).bounds(0, 0, TL_CONNECT_W, h).build();
        tokenlogin$add(tokenlogin$proxyConnectButton);

        tokenlogin$proxyBrowseButton = Button.builder(
                Component.literal("Browse..."),
                btn -> tokenlogin$openProxyBrowser()
        ).bounds(0, 0, TL_PBROWSE_W, h).build();
        tokenlogin$add(tokenlogin$proxyBrowseButton);

        tokenlogin$proxyUserField = new EditBox(
                this.font, 0, 0, halfW, h,
                Component.literal("Username"));
        tokenlogin$proxyUserField.setMaxLength(256);
        tokenlogin$proxyUserField.setHint(Component.literal("User"));
        tokenlogin$proxyUserField.setValue(ProxyConfig.getUsername());
        tokenlogin$add(tokenlogin$proxyUserField);

        tokenlogin$proxyPassField = new PasswordFieldWidget(
                this.font, 0, 0, halfW, h,
                Component.literal("Password"));
        tokenlogin$proxyPassField.setMaxLength(256);
        tokenlogin$proxyPassField.setHint(Component.literal("Pass"));
        tokenlogin$proxyPassField.setValue(ProxyConfig.getPassword());
        tokenlogin$add(tokenlogin$proxyPassField);

        tokenlogin$proxyDisconnectButton = Button.builder(
                Component.literal("Off"),
                btn -> tokenlogin$handleProxyDisconnect()
        ).bounds(0, 0, TL_CONNECT_W + TL_GAP + TL_PBROWSE_W, h).build();
        tokenlogin$proxyDisconnectButton.active = ProxyManager.isEnabled();
        tokenlogin$add(tokenlogin$proxyDisconnectButton);

        // ── IGN changer ─────────────────────────────────────────────────────
        tokenlogin$nameField = new EditBox(
                this.font, 0, 0, TL_NAME_W, h,
                Component.literal("IGN"));
        tokenlogin$nameField.setMaxLength(16);
        tokenlogin$nameField.setHint(Component.literal("New name..."));
        tokenlogin$add(tokenlogin$nameField);

        tokenlogin$nameChangeButton = Button.builder(
                Component.literal("Change"),
                btn -> tokenlogin$handleNameChange()
        ).bounds(0, 0, TL_CHANGE_W, h).build();
        tokenlogin$add(tokenlogin$nameChangeButton);

        tokenlogin$nameModeButton = Button.builder(
                Component.literal(tokenlogin$nameApiMode ? "IGN" : "Hider"),
                btn -> tokenlogin$toggleNameMode()
        ).bounds(0, 0, TL_MODE_W, h).build();
        tokenlogin$add(tokenlogin$nameModeButton);

        // ── Show / hide the whole mod UI (always in the free top-left gap) ──
        tokenlogin$toggleButton = Button.builder(
                tokenlogin$toggleLabel(),
                btn -> {
                    tokenlogin$uiHidden = !tokenlogin$uiHidden;
                    btn.setMessage(tokenlogin$toggleLabel());
                    this.clearFocus();   // a hidden EditBox must not keep the keyboard
                    tokenlogin$layoutBands();
                }
        ).bounds(TL_PAD, (TL_VANILLA_HEADER - h) / 2, TL_TOGGLE_W, h).build();
        this.addRenderableWidget(tokenlogin$toggleButton);

        tokenlogin$layoutBands();
    }

    @Unique
    private void tokenlogin$add(AbstractWidget widget) {
        tokenlogin$ourWidgets.add(widget);
        this.addRenderableWidget(widget);
    }

    @Unique
    private static Component tokenlogin$toggleLabel() {
        return Component.literal(tokenlogin$uiHidden ? "Show UI" : "Hide UI");
    }

    // =====================================================================
    // Band reservation
    // =====================================================================

    /**
     * Grows the vanilla header/footer bands so our rows have room of their
     * own, then lays everything out.  Re-entrant: the recursive
     * repositionElements() lands back in tokenlogin$onReposition with the
     * band sizes already applied.
     */
    @Unique
    private void tokenlogin$layoutBands() {
        if (tokenlogin$tokenField == null) return;

        tokenlogin$measureBands();

        int wantHeader = TL_VANILLA_HEADER + (tokenlogin$uiHidden ? 0 : tokenlogin$topBand);
        int wantFooter = TL_VANILLA_FOOTER + (tokenlogin$uiHidden ? 0 : tokenlogin$botBand);

        if (this.layout.getHeaderHeight() != wantHeader || this.layout.getFooterHeight() != wantFooter) {
            this.layout.setHeaderHeight(wantHeader);
            this.layout.setFooterHeight(wantFooter);
            this.repositionElements();   // re-arranges vanilla, then calls us back
            return;
        }

        tokenlogin$pullVanillaToEdges();
        tokenlogin$layoutWidgets();
    }

    @Inject(method = "repositionElements", at = @At("TAIL"))
    private void tokenlogin$onReposition(CallbackInfo ci) {
        tokenlogin$layoutBands();
    }

    /** Picks band heights for the current window width. */
    @Unique
    private void tokenlogin$measureBands() {
        int nameCluster  = TL_NAME_W + TL_GAP + TL_CHANGE_W + TL_GAP + TL_MODE_W;
        int proxyCluster = TL_ADDR_W + TL_GAP + TL_CONNECT_W + TL_GAP + TL_PBROWSE_W;
        tokenlogin$stackTop =
                TL_PAD + nameCluster + TL_CLUSTER_GAP + proxyCluster + TL_PAD > this.width;

        int tokenCluster = TL_TOKEN_W + TL_GAP + TL_LOGIN_W + TL_GAP + TL_RESTORE_W + TL_GAP + TL_BROWSE_W;
        int toggleCluster = TL_AUTOREC_W + TL_GAP + TL_SELFBAN_W;
        tokenlogin$stackBottom =
                TL_PAD + tokenCluster + TL_CLUSTER_GAP + toggleCluster + TL_PAD > this.width;

        // top: [IGN | proxy row 1] [status | proxy row 2] [status text]
        tokenlogin$topBand = tokenlogin$stackTop
                ? 3 * (TL_ROW_H + TL_GAP) + 2 * (TL_TEXT_H + TL_GAP) + TL_GAP
                : 2 * (TL_ROW_H + TL_GAP) + TL_TEXT_H + TL_GAP;

        // bottom: [info text] [token row (| toggles)] (+ [toggles])
        tokenlogin$botBand = tokenlogin$stackBottom
                ? TL_TEXT_H + TL_GAP + 2 * (TL_ROW_H + TL_GAP) + TL_GAP
                : TL_TEXT_H + TL_GAP + TL_ROW_H + 2 * TL_GAP;
    }

    /**
     * HeaderAndFooterLayout centres each band's content, so growing a band
     * pushes the vanilla title/buttons towards the middle of the screen.
     * Shift them back by half the growth: the title keeps its vanilla spot
     * and the vanilla button block sits directly above our bottom band.
     */
    @Unique
    private void tokenlogin$pullVanillaToEdges() {
        int headerShift = (this.layout.getHeaderHeight() - TL_VANILLA_HEADER) / 2;
        int footerShift = (this.layout.getFooterHeight() - TL_VANILLA_FOOTER) / 2;
        int footerTop   = this.height - this.layout.getFooterHeight();

        for (GuiEventListener child : this.children()) {
            if (!(child instanceof AbstractWidget widget)) continue;
            if (widget == tokenlogin$toggleButton || tokenlogin$ourWidgets.contains(widget)) continue;

            if (widget.getY() >= footerTop) {
                widget.setY(widget.getY() - footerShift);
            } else if (widget instanceof AbstractStringWidget) {
                widget.setY(widget.getY() - headerShift);
            }
        }
    }

    // =====================================================================
    // Our own rows
    // =====================================================================

    @Unique
    private void tokenlogin$layoutWidgets() {
        // visible == false also stops AbstractWidget from taking clicks
        boolean show = !tokenlogin$uiHidden;
        for (AbstractWidget w : tokenlogin$ourWidgets) w.visible = show;
        if (!show) return;

        int h    = TL_ROW_H;
        int gap  = TL_GAP;
        int pad  = TL_PAD;

        // ── top band: starts right below the vanilla title row ──────────────
        int topY = TL_VANILLA_HEADER + gap;

        int nameX = pad;
        tokenlogin$nameField.setPosition(nameX, topY);
        tokenlogin$nameChangeButton.setPosition(nameX + TL_NAME_W + gap, topY);
        tokenlogin$nameModeButton.setPosition(nameX + TL_NAME_W + gap + TL_CHANGE_W + gap, topY);

        int proxyCluster = TL_ADDR_W + gap + TL_CONNECT_W + gap + TL_PBROWSE_W;
        int proxyX       = this.width - pad - proxyCluster;
        int proxyRow1    = tokenlogin$stackTop ? topY + h + gap + TL_TEXT_H + gap : topY;
        int proxyRow2    = proxyRow1 + h + gap;
        if (tokenlogin$stackTop) proxyX = pad;

        tokenlogin$proxyAddressField.setPosition(proxyX, proxyRow1);
        tokenlogin$proxyConnectButton.setPosition(proxyX + TL_ADDR_W + gap, proxyRow1);
        tokenlogin$proxyBrowseButton.setPosition(proxyX + TL_ADDR_W + gap + TL_CONNECT_W + gap, proxyRow1);

        int halfW = (TL_ADDR_W - gap) / 2;
        tokenlogin$proxyUserField.setPosition(proxyX, proxyRow2);
        tokenlogin$proxyPassField.setPosition(proxyX + halfW + gap, proxyRow2);
        tokenlogin$proxyDisconnectButton.setPosition(proxyX + TL_ADDR_W + gap, proxyRow2);

        tokenlogin$nameStatusY  = topY + h + gap;
        tokenlogin$proxyStatusY = proxyRow2 + h + gap;

        // ── bottom band: below the vanilla button block ─────────────────────
        int toggleRowY = this.height - gap - h;
        int tokenRowY  = tokenlogin$stackBottom ? toggleRowY - h - gap : toggleRowY;
        tokenlogin$infoY = tokenRowY - gap - (TL_TEXT_H - 1);

        tokenlogin$tokenField.setPosition(pad, tokenRowY);
        tokenlogin$loginButton.setPosition(pad + TL_TOKEN_W + gap, tokenRowY);
        tokenlogin$restoreButton.setPosition(pad + TL_TOKEN_W + gap + TL_LOGIN_W + gap, tokenRowY);
        tokenlogin$browseButton.setPosition(
                pad + TL_TOKEN_W + gap + TL_LOGIN_W + gap + TL_RESTORE_W + gap, tokenRowY);

        int selfbanX = this.width - pad - TL_SELFBAN_W;
        tokenlogin$autoRecButton.setPosition(selfbanX - TL_AUTOREC_W - gap, toggleRowY);
        tokenlogin$selfbanButton.setPosition(selfbanX, toggleRowY);

        // ── toggle button: free gap left of the centred vanilla title ───────
        tokenlogin$toggleButton.setPosition(pad, (TL_VANILLA_HEADER - h) / 2);

        tokenlogin$restoreButton.active        = tokenlogin$originalSession != null;
        tokenlogin$proxyDisconnectButton.active = ProxyManager.isEnabled();
    }

    // =====================================================================
    // render (26.x: render-state extraction)
    // =====================================================================

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float deltaTicks) {
        // Keep selfban button label in sync — disable() can be called from the tick thread
        if (tokenlogin$selfbanButton != null) {
            tokenlogin$selfbanButton.setMessage(tokenlogin$selfbanLabel());
        }
        if (!tokenlogin$uiHidden) tokenlogin$renderBands(extractor);
        super.extractRenderState(extractor, mouseX, mouseY, deltaTicks);
    }

    @Unique
    private void tokenlogin$renderBands(GuiGraphicsExtractor context) {
        int pad = TL_PAD;

        // Band backgrounds, so the text on them stays readable over the dirt/panorama
        int topTop    = TL_VANILLA_HEADER;
        int topBottom = topTop + tokenlogin$topBand;
        context.fill(0, topTop, this.width, topBottom, 0x99000000);
        context.fill(0, this.height - tokenlogin$botBand, this.width, this.height, 0x99000000);

        // ── top band text: IGN / nick status (left), proxy status (right) ───
        String nameMsg = tokenlogin$nameApiMode
                ? NameChanger.getStatusMessage() : NickHider.getStatusMessage();
        int nameColor = tokenlogin$nameApiMode
                ? NameChanger.getStatusColor() : NickHider.getStatusColor();
        if (!nameMsg.isEmpty()) {
            context.text(this.font, Component.literal(tokenlogin$fit(nameMsg, this.width / 2 - pad * 2)),
                    pad, tokenlogin$nameStatusY, nameColor, true);
        }

        String proxyMsg   = ProxyManager.getStatusMessage();
        int    proxyColor = ProxyManager.getStatusColor();
        if (proxyMsg.isEmpty()) { proxyMsg = "No proxy"; proxyColor = 0xFF888888; }
        proxyMsg = tokenlogin$fit(proxyMsg, this.width / 2 - pad * 2);
        int proxyMsgX = tokenlogin$stackTop ? pad : this.width - pad - this.font.width(proxyMsg);
        context.text(this.font, Component.literal(proxyMsg),
                proxyMsgX, tokenlogin$proxyStatusY, proxyColor, true);

        // ── bottom band text: session (left), token validity (right) ────────
        String username = NickHider.isEnabled()
                ? NickHider.getFakeName()
                : (this.minecraft != null ? this.minecraft.getUser().getName() : "unknown");

        String tokenMsg;
        int    tokenColor;
        if (!tokenlogin$errorMessage.isEmpty()) {
            tokenMsg   = tokenlogin$errorMessage;
            tokenColor = 0xFFFF5555;
        } else if (tokenlogin$expiryEpoch > 0) {
            long diff = tokenlogin$expiryEpoch - (System.currentTimeMillis() / 1000L);
            if (diff <= 0) {
                tokenMsg   = "Token expired";
                tokenColor = 0xFFFF5555;
            } else {
                tokenColor = diff < 300 ? 0xFFFF5555 : (diff < 1800 ? 0xFFFFAA00 : 0xFF55FF55);
                tokenMsg   = "Valid: " + tokenlogin$fmtDuration(diff);
            }
        } else {
            tokenMsg   = "No token loaded";
            tokenColor = 0xFF888888;
        }

        tokenMsg = tokenlogin$fit(tokenMsg, this.width / 2 - pad * 2);
        int tokenMsgW = this.font.width(tokenMsg);

        String sessionMsg = tokenlogin$fit("Logged in as: " + username,
                this.width - tokenMsgW - pad * 3);
        context.text(this.font, Component.literal(sessionMsg),
                pad, tokenlogin$infoY, 0xFFFFFFFF, true);
        context.text(this.font, Component.literal(tokenMsg),
                this.width - pad - tokenMsgW, tokenlogin$infoY, tokenColor, true);
    }

    /** Truncates to maxWidth so band text never runs under the widgets beside it. */
    @Unique
    private String tokenlogin$fit(String text, int maxWidth) {
        if (maxWidth <= 0 || this.font.width(text) <= maxWidth) return text;
        int ellipsis = this.font.width("...");
        return this.font.plainSubstrByWidth(text, Math.max(0, maxWidth - ellipsis)) + "...";
    }
    // =====================================================================
    // Token Browser
    // =====================================================================

    @Unique
    private void tokenlogin$openBrowser() {
        JoinMultiplayerScreen self = (JoinMultiplayerScreen)(Object) this;
        this.minecraft.setScreenAndShow(new TokenBrowserScreen(
                self,
                token -> {
                    tokenlogin$tokenField.setValue(token);
                    tokenlogin$errorMessage = "Token pasted — press Login";
                    TokenManager.ExpiryInfo exp = TokenManager.decodeExpiry(token);
                    tokenlogin$expiryEpoch = exp != null ? exp.expireEpochSeconds() : 0L;
                },
                account -> tokenlogin$applyAccountSession(account)
        ));
    }

    @Unique
    private void tokenlogin$applyAccountSession(AccountEntry account) {
        try {
            // Use cached UUID if available, otherwise zero UUID as placeholder.
            // Server authenticates by token — username/UUID here are cosmetic client-side.
            UUID uuid;
            if (account.uuid != null && !account.uuid.isBlank()) {
                uuid = tokenlogin$parseUuid(account.uuid);
            } else {
                uuid = new UUID(0L, 0L);
            }

            String displayName = (account.username != null && !account.username.isBlank())
                    ? account.username : "Player";

            User session = new User(
                    displayName,
                    uuid,
                    account.minecraftToken,
                    Optional.empty(),
                    Optional.empty()
            );
            ((MinecraftClientAccessor) this.minecraft).tokenlogin$setSession(session);

            TokenManager.ExpiryInfo exp = TokenManager.decodeExpiry(account.minecraftToken);
            tokenlogin$expiryEpoch  = exp != null ? exp.expireEpochSeconds() : 0L;
            tokenlogin$errorMessage = "";

            tokenlogin$tokenField.setValue(account.minecraftToken);

            TokenLoginClient.LOGGER.info("Session applied from browser: {} (skipped API)", displayName);

            // Fire async update — resolves current username/UUID from the API
            // and updates the AccountEntry + session if anything changed
            tokenlogin$asyncUpdateUsername(account);

            // Auto proxy switching: connect to bound proxy or disconnect if none
            if (account.boundProxyAddress != null && !account.boundProxyAddress.isBlank()) {
                String addr = account.boundProxyAddress;
                String user = "";
                String pass = "";
                for (ProxyEntry p : ProxyConfig.getProxies()) {
                    if (p.key().equals(addr.trim().toLowerCase())) {
                        user = p.username;
                        pass = p.password;
                        break;
                    }
                }
                tokenlogin$proxyAddressField.setValue(addr);
                tokenlogin$proxyUserField.setValue(user);
                tokenlogin$proxyPassField.setValue(pass);
                tokenlogin$handleProxyConnect();
            } else {
                tokenlogin$handleProxyDisconnect();
            }

        } catch (Exception e) {
            tokenlogin$errorMessage = "Apply failed: " + e.getMessage();
            TokenLoginClient.LOGGER.warn("Failed to apply account session: {}", e.getMessage());
        }
    }

    /**
     * Background thread: calls the Minecraft profile API to get the current
     * username/UUID for this token.  If the name changed (e.g. after a name
     * snipe), updates the AccountEntry, persists it, and re-injects the
     * session so the client shows the correct name.
     */
    @Unique
    private void tokenlogin$asyncUpdateUsername(AccountEntry account) {
        Thread t = new Thread(() -> {
            try {
                TokenManager.SessionInfo info = TokenManager.authenticate(account.minecraftToken);
                this.minecraft.execute(() -> {
                    boolean changed = false;

                    if (!info.username().equals(account.username)) {
                        TokenLoginClient.LOGGER.info("Username updated: {} -> {}",
                                account.username, info.username());
                        account.username = info.username();
                        changed = true;
                    }

                    String newUuid = info.uuid().toString();
                    if (!newUuid.equalsIgnoreCase(account.uuid)) {
                        account.uuid = newUuid;
                        changed = true;
                    }

                    if (changed) {
                        // Persist updated identity
                        AccountStorage.updateTokens(account);

                        // Re-inject session with correct name
                        TokenManager.applySession(info, account.minecraftToken);
                        TokenLoginClient.LOGGER.info("Session re-injected with updated identity: {}",
                                info.username());
                    }
                });
            } catch (Exception e) {
                // Non-fatal — session is already set, just couldn't verify the name
                TokenLoginClient.LOGGER.debug("Async username update failed: {}", e.getMessage());
            }
        }, "TokenLogin-UsernameUpdate");
        t.setDaemon(true);
        t.start();
    }

    @Unique
    private void tokenlogin$loginWithToken(String token) {
        if (tokenlogin$loginInProgress) return;
        tokenlogin$loginInProgress    = true;
        tokenlogin$loginButton.active = false;
        tokenlogin$errorMessage       = "Authenticating...";
        tokenlogin$tokenField.setValue(token);

        Thread t = new Thread(() -> {
            try {
                TokenManager.SessionInfo info = TokenManager.authenticate(token);
                this.minecraft.execute(() -> {
                    TokenManager.applySession(info, token);
                    TokenManager.ExpiryInfo fresh = TokenManager.decodeExpiry(token);
                    tokenlogin$expiryEpoch         = fresh != null ? fresh.expireEpochSeconds() : 0L;
                    tokenlogin$errorMessage        = "";
                    tokenlogin$loginButton.active  = true;
                    tokenlogin$loginInProgress     = false;
                });
            } catch (Exception e) {
                this.minecraft.execute(() -> {
                    tokenlogin$errorMessage        = e.getMessage();
                    tokenlogin$expiryEpoch         = 0L;
                    tokenlogin$loginButton.active  = true;
                    tokenlogin$loginInProgress     = false;
                });
            }
        }, "TokenLogin-Auth");
        t.setDaemon(true);
        t.start();
    }

    // =====================================================================
    // Token login logic
    // =====================================================================

    @Unique
    private void tokenlogin$handleLogin() {
        if (tokenlogin$loginInProgress) return;

        String token = tokenlogin$tokenField.getValue().trim();
        if (token.isEmpty()) {
            tokenlogin$errorMessage = "Enter a token first";
            tokenlogin$expiryEpoch  = 0L;
            return;
        }

        TokenManager.ExpiryInfo expiry = TokenManager.decodeExpiry(token);
        if (expiry == null) {
            tokenlogin$errorMessage = "Not a valid JWT";
            tokenlogin$expiryEpoch  = 0L;
            return;
        }
        if (expiry.expired()) {
            tokenlogin$errorMessage = "Token expired " + expiry.formatDuration() + " ago";
            tokenlogin$expiryEpoch  = 0L;
            return;
        }

        tokenlogin$loginWithToken(token);
    }

    @Unique
    private void tokenlogin$handleRestore() {
        if (tokenlogin$originalSession == null || this.minecraft == null) return;
        ((MinecraftClientAccessor) this.minecraft).tokenlogin$setSession(tokenlogin$originalSession);
        tokenlogin$expiryEpoch  = 0L;
        tokenlogin$errorMessage = "";
        TokenLoginClient.LOGGER.info("Session restored: {}", tokenlogin$originalSession.getName());
    }

    // =====================================================================
    // IGN change / Nick hider logic
    // =====================================================================

    @Unique
    private void tokenlogin$toggleNameMode() {
        tokenlogin$nameApiMode = !tokenlogin$nameApiMode;
        tokenlogin$nameModeButton.setMessage(
                Component.literal(tokenlogin$nameApiMode ? "IGN" : "Hider"));
        if (tokenlogin$nameApiMode && NickHider.isEnabled()) {
            NickHider.disable();
        }
    }

    @Unique
    private void tokenlogin$handleNameChange() {
        if (tokenlogin$nameChangeInProgress) return;
        String newName = tokenlogin$nameField.getValue().trim();
        if (newName.isEmpty()) return;

        if (tokenlogin$nameApiMode) {
            tokenlogin$nameChangeInProgress    = true;
            tokenlogin$nameChangeButton.active = false;

            Thread t = new Thread(() -> {
                NameChanger.ChangeResult result = NameChanger.changeName(newName);
                this.minecraft.execute(() -> {
                    if (result.success()) NameChanger.applyNewName(result.newName(), result.uuid());
                    tokenlogin$nameChangeInProgress    = false;
                    tokenlogin$nameChangeButton.active = true;
                });
            }, "TokenLogin-NameChange");
            t.setDaemon(true);
            t.start();
        } else {
            NickHider.enable(newName);
        }
    }

    // =====================================================================
    // Selfban toggle
    // =====================================================================

    @Unique
    private void tokenlogin$handleSelfbanToggle() {
        SelfBan.State newState = SelfBan.toggle();
        tokenlogin$selfbanButton.setMessage(tokenlogin$selfbanLabel());

        if (newState == SelfBan.State.ON) {
            // Auto-connect to the SelfBan target when confirmed. SelfBan owns the
            // connect target/name so the reconnect-until-banned loop stays consistent.
            SelfBan.connect(new JoinMultiplayerScreen(new TitleScreen()));
        }
    }

    @Unique
    private static Component tokenlogin$selfbanLabel() {
        return switch (SelfBan.getState()) {
            case OFF        -> Component.literal("Selfban: ").append(Component.literal("OFF").withStyle(s -> s.withColor(0xFF5555)));
            case CONFIRMING -> Component.literal("Sure?").withStyle(s -> s.withColor(0xFFAA00));
            case ON         -> Component.literal("Selfban: ").append(Component.literal("ON").withStyle(s -> s.withColor(0x55FF55)));
        };
    }

    // =====================================================================
    // AutoReconnect label
    // =====================================================================

    @Unique
    private static Component tokenlogin$autoRecLabel() {
        if (AutoReconnect.isEnabled()) {
            return Component.literal("AutoRec: ")
                    .append(Component.literal("ON").withStyle(s -> s.withColor(0x55FF55)));
        }
        return Component.literal("AutoRec: ")
                .append(Component.literal("OFF").withStyle(s -> s.withColor(0xFF5555)));
    }

    // =====================================================================
    // Proxy Browser
    // =====================================================================

    @Unique
    private void tokenlogin$openProxyBrowser() {
        JoinMultiplayerScreen self = (JoinMultiplayerScreen)(Object) this;
        this.minecraft.setScreenAndShow(new ProxyBrowserScreen(
                self,
                entry -> tokenlogin$applyProxySelection(entry),
                entry -> {
                    tokenlogin$applyProxySelection(entry);
                    tokenlogin$handleProxyConnect();
                }
        ));
    }

    @Unique
    private void tokenlogin$applyProxySelection(ProxyEntry entry) {
        // Populate the multiplayer screen fields with the selected proxy
        tokenlogin$proxyAddressField.setValue(entry.address);
        tokenlogin$proxyUserField.setValue(entry.username);
        tokenlogin$proxyPassField.setValue(entry.password);

        // Sync legacy config fields
        ProxyConfig.setAddress(entry.address);
        ProxyConfig.setUsername(entry.username);
        ProxyConfig.setPassword(entry.password);
        ProxyConfig.save();

        // Update disconnect button state
        tokenlogin$proxyDisconnectButton.active = ProxyManager.isEnabled();
    }

    // =====================================================================
    // Proxy connect / disconnect
    // =====================================================================

    @Unique
    private void tokenlogin$saveProxyFields() {
        ProxyConfig.setAddress(tokenlogin$proxyAddressField.getValue().trim());
        ProxyConfig.setUsername(tokenlogin$proxyUserField.getValue().trim());
        ProxyConfig.setPassword(tokenlogin$proxyPassField.getValue().trim());
        ProxyConfig.save();
    }

    @Unique
    private void tokenlogin$handleProxyConnect() {
        if (tokenlogin$proxyTestInProgress) return;
        String address = tokenlogin$proxyAddressField.getValue().trim();
        if (address.isEmpty()) { ProxyManager.disable(); return; }

        String user = tokenlogin$proxyUserField.getValue().trim();
        String pass = tokenlogin$proxyPassField.getValue().trim();

        tokenlogin$saveProxyFields();
        tokenlogin$proxyTestInProgress       = true;
        tokenlogin$proxyConnectButton.active = false;

        Thread t = new Thread(() -> {
            ProxyManager.ProxyType result = ProxyManager.testAndConnect(address, user, pass);
            this.minecraft.execute(() -> {
                tokenlogin$proxyTestInProgress          = false;
                tokenlogin$proxyConnectButton.active    = true;
                tokenlogin$proxyDisconnectButton.active = (result != ProxyManager.ProxyType.NONE);

                // Sync to proxy list — find or create entry, mark active
                if (result != ProxyManager.ProxyType.NONE) {
                    ProxyEntry entry = null;
                    String key = address.trim().toLowerCase();
                    for (ProxyEntry p : ProxyConfig.getProxies()) {
                        if (p.key().equals(key)) { entry = p; break; }
                    }
                    if (entry == null) {
                        entry = new ProxyEntry();
                        entry.address  = address;
                        entry.username = user;
                        entry.password = pass;
                        ProxyConfig.addProxy(entry);
                    }
                    ProxyConfig.markConnected(entry, result);
                }
            });
        }, "TokenLogin-ProxyTest");
        t.setDaemon(true);
        t.start();
    }

    @Unique
    private void tokenlogin$handleProxyDisconnect() {
        ProxyManager.disable();
        ProxyConfig.setActiveKey("");
        tokenlogin$proxyDisconnectButton.active = false;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    @Unique
    private static String tokenlogin$fmtDuration(long s) {
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format("%dh %dm %ds", h, m, sec);
        if (m > 0) return String.format("%dm %ds", m, sec);
        return String.format("%ds", sec);
    }

    @Unique
    private static UUID tokenlogin$parseUuid(String raw) {
        if (raw.contains("-")) return UUID.fromString(raw);
        return UUID.fromString(raw.replaceAll(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
    }
}
