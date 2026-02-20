package dev.tokenlogin.mixin;

import dev.tokenlogin.client.NameChanger;
import dev.tokenlogin.client.NickHider;
import dev.tokenlogin.client.ProxyConfig;
import dev.tokenlogin.client.ProxyManager;
import dev.tokenlogin.client.PasswordFieldWidget;
import dev.tokenlogin.client.SelfBan;
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

@Environment(EnvType.CLIENT)
@Mixin(MultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {

    // =====================================================================
    // Token login widgets (bottom-left)
    // =====================================================================

    @Unique private TextFieldWidget tokenlogin$tokenField;
    @Unique private ButtonWidget    tokenlogin$loginButton;
    @Unique private ButtonWidget    tokenlogin$restoreButton;

    @Unique private static long   tokenlogin$expiryEpoch  = 0L;
    @Unique private static String tokenlogin$errorMessage = "";

    @Unique private volatile boolean tokenlogin$loginInProgress = false;

    /** Stores the original session before any token login, so we can restore it. */
    @Unique private static Session tokenlogin$originalSession = null;

    // =====================================================================
    // Proxy widgets (top-right)
    // =====================================================================

    @Unique private TextFieldWidget tokenlogin$proxyAddressField;
    @Unique private TextFieldWidget tokenlogin$proxyUserField;
    @Unique private PasswordFieldWidget tokenlogin$proxyPassField;
    @Unique private ButtonWidget    tokenlogin$proxyConnectButton;
    @Unique private ButtonWidget    tokenlogin$proxyDisconnectButton;

    @Unique private volatile boolean tokenlogin$proxyTestInProgress = false;

    // =====================================================================
    // IGN changer widgets (top-left)
    // =====================================================================

    @Unique private TextFieldWidget tokenlogin$nameField;
    @Unique private ButtonWidget    tokenlogin$nameChangeButton;
    @Unique private ButtonWidget    tokenlogin$nameModeButton;

    /** false = Hider (client-side text replacement), true = IGN (real Mojang API change) */
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

        // Capture the original session on first init
        if (tokenlogin$originalSession == null && this.client != null) {
            tokenlogin$originalSession = this.client.getSession();
        }

        // ── Token login (bottom-left) ────────────────────────────────────
        int widgetHeight = 14;
        int fieldWidth   = 160;
        int buttonWidth  = 50;
        int restoreWidth = 56;
        int x            = 4;
        int y            = this.height - widgetHeight - 2;

        tokenlogin$tokenField = new TextFieldWidget(
                this.textRenderer, x, y, fieldWidth, widgetHeight,
                Text.literal("Token"));
        tokenlogin$tokenField.setMaxLength(4096);
        tokenlogin$tokenField.setPlaceholder(Text.literal("Paste token..."));
        this.addDrawableChild(tokenlogin$tokenField);

        tokenlogin$loginButton = ButtonWidget.builder(
                Text.literal("Login"),
                btn -> tokenlogin$handleLogin()
        ).dimensions(x + fieldWidth + 2, y, buttonWidth, widgetHeight).build();
        this.addDrawableChild(tokenlogin$loginButton);

        tokenlogin$restoreButton = ButtonWidget.builder(
                Text.literal("Restore"),
                btn -> tokenlogin$handleRestore()
        ).dimensions(x + fieldWidth + 2 + buttonWidth + 2, y, restoreWidth, widgetHeight).build();
        tokenlogin$restoreButton.active = tokenlogin$originalSession != null;
        this.addDrawableChild(tokenlogin$restoreButton);

        // ── Proxy (top-right, 2 compact rows) ───────────────────────────
        int h       = 14;
        int gap     = 2;
        int btnW    = 56;
        int addrW   = 130;
        int halfW   = (addrW - gap) / 2;
        int rightX  = this.width - addrW - btnW - gap - 4;
        int row1Y   = 2;
        int row2Y   = row1Y + h + gap;

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
        ).dimensions(rightX + addrW + gap, row2Y, btnW, h).build();
        tokenlogin$proxyDisconnectButton.active = ProxyManager.isEnabled();
        this.addDrawableChild(tokenlogin$proxyDisconnectButton);

        // ── IGN changer (top-left) ───────────────────────────────────────
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

        // ── Selfban toggle (bottom-right) ────────────────────────────────
        int selfbanW = 72;
        int selfbanX = this.width - selfbanW - 4;
        int selfbanY = this.height - widgetHeight - 2;

        tokenlogin$selfbanButton = ButtonWidget.builder(
                tokenlogin$selfbanLabel(),
                btn -> tokenlogin$handleSelfbanToggle()
        ).dimensions(selfbanX, selfbanY, selfbanW, widgetHeight).build();
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
        int WIDGET_ROW  = this.height - 16;
        int stripBottom = WIDGET_ROW - 2;
        int stripTop    = stripBottom - 26;

        int bgLeft  = tokenlogin$tokenField.getX() - 4;
        int bgRight = tokenlogin$restoreButton.getX()
                + tokenlogin$restoreButton.getWidth() + 4;

        context.fill(bgLeft, stripTop, bgRight, stripBottom, 0xBB000000);

        // Show fake name if hider is active, otherwise real session name
        String username;
        if (NickHider.isEnabled()) {
            username = NickHider.getFakeName();
        } else {
            username = this.client != null
                    ? this.client.getSession().getUsername()
                    : "unknown";
        }
        context.drawTextWithShadow(this.textRenderer,
                Text.literal("Logged in as: " + username),
                4, stripTop + 4, 0xFFFFFFFF);

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
                line2  = "Token valid for: " + tokenlogin$fmtDuration(diff);
            }
        } else {
            line2  = "No token loaded";
            color2 = 0xFF888888;
        }

        context.drawTextWithShadow(this.textRenderer,
                Text.literal(line2),
                4, stripTop + 4 + 11, color2);
    }

    @Unique
    private void tokenlogin$renderProxyStatus(DrawContext context) {
        int statusY = 2 + 14 + 2 + 14 + 3;

        int addrW   = 130;
        int btnW    = 56;
        int gap     = 2;
        int rightX  = this.width - addrW - btnW - gap - 4;

        int bgLeft   = rightX - 4;
        int bgRight  = this.width - 2;
        int bgTop    = statusY - 2;
        int bgBottom = statusY + 11;

        context.fill(bgLeft, bgTop, bgRight, bgBottom, 0xBB000000);

        String msg   = ProxyManager.getStatusMessage();
        int    color = ProxyManager.getStatusColor();
        if (msg.isEmpty()) {
            msg   = "No proxy";
            color = 0xFF888888;
        }

        context.drawTextWithShadow(this.textRenderer,
                Text.literal(msg), rightX, statusY, color);
    }

    @Unique
    private void tokenlogin$renderNameStatus(DrawContext context) {
        int statusY = 2 + 14 + 3;

        int nameFieldW = 120;
        int nameBtnW   = 50;
        int modeBtnW   = 36;
        int gap        = 2;
        int nameX      = 4;

        int bgLeft   = nameX - 4;
        int bgRight  = nameX + nameFieldW + gap + nameBtnW + gap + modeBtnW + 4;
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
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal(msg), nameX, statusY, color);
        }
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

        tokenlogin$errorMessage     = "Authenticating...";
        tokenlogin$loginInProgress  = true;
        tokenlogin$loginButton.active = false;

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
                TokenLoginClient.LOGGER.warn("Token login failed: {}", e.getMessage());
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

    @Unique
    private void tokenlogin$handleRestore() {
        if (tokenlogin$originalSession == null || this.client == null) return;

        ((MinecraftClientAccessor) this.client).tokenlogin$setSession(tokenlogin$originalSession);

        tokenlogin$expiryEpoch  = 0L;
        tokenlogin$errorMessage = "";

        TokenLoginClient.LOGGER.info("Session restored to original account: {}",
                tokenlogin$originalSession.getUsername());
    }

    // =====================================================================
    // IGN change / Nick hider logic
    // =====================================================================

    @Unique
    private void tokenlogin$toggleNameMode() {
        tokenlogin$nameApiMode = !tokenlogin$nameApiMode;
        tokenlogin$nameModeButton.setMessage(
                Text.literal(tokenlogin$nameApiMode ? "IGN" : "Hider")
        );
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
            tokenlogin$nameChangeInProgress      = true;
            tokenlogin$nameChangeButton.active   = false;

            Thread t = new Thread(() -> {
                NameChanger.ChangeResult result = NameChanger.changeName(newName);
                this.client.execute(() -> {
                    if (result.success()) {
                        NameChanger.applyNewName(result.newName(), result.uuid());
                    }
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
    // Selfban toggle logic — OFF → Sure? → ON → OFF
    // =====================================================================

    @Unique
    private void tokenlogin$handleSelfbanToggle() {
        SelfBan.toggle();
        tokenlogin$selfbanButton.setMessage(tokenlogin$selfbanLabel());
    }

    @Unique
    private static Text tokenlogin$selfbanLabel() {
        return switch (SelfBan.getState()) {
            case OFF        -> Text.literal("Selfban: ")
                    .append(Text.literal("OFF").styled(s -> s.withColor(0xFF5555)));
            case CONFIRMING -> Text.literal("Sure?")
                    .styled(s -> s.withColor(0xFFAA00));
            case ON         -> Text.literal("Selfban: ")
                    .append(Text.literal("ON").styled(s -> s.withColor(0x55FF55)));
        };
    }

    // =====================================================================
    // Proxy connect / disconnect logic
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
        if (address.isEmpty()) {
            ProxyManager.disable();
            return;
        }

        String user = tokenlogin$proxyUserField.getText().trim();
        String pass = tokenlogin$proxyPassField.getText().trim();

        tokenlogin$saveProxyFields();

        tokenlogin$proxyTestInProgress        = true;
        tokenlogin$proxyConnectButton.active  = false;

        Thread t = new Thread(() -> {
            ProxyManager.ProxyType result = ProxyManager.testAndConnect(address, user, pass);
            this.client.execute(() -> {
                tokenlogin$proxyTestInProgress        = false;
                tokenlogin$proxyConnectButton.active  = true;
                tokenlogin$proxyDisconnectButton.active = result != ProxyManager.ProxyType.NONE;
            });
        }, "TokenLogin-ProxyTest");
        t.setDaemon(true);
        t.start();
    }

    @Unique
    private void tokenlogin$handleProxyDisconnect() {
        ProxyManager.disable();
        tokenlogin$proxyDisconnectButton.active = false;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    @Unique
    private static String tokenlogin$fmtDuration(long s) {
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0)  return String.format("%dh %dm %ds", h, m, sec);
        if (m > 0)  return String.format("%dm %ds", m, sec);
        return String.format("%ds", sec);
    }
}