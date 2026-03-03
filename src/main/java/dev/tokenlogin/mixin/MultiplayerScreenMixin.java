package dev.tokenlogin.mixin;

import dev.tokenlogin.client.AccountEntry;
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
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.session.Session;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.UUID;

@Environment(EnvType.CLIENT)
@Mixin(MultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {

    // =====================================================================
    // Token login widgets (bottom-left)
    // =====================================================================

    @Unique private TextFieldWidget tokenlogin$tokenField;
    @Unique private ButtonWidget    tokenlogin$loginButton;
    @Unique private ButtonWidget    tokenlogin$restoreButton;
    @Unique private ButtonWidget    tokenlogin$browseButton;

    @Unique private static long   tokenlogin$expiryEpoch  = 0L;
    @Unique private static String tokenlogin$errorMessage = "";

    @Unique private volatile boolean tokenlogin$loginInProgress = false;

    @Unique private static Session tokenlogin$originalSession = null;

    // =====================================================================
    // Proxy widgets (top-right)
    // =====================================================================

    @Unique private TextFieldWidget     tokenlogin$proxyAddressField;
    @Unique private TextFieldWidget     tokenlogin$proxyUserField;
    @Unique private PasswordFieldWidget tokenlogin$proxyPassField;
    @Unique private ButtonWidget        tokenlogin$proxyConnectButton;
    @Unique private ButtonWidget        tokenlogin$proxyDisconnectButton;
    @Unique private ButtonWidget        tokenlogin$proxyBrowseButton;

    @Unique private volatile boolean tokenlogin$proxyTestInProgress = false;

    // =====================================================================
    // IGN changer widgets (top-left)
    // =====================================================================

    @Unique private TextFieldWidget tokenlogin$nameField;
    @Unique private ButtonWidget    tokenlogin$nameChangeButton;
    @Unique private ButtonWidget    tokenlogin$nameModeButton;

    @Unique private static boolean tokenlogin$nameApiMode = false;

    @Unique private volatile boolean tokenlogin$nameChangeInProgress = false;

    // =====================================================================
    // Selfban toggle (bottom-right)
    // =====================================================================

    @Unique private ButtonWidget tokenlogin$selfbanButton;

    protected MultiplayerScreenMixin(Text title) { super(title); }

    // =====================================================================
    // init
    // =====================================================================

    @Inject(method = "init", at = @At("TAIL"))
    private void tokenlogin$onInit(CallbackInfo ci) {

        ProxyConfig.load();

        if (tokenlogin$originalSession == null && this.client != null) {
            tokenlogin$originalSession = this.client.getSession();
        }

        // ── Token login row (bottom-left) ────────────────────────────────────
        // Reduced field width and tighter button spacing to avoid overlapping
        // the vanilla multiplayer buttons above.
        int h          = 14;
        int fieldWidth = 120;   // was 160 — shorter field
        int loginW     = 44;
        int restoreW   = 50;
        int browseW    = 48;
        int gap        = 2;
        int x          = 4;
        int y          = this.height - h - 2;

        tokenlogin$tokenField = new TextFieldWidget(
                this.textRenderer, x, y, fieldWidth, h,
                Text.literal("Token"));
        tokenlogin$tokenField.setMaxLength(4096);
        tokenlogin$tokenField.setPlaceholder(Text.literal("Paste token..."));
        this.addDrawableChild(tokenlogin$tokenField);

        tokenlogin$loginButton = ButtonWidget.builder(
                Text.literal("Login"),
                btn -> tokenlogin$handleLogin()
        ).dimensions(x + fieldWidth + gap, y, loginW, h).build();
        this.addDrawableChild(tokenlogin$loginButton);

        tokenlogin$restoreButton = ButtonWidget.builder(
                Text.literal("Restore"),
                btn -> tokenlogin$handleRestore()
        ).dimensions(x + fieldWidth + gap + loginW + gap, y, restoreW, h).build();
        tokenlogin$restoreButton.active = tokenlogin$originalSession != null;
        this.addDrawableChild(tokenlogin$restoreButton);

        tokenlogin$browseButton = ButtonWidget.builder(
                Text.literal("Browse..."),
                btn -> tokenlogin$openBrowser()
        ).dimensions(x + fieldWidth + gap + loginW + gap + restoreW + gap, y, browseW, h).build();
        this.addDrawableChild(tokenlogin$browseButton);

        // ── Proxy (top-right) ────────────────────────────────────────────────
        int addrW       = 120;
        int btnW        = 56;
        int browseProxyW = 52;
        int halfW       = (addrW - gap) / 2;
        int rightX      = this.width - addrW - btnW - gap - browseProxyW - gap - 4;
        int row1Y       = 2;
        int row2Y       = row1Y + h + gap;

        tokenlogin$proxyAddressField = new TextFieldWidget(
                this.textRenderer, rightX, row1Y, addrW, h,
                Text.literal("Address"));
        tokenlogin$proxyAddressField.setMaxLength(256);
        tokenlogin$proxyAddressField.setPlaceholder(Text.literal("ip:port"));
        tokenlogin$proxyAddressField.setText(ProxyConfig.getAddress());
        this.addDrawableChild(tokenlogin$proxyAddressField);

        tokenlogin$proxyConnectButton = ButtonWidget.builder(
                Text.literal("Connect"),
                btn -> tokenlogin$handleProxyConnect()
        ).dimensions(rightX + addrW + gap, row1Y, btnW, h).build();
        this.addDrawableChild(tokenlogin$proxyConnectButton);

        tokenlogin$proxyBrowseButton = ButtonWidget.builder(
                Text.literal("Browse..."),
                btn -> tokenlogin$openProxyBrowser()
        ).dimensions(rightX + addrW + gap + btnW + gap, row1Y, browseProxyW, h).build();
        this.addDrawableChild(tokenlogin$proxyBrowseButton);

        tokenlogin$proxyUserField = new TextFieldWidget(
                this.textRenderer, rightX, row2Y, halfW, h,
                Text.literal("Username"));
        tokenlogin$proxyUserField.setMaxLength(256);
        tokenlogin$proxyUserField.setPlaceholder(Text.literal("User"));
        tokenlogin$proxyUserField.setText(ProxyConfig.getUsername());
        this.addDrawableChild(tokenlogin$proxyUserField);

        tokenlogin$proxyPassField = new PasswordFieldWidget(
                this.textRenderer, rightX + halfW + gap, row2Y, halfW, h,
                Text.literal("Password"));
        tokenlogin$proxyPassField.setMaxLength(256);
        tokenlogin$proxyPassField.setPlaceholder(Text.literal("Pass"));
        tokenlogin$proxyPassField.setText(ProxyConfig.getPassword());
        this.addDrawableChild(tokenlogin$proxyPassField);

        tokenlogin$proxyDisconnectButton = ButtonWidget.builder(
                Text.literal("Off"),
                btn -> tokenlogin$handleProxyDisconnect()
        ).dimensions(rightX + addrW + gap, row2Y, btnW + gap + browseProxyW, h).build();
        tokenlogin$proxyDisconnectButton.active = ProxyManager.isEnabled();
        this.addDrawableChild(tokenlogin$proxyDisconnectButton);

        // ── IGN changer (top-left) ───────────────────────────────────────────
        int nameFieldW = 120;
        int nameBtnW   = 50;
        int modeBtnW   = 36;
        int nameX      = 4;
        int nameY      = 2;

        tokenlogin$nameField = new TextFieldWidget(
                this.textRenderer, nameX, nameY, nameFieldW, h,
                Text.literal("IGN"));
        tokenlogin$nameField.setMaxLength(16);
        tokenlogin$nameField.setPlaceholder(Text.literal("New name..."));
        this.addDrawableChild(tokenlogin$nameField);

        tokenlogin$nameChangeButton = ButtonWidget.builder(
                Text.literal("Change"),
                btn -> tokenlogin$handleNameChange()
        ).dimensions(nameX + nameFieldW + gap, nameY, nameBtnW, h).build();
        this.addDrawableChild(tokenlogin$nameChangeButton);

        tokenlogin$nameModeButton = ButtonWidget.builder(
                Text.literal(tokenlogin$nameApiMode ? "IGN" : "Hider"),
                btn -> tokenlogin$toggleNameMode()
        ).dimensions(nameX + nameFieldW + gap + nameBtnW + gap, nameY, modeBtnW, h).build();
        this.addDrawableChild(tokenlogin$nameModeButton);

        // ── Selfban toggle (bottom-right) ────────────────────────────────────
        int selfbanW = 72;
        int selfbanX = this.width - selfbanW - 4;
        int selfbanY = this.height - h - 2;

        tokenlogin$selfbanButton = ButtonWidget.builder(
                tokenlogin$selfbanLabel(),
                btn -> tokenlogin$handleSelfbanToggle()
        ).dimensions(selfbanX, selfbanY, selfbanW, h).build();
        this.addDrawableChild(tokenlogin$selfbanButton);
    }

    // =====================================================================
    // render
    // =====================================================================

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        tokenlogin$renderTokenInfo(context);
        tokenlogin$renderProxyStatus(context);
        tokenlogin$renderNameStatus(context);
    }

    @Unique
    private void tokenlogin$renderTokenInfo(DrawContext context) {
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
                : (this.client != null ? this.client.getSession().getUsername() : "unknown");

        context.drawTextWithShadow(this.textRenderer,
                Text.literal("Logged in as: " + username),
                4, stripTop + 3, 0xFFFFFFFF);

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
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal(line2),
                    4, stripTop + 3 + 10, color2);
        }
    }

    @Unique
    private void tokenlogin$renderProxyStatus(DrawContext context) {
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
        context.drawTextWithShadow(this.textRenderer, Text.literal(msg), rightX, statusY, color);
    }

    @Unique
    private void tokenlogin$renderNameStatus(DrawContext context) {
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
            context.drawTextWithShadow(this.textRenderer, Text.literal(msg), 4, statusY, color);
        }
    }

    // =====================================================================
    // Token Browser
    // =====================================================================

    @Unique
    private void tokenlogin$openBrowser() {
        MultiplayerScreen self = (MultiplayerScreen)(Object) this;
        this.client.setScreen(new TokenBrowserScreen(
                self,
                token -> {
                    tokenlogin$tokenField.setText(token);
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
            UUID uuid;
            if (account.uuid == null || account.uuid.isBlank()) {
                tokenlogin$loginWithToken(account.minecraftToken);
                return;
            }
            uuid = tokenlogin$parseUuid(account.uuid);

            Session session = new Session(
                    account.username,
                    uuid,
                    account.minecraftToken,
                    Optional.empty(),
                    Optional.empty()
            );
            ((MinecraftClientAccessor) this.client).tokenlogin$setSession(session);

            TokenManager.ExpiryInfo exp = TokenManager.decodeExpiry(account.minecraftToken);
            tokenlogin$expiryEpoch  = exp != null ? exp.expireEpochSeconds() : 0L;
            tokenlogin$errorMessage = "";

            tokenlogin$tokenField.setText(account.minecraftToken);

            TokenLoginClient.LOGGER.info("Session applied from browser: {}", account.username);
        } catch (Exception e) {
            tokenlogin$errorMessage = "Apply failed: " + e.getMessage();
            TokenLoginClient.LOGGER.warn("Failed to apply account session: {}", e.getMessage());
        }
    }

    @Unique
    private void tokenlogin$loginWithToken(String token) {
        if (tokenlogin$loginInProgress) return;
        tokenlogin$loginInProgress    = true;
        tokenlogin$loginButton.active = false;
        tokenlogin$errorMessage       = "Authenticating...";
        tokenlogin$tokenField.setText(token);

        Thread t = new Thread(() -> {
            try {
                TokenManager.SessionInfo info = TokenManager.authenticate(token);
                this.client.execute(() -> {
                    TokenManager.applySession(info, token);
                    TokenManager.ExpiryInfo fresh = TokenManager.decodeExpiry(token);
                    tokenlogin$expiryEpoch         = fresh != null ? fresh.expireEpochSeconds() : 0L;
                    tokenlogin$errorMessage        = "";
                    tokenlogin$loginButton.active  = true;
                    tokenlogin$loginInProgress     = false;
                });
            } catch (Exception e) {
                this.client.execute(() -> {
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

        String token = tokenlogin$tokenField.getText().trim();
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
        if (tokenlogin$originalSession == null || this.client == null) return;
        ((MinecraftClientAccessor) this.client).tokenlogin$setSession(tokenlogin$originalSession);
        tokenlogin$expiryEpoch  = 0L;
        tokenlogin$errorMessage = "";
        TokenLoginClient.LOGGER.info("Session restored: {}", tokenlogin$originalSession.getUsername());
    }

    // =====================================================================
    // IGN change / Nick hider logic
    // =====================================================================

    @Unique
    private void tokenlogin$toggleNameMode() {
        tokenlogin$nameApiMode = !tokenlogin$nameApiMode;
        tokenlogin$nameModeButton.setMessage(
                Text.literal(tokenlogin$nameApiMode ? "IGN" : "Hider"));
        if (tokenlogin$nameApiMode && NickHider.isEnabled()) {
            NickHider.disable();
        }
    }

    @Unique
    private void tokenlogin$handleNameChange() {
        if (tokenlogin$nameChangeInProgress) return;
        String newName = tokenlogin$nameField.getText().trim();
        if (newName.isEmpty()) return;

        if (tokenlogin$nameApiMode) {
            tokenlogin$nameChangeInProgress    = true;
            tokenlogin$nameChangeButton.active = false;

            Thread t = new Thread(() -> {
                NameChanger.ChangeResult result = NameChanger.changeName(newName);
                this.client.execute(() -> {
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
        SelfBan.toggle();
        tokenlogin$selfbanButton.setMessage(tokenlogin$selfbanLabel());
    }

    @Unique
    private static Text tokenlogin$selfbanLabel() {
        return switch (SelfBan.getState()) {
            case OFF        -> Text.literal("Selfban: ").append(Text.literal("OFF").styled(s -> s.withColor(0xFF5555)));
            case CONFIRMING -> Text.literal("Sure?").styled(s -> s.withColor(0xFFAA00));
            case ON         -> Text.literal("Selfban: ").append(Text.literal("ON").styled(s -> s.withColor(0x55FF55)));
        };
    }

    // =====================================================================
    // Proxy Browser
    // =====================================================================

    @Unique
    private void tokenlogin$openProxyBrowser() {
        MultiplayerScreen self = (MultiplayerScreen)(Object) this;
        this.client.setScreen(new ProxyBrowserScreen(
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
        tokenlogin$proxyAddressField.setText(entry.address);
        tokenlogin$proxyUserField.setText(entry.username);
        tokenlogin$proxyPassField.setText(entry.password);

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
        ProxyConfig.setAddress(tokenlogin$proxyAddressField.getText().trim());
        ProxyConfig.setUsername(tokenlogin$proxyUserField.getText().trim());
        ProxyConfig.setPassword(tokenlogin$proxyPassField.getText().trim());
        ProxyConfig.save();
    }

    @Unique
    private void tokenlogin$handleProxyConnect() {
        if (tokenlogin$proxyTestInProgress) return;
        String address = tokenlogin$proxyAddressField.getText().trim();
        if (address.isEmpty()) { ProxyManager.disable(); return; }

        String user = tokenlogin$proxyUserField.getText().trim();
        String pass = tokenlogin$proxyPassField.getText().trim();

        tokenlogin$saveProxyFields();
        tokenlogin$proxyTestInProgress       = true;
        tokenlogin$proxyConnectButton.active = false;

        Thread t = new Thread(() -> {
            ProxyManager.ProxyType result = ProxyManager.testAndConnect(address, user, pass);
            this.client.execute(() -> {
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
