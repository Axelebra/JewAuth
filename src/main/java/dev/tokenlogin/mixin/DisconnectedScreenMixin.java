package dev.tokenlogin.mixin;

import dev.tokenlogin.client.AutoReconnect;
import dev.tokenlogin.client.SelfBan;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds AutoReconnect countdown + toggle button to the disconnect screen.
 * Ported from MeteorClient's DisconnectedScreenMixin.
 */
@Environment(EnvType.CLIENT)
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin extends Screen {

    @Unique private Button tokenlogin$reconnectBtn;
    @Unique private Button tokenlogin$toggleBtn;
    @Unique private double tokenlogin$countdown = AutoReconnect.RECONNECT_DELAY_TICKS;

    protected DisconnectedScreenMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void tokenlogin$onInit(CallbackInfo ci) {
        // If SelfBan was running, feed it the disconnect reason so it can
        // reconnect (or stop, if the reason looks like a ban).
        if (SelfBan.isEnabled()) {
            DisconnectionDetails info = ((DisconnectedScreenAccessor) this).tokenlogin$getInfo();
            String reason = (info == null || info.reason() == null) ? "" : info.reason().getString();
            SelfBan.handleDisconnect(reason);
        }

        if (AutoReconnect.getLastServer() == null) return;

        int btnW = 150, btnH = 20;
        int x = this.width - btnW - 4;

        tokenlogin$reconnectBtn = Button.builder(
                Component.literal(tokenlogin$reconnectText()),
                btn -> AutoReconnect.connect(this.minecraft)
        ).bounds(x, 4, btnW, btnH).build();

        tokenlogin$toggleBtn = Button.builder(
                Component.literal(tokenlogin$toggleText()),
                btn -> {
                    AutoReconnect.toggle();
                    tokenlogin$countdown = AutoReconnect.RECONNECT_DELAY_TICKS;
                    btn.setMessage(Component.literal(tokenlogin$toggleText()));
                    tokenlogin$reconnectBtn.setMessage(Component.literal(tokenlogin$reconnectText()));
                }
        ).bounds(x, 28, btnW, btnH).build();

        this.addRenderableWidget(tokenlogin$reconnectBtn);
        this.addRenderableWidget(tokenlogin$toggleBtn);
    }

    @Override
    public void tick() {
        super.tick();

        if (tokenlogin$reconnectBtn == null) return;

        if (AutoReconnect.isEnabled() && AutoReconnect.getLastServer() != null) {
            if (tokenlogin$countdown <= 0) {
                AutoReconnect.connect(this.minecraft);
            } else {
                tokenlogin$countdown--;
                tokenlogin$reconnectBtn.setMessage(Component.literal(tokenlogin$reconnectText()));
            }
        }
    }

    @Unique
    private String tokenlogin$reconnectText() {
        if (AutoReconnect.isEnabled() && tokenlogin$countdown > 0) {
            return String.format("Reconnect (%.1fs)", tokenlogin$countdown / 20.0);
        }
        return "Reconnect";
    }

    @Unique
    private String tokenlogin$toggleText() {
        return "AutoReconnect: " + (AutoReconnect.isEnabled() ? "ON" : "OFF");
    }
}
