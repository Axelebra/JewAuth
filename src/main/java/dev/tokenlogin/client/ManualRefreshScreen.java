package dev.tokenlogin.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Manual refresh tester — enter a client_id and refresh_token,
 * pick an endpoint, hit Refresh, see the result.
 */
public class ManualRefreshScreen extends Screen {

    private final Screen parent;

    private TextFieldWidget clientIdField;
    private TextFieldWidget refreshTokenField;
    private ButtonWidget    endpointButton;
    private ButtonWidget    refreshButton;
    private ButtonWidget    copyButton;
    private ButtonWidget    backButton;

    private boolean liveAuth = false;

    private String outputText  = "";
    private int    outputColor = 0xFFAAAAAA;
    private String resultToken = "";

    private boolean running = false;

    // Track y positions of labels so render() stays in sync with init()
    private int labelClientIdY;
    private int labelRefreshY;
    private int outputBoxY;

    public ManualRefreshScreen(Screen parent) {
        super(Text.literal("Manual Refresh"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx     = this.width / 2;
        int fieldW = Math.min(400, this.width - 60);
        int left   = cx - fieldW / 2;
        int y      = 36;

        // ── Client ID ─────────────────────────────────────────────────────
        labelClientIdY = y;
        y += 12;
        clientIdField = new TextFieldWidget(
                this.textRenderer, left, y, fieldW, 20,
                Text.literal("Client ID"));
        clientIdField.setMaxLength(256);
        clientIdField.setSuggestion("Client ID");
        this.addDrawableChild(clientIdField);
        y += 20 + 18;

        // ── Refresh Token ─────────────────────────────────────────────────
        labelRefreshY = y;
        y += 12;
        refreshTokenField = new TextFieldWidget(
                this.textRenderer, left, y, fieldW, 20,
                Text.literal("Refresh Token"));
        refreshTokenField.setMaxLength(4096);
        refreshTokenField.setSuggestion("Refresh Token");
        this.addDrawableChild(refreshTokenField);
        y += 20 + 18;

        // ── Endpoint toggle ───────────────────────────────────────────────
        endpointButton = ButtonWidget.builder(
                Text.literal(endpointLabel()),
                btn -> {
                    liveAuth = !liveAuth;
                    btn.setMessage(Text.literal(endpointLabel()));
                    setFocused(null);
                }
        ).dimensions(left, y, fieldW, 20).build();
        this.addDrawableChild(endpointButton);
        y += 20 + 16;

        // ── Refresh button ────────────────────────────────────────────────
        refreshButton = ButtonWidget.builder(
                Text.literal("Refresh"),
                btn -> { doRefresh(); setFocused(null); }
        ).dimensions(cx - 50, y, 100, 20).build();
        this.addDrawableChild(refreshButton);
        y += 20 + 16;

        // ── Output box drawn in render() ──────────────────────────────────
        outputBoxY = y;

        // ── Copy button ───────────────────────────────────────────────────
        copyButton = ButtonWidget.builder(
                Text.literal("Copy Token"),
                btn -> {
                    if (!resultToken.isBlank()) {
                        this.client.keyboard.setClipboard(resultToken);
                        outputText  = "Copied to clipboard.";
                        outputColor = 0xFF55FF55;
                    }
                    setFocused(null);
                }
        ).dimensions(cx - 50, this.height - 28, 100, 20).build();
        this.addDrawableChild(copyButton);

        // ── Back ──────────────────────────────────────────────────────────
        backButton = ButtonWidget.builder(
                Text.literal("< Back"),
                btn -> this.client.setScreen(parent)
        ).dimensions(4, 3, 50, 16).build();
        this.addDrawableChild(backButton);
    }

    private String endpointLabel() {
        return "Endpoint: " + (liveAuth ? "login.live.com" : "microsoftonline.com");
    }

    private void doRefresh() {
        if (running) return;
        String cid = clientIdField.getText().strip();
        String rt  = refreshTokenField.getText().strip();

        if (cid.isBlank()) { outputText = "Client ID is empty."; outputColor = 0xFFFF5555; return; }
        if (rt.isBlank())  { outputText = "Refresh token is empty."; outputColor = 0xFFFF5555; return; }

        running = true;
        outputText  = "Running...";
        outputColor = 0xFFFFFF55;
        resultToken = "";
        refreshButton.active = false;

        boolean live = this.liveAuth;
        Thread t = new Thread(() -> {
            try {
                ProxyEntry proxy = MicrosoftAuthChain.grabProxy();
                MicrosoftAuthChain.RefreshResult result =
                        MicrosoftAuthChain.refresh(rt, cid, live, proxy);
                this.client.execute(() -> {
                    running = false;
                    refreshButton.active = true;
                    resultToken = result.minecraftToken();
                    outputText  = "OK — expires " + result.expiresEpoch();
                    outputColor = 0xFF55FF55;
                });
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                this.client.execute(() -> {
                    running = false;
                    refreshButton.active = true;
                    outputText  = "FAILED: " + msg;
                    outputColor = 0xFFFF5555;
                });
            }
        }, "TokenLogin-ManualRefresh");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);

        int cx     = this.width / 2;
        int fieldW = Math.min(400, this.width - 60);
        int left   = cx - fieldW / 2;

        // Title
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Manual Refresh"), cx, 14, 0xFFFFFFFF);

        // Field labels
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Client ID"),
                left, labelClientIdY, 0xFF888888);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Refresh Token"),
                left, labelRefreshY, 0xFF888888);

        // Output box
        if (!outputText.isBlank()) {
            int boxH  = 46;
            int boxX  = left;
            int boxY  = outputBoxY;
            int boxW  = fieldW;
            ctx.fill(boxX - 1, boxY - 1, boxX + boxW + 1, boxY + boxH + 1, 0xFF333333);
            ctx.fill(boxX,     boxY,     boxX + boxW,     boxY + boxH,     0xFF111111);
            drawWrapped(ctx, outputText, boxX + 5, boxY + 6, boxW - 10, outputColor);
        }
    }

    private void drawWrapped(DrawContext ctx, String text, int x, int y, int maxW, int color) {
        if (text == null || text.isBlank()) return;
        int lineH = 11;
        while (!text.isEmpty()) {
            String line = text;
            if (this.textRenderer.getWidth(line) > maxW) {
                int b = line.length();
                while (b > 0 && this.textRenderer.getWidth(line.substring(0, b)) > maxW) b--;
                if (b == 0) b = 1;
                line = text.substring(0, b);
                text = text.substring(b);
            } else {
                text = "";
            }
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(line), x, y, color);
            y += lineH;
        }
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
