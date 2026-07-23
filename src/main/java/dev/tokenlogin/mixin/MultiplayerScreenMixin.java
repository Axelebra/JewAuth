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
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
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
    // init
    // =====================================================================

    @Inject(method = "init", at = @At("TAIL"))
    private void tokenlogin$onInit(CallbackInfo ci) {

        ProxyConfig.load();

        if (tokenlogin$originalSession == null && this.minecraft != null) {
            tokenlogin$originalSession = this.minecraft.getUser();
        }

        int h          = 14;
        int fieldWidth = 120;
        int loginW     = 44;
        int restoreW   = 50;
        int browseW    = 48;
        int gap        = 2;

        // ── Token login row (bottom-left) ────────────────────────────────────
        int x = 4;
        int y = this.height - h - 2;

        tokenlogin$tokenField = new EditBox(
                this.font, x, y, fieldWidth, h,
                Component.literal("Token"));
        tokenlogin$tokenField.setMaxLength(4096);
        tokenlogin$tokenField.setHint(Component.literal("Paste token..."));
        this.addRenderableWidget(tokenlogin$tokenField);

        tokenlogin$loginButton = Button.builder(
                Component.literal("Login"),
                btn -> tokenlogin$handleLogin()
        ).bounds(x + fieldWidth + gap, y, loginW, h).build();
        this.addRenderableWidget(tokenlogin$loginButton);

        tokenlogin$restoreButton = Button.builder(
                Component.literal("Restore"),
                btn -> tokenlogin$handleRestore()
        ).bounds(x + fieldWidth + gap + loginW + gap, y, restoreW, h).build();
        tokenlogin$restoreButton.active = tokenlogin$originalSession != null;
        this.addRenderableWidget(tokenlogin$restoreButton);

        tokenlogin$browseButton = Button.builder(
                Component.literal("Browse..."),
                btn -> tokenlogin$openBrowser()
        ).bounds(x + fieldWidth + gap + loginW + gap + restoreW + gap, y, browseW, h).build();
        this.addRenderableWidget(tokenlogin$browseButton);

        // ── Proxy (top-right) ────────────────────────────────────────────────
        int addrW        = 120;
        int btnW         = 56;
        int browseProxyW = 52;
        int halfW        = (addrW - gap) / 2;
        int rightX       = this.width - addrW - btnW - gap - browseProxyW - gap - 4;
        int row1Y        = 2;
        int row2Y        = row1Y + h + gap;

        tokenlogin$proxyAddressField = new EditBox(
                this.font, rightX, row1Y, addrW, h,
                Component.literal("Address"));
        tokenlogin$proxyAddressField.setMaxLength(256);
        tokenlogin$proxyAddressField.setHint(Component.literal("ip:port"));
        tokenlogin$proxyAddressField.setValue(ProxyConfig.getAddress());
        this.addRenderableWidget(tokenlogin$proxyAddressField);

        tokenlogin$proxyConnectButton = Button.builder(
                Component.literal("Connect"),
                btn -> tokenlogin$handleProxyConnect()
        ).bounds(rightX + addrW + gap, row1Y, btnW, h).build();
        this.addRenderableWidget(tokenlogin$proxyConnectButton);

        tokenlogin$proxyBrowseButton = Button.builder(
                Component.literal("Browse..."),
                btn -> tokenlogin$openProxyBrowser()
        ).bounds(rightX + addrW + gap + btnW + gap, row1Y, browseProxyW, h).build();
        this.addRenderableWidget(tokenlogin$proxyBrowseButton);

        tokenlogin$proxyUserField = new EditBox(
                this.font, rightX, row2Y, halfW, h,
                Component.literal("Username"));
        tokenlogin$proxyUserField.setMaxLength(256);
        tokenlogin$proxyUserField.setHint(Component.literal("User"));
        tokenlogin$proxyUserField.setValue(ProxyConfig.getUsername());
        this.addRenderableWidget(tokenlogin$proxyUserField);

        tokenlogin$proxyPassField = new PasswordFieldWidget(
                this.font, rightX + halfW + gap, row2Y, halfW, h,
                Component.literal("Password"));
        tokenlogin$proxyPassField.setMaxLength(256);
        tokenlogin$proxyPassField.setHint(Component.literal("Pass"));
        tokenlogin$proxyPassField.setValue(ProxyConfig.getPassword());
        this.addRenderableWidget(tokenlogin$proxyPassField);

        tokenlogin$proxyDisconnectButton = Button.builder(
                Component.literal("Off"),
                btn -> tokenlogin$handleProxyDisconnect()
        ).bounds(rightX + addrW + gap, row2Y, btnW + gap + browseProxyW, h).build();
        tokenlogin$proxyDisconnectButton.active = ProxyManager.isEnabled();
        this.addRenderableWidget(tokenlogin$proxyDisconnectButton);

        // ── IGN changer (top-left) ───────────────────────────────────────────
        int nameFieldW = 120;
        int nameBtnW   = 50;
        int modeBtnW   = 36;
        int nameX      = 4;
        int nameY      = 2;

        tokenlogin$nameField = new EditBox(
                this.font, nameX, nameY, nameFieldW, h,
                Component.literal("IGN"));
        tokenlogin$nameField.setMaxLength(16);
        tokenlogin$nameField.setHint(Component.literal("New name..."));
        this.addRenderableWidget(tokenlogin$nameField);

        tokenlogin$nameChangeButton = Button.builder(
                Component.literal("Change"),
                btn -> tokenlogin$handleNameChange()
        ).bounds(nameX + nameFieldW + gap, nameY, nameBtnW, h).build();
        this.addRenderableWidget(tokenlogin$nameChangeButton);

        tokenlogin$nameModeButton = Button.builder(
                Component.literal(tokenlogin$nameApiMode ? "IGN" : "Hider"),
                btn -> tokenlogin$toggleNameMode()
        ).bounds(nameX + nameFieldW + gap + nameBtnW + gap, nameY, modeBtnW, h).build();
        this.addRenderableWidget(tokenlogin$nameModeButton);

        // ── AutoRec + Selfban (bottom-right) ─────────────────────────────────
        int autoRecW   = 72;
        int selfbanW   = 72;
        int selfbanX   = this.width - selfbanW - 4;
        int autoRecX   = selfbanX - autoRecW - gap;
        int selfbanY   = this.height - h - 2;

        tokenlogin$autoRecButton = Button.builder(
                tokenlogin$autoRecLabel(),
                btn -> { AutoReconnect.toggle(); btn.setMessage(tokenlogin$autoRecLabel()); }
        ).bounds(autoRecX, selfbanY, autoRecW, h).build();
        this.addRenderableWidget(tokenlogin$autoRecButton);

        tokenlogin$selfbanButton = Button.builder(
                tokenlogin$selfbanLabel(),
                btn -> tokenlogin$handleSelfbanToggle()
        ).bounds(selfbanX, selfbanY, selfbanW, h).build();
        this.addRenderableWidget(tokenlogin$selfbanButton);

    }

    @Unique private int tokenlogin$lastW = -1;
    @Unique private int tokenlogin$lastH = -1;

    @Unique
    private void tokenlogin$repositionWidgets() {
        if (tokenlogin$tokenField == null) return;

        int h          = 14;
        int gap        = 2;
        int fieldWidth = 120;
        int loginW     = 44;
        int restoreW   = 50;
        int browseW    = 48;

        // Bottom-left: token row
        int x = 4;
        int y = this.height - h - 2;
        tokenlogin$tokenField.setPosition(x, y);
        tokenlogin$loginButton.setPosition(x + fieldWidth + gap, y);
        tokenlogin$restoreButton.setPosition(x + fieldWidth + gap + loginW + gap, y);
        tokenlogin$browseButton.setPosition(x + fieldWidth + gap + loginW + gap + restoreW + gap, y);

        // Top-right: proxy rows
        int addrW        = 120;
        int btnW         = 56;
        int browseProxyW = 52;
        int halfW        = (addrW - gap) / 2;
        int rightX       = this.width - addrW - btnW - gap - browseProxyW - gap - 4;
        int row1Y        = 2;
        int row2Y        = row1Y + h + gap;
        tokenlogin$proxyAddressField.setPosition(rightX, row1Y);
        tokenlogin$proxyConnectButton.setPosition(rightX + addrW + gap, row1Y);
        tokenlogin$proxyBrowseButton.setPosition(rightX + addrW + gap + btnW + gap, row1Y);
        tokenlogin$proxyUserField.setPosition(rightX, row2Y);
        tokenlogin$proxyPassField.setPosition(rightX + halfW + gap, row2Y);
        tokenlogin$proxyDisconnectButton.setPosition(rightX + addrW + gap, row2Y);

        // Bottom-right: autorec + selfban (bottom row)
        int autoRecW   = 72;
        int selfbanW   = 72;
        int selfbanX   = this.width - selfbanW - 4;
        int autoRecX   = selfbanX - autoRecW - gap;
        int selfbanY   = this.height - h - 2;
        tokenlogin$autoRecButton.setPosition(autoRecX, selfbanY);
        tokenlogin$selfbanButton.setPosition(selfbanX, selfbanY);
    }

    // =====================================================================
    // render (26.x: render-state extraction)
    // =====================================================================

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float deltaTicks) {
        if (this.width != tokenlogin$lastW || this.height != tokenlogin$lastH) {
            tokenlogin$lastW = this.width;
            tokenlogin$lastH = this.height;
            tokenlogin$repositionWidgets();
        }
        // Keep selfban button label in sync — disable() can be called from the tick thread
        if (tokenlogin$selfbanButton != null) {
            tokenlogin$selfbanButton.setMessage(tokenlogin$selfbanLabel());
        }
        super.extractRenderState(extractor, mouseX, mouseY, deltaTicks);
        tokenlogin$renderTokenInfo(extractor);
        tokenlogin$renderProxyStatus(extractor);
        tokenlogin$renderNameStatus(extractor);
    }

    @Unique
    private void tokenlogin$renderTokenInfo(GuiGraphicsExtractor context) {
        int h = 14;

        // Widget row sits at the very bottom
        int widgetY     = this.height - h - 2;
        int stripBottom = widgetY - 2;
        // Reduced strip height — was 26, now 20 — less overlap with buttons above
        int stripTop    = stripBottom - 20;

        // Right edge of the Browse button
        int fieldWidth = 120;
        int loginW     = 44;
        int restoreW   = 50;
        int browseW    = 48;
        int gap        = 2;
        int bgLeft     = 0;
        int bgRight    = 4 + fieldWidth + gap + loginW + gap + restoreW + gap + browseW + 4;

        context.fill(bgLeft, stripTop, bgRight, stripBottom, 0xBB000000);

        String username = NickHider.isEnabled()
                ? NickHider.getFakeName()
                : (this.minecraft != null ? this.minecraft.getUser().getName() : "unknown");

        context.text(this.font,
                Component.literal("Logged in as: " + username),
                4, stripTop + 3, 0xFFFFFFFF, true);

        String line2;
        int    color2;

        if (!tokenlogin$errorMessage.isEmpty()) {
            line2  = tokenlogin$errorMessage;
            color2 = 0xFFFF5555;
        } else if (tokenlogin$expiryEpoch > 0) {
            long diff = tokenlogin$expiryEpoch - (System.currentTimeMillis() / 1000L);
            if (diff <= 0) {
                line2  = "Token expired";
                color2 = 0xFFFF5555;
            } else {
                color2 = diff < 300 ? 0xFFFF5555 : (diff < 1800 ? 0xFFFFAA00 : 0xFF55FF55);
                line2  = "Valid: " + tokenlogin$fmtDuration(diff);
            }
        } else {
            line2  = "No token loaded";
            color2 = 0xFF888888;
        }

        // Only draw line 2 if there's room (strip is now 20px, line needs ~9px each)
        if (stripBottom - stripTop >= 20) {
            context.text(this.font,
                    Component.literal(line2),
                    4, stripTop + 3 + 10, color2, true);
        }
    }

    @Unique
    private void tokenlogin$renderProxyStatus(GuiGraphicsExtractor context) {
        int h       = 14;
        int statusY = 2 + h + 2 + h + 3;
        int addrW   = 120;
        int btnW    = 56;
        int browseProxyW = 52;
        int gap     = 2;
        int rightX  = this.width - addrW - btnW - gap - browseProxyW - gap - 4;

        context.fill(rightX - 4, statusY - 2, this.width - 2, statusY + 11, 0xBB000000);

        String msg   = ProxyManager.getStatusMessage();
        int    color = ProxyManager.getStatusColor();
        if (msg.isEmpty()) { msg = "No proxy"; color = 0xFF888888; }
        context.text(this.font, Component.literal(msg), rightX, statusY, color, true);
    }

    @Unique
    private void tokenlogin$renderNameStatus(GuiGraphicsExtractor context) {
        int h          = 14;
        int statusY    = 2 + h + 3;
        int nameFieldW = 120;
        int nameBtnW   = 50;
        int modeBtnW   = 36;
        int gap        = 2;

        int bgLeft   = 0;
        int bgRight  = 4 + nameFieldW + gap + nameBtnW + gap + modeBtnW + 4;
        int bgTop    = statusY - 2;
        int bgBottom = statusY + 11;

        String msg;
        int    color;
        if (tokenlogin$nameApiMode) {
            msg   = NameChanger.getStatusMessage();
            color = NameChanger.getStatusColor();
        } else {
            msg   = NickHider.getStatusMessage();
            color = NickHider.getStatusColor();
        }

        if (!msg.isEmpty()) {
            context.fill(bgLeft, bgTop, bgRight, bgBottom, 0xBB000000);
            context.text(this.font, Component.literal(msg), 4, statusY, color, true);
        }
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
