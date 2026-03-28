package dev.tokenlogin.mixin;

import dev.tokenlogin.client.AutoReconnect;
import dev.tokenlogin.client.SelfBan;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
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

    @Unique private ButtonWidget tokenlogin$reconnectBtn;
    @Unique private ButtonWidget tokenlogin$toggleBtn;
    @Unique private double tokenlogin$countdown = AutoReconnect.RECONNECT_DELAY_TICKS;

    protected DisconnectedScreenMixin(Text title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void tokenlogin$onInit(CallbackInfo ci) {
        // If SelfBan was running, turn it off
        if (SelfBan.isEnabled()) SelfBan.disable();

        if (AutoReconnect.getLastServer() == null) return;

        int btnW = 150, btnH = 20;
        int x = this.width - btnW - 4;

        tokenlogin$reconnectBtn = ButtonWidget.builder(
                Text.literal(tokenlogin$reconnectText()),
                btn -> AutoReconnect.connect(this.client)
        ).dimensions(x, 4, btnW, btnH).build();

        tokenlogin$toggleBtn = ButtonWidget.builder(
                Text.literal(tokenlogin$toggleText()),
                btn -> {
                    AutoReconnect.toggle();
                    tokenlogin$countdown = AutoReconnect.RECONNECT_DELAY_TICKS;
                    btn.setMessage(Text.literal(tokenlogin$toggleText()));
                    tokenlogin$reconnectBtn.setMessage(Text.literal(tokenlogin$reconnectText()));
                }
        ).dimensions(x, 28, btnW, btnH).build();

        this.addDrawableChild(tokenlogin$reconnectBtn);
        this.addDrawableChild(tokenlogin$toggleBtn);
    }

    @Override
    public void tick() {
        super.tick();

        if (tokenlogin$reconnectBtn == null) return;

        if (AutoReconnect.isEnabled() && AutoReconnect.getLastServer() != null) {
            if (tokenlogin$countdown <= 0) {
                AutoReconnect.connect(this.client);
            } else {
                tokenlogin$countdown--;
                tokenlogin$reconnectBtn.setMessage(Text.literal(tokenlogin$reconnectText()));
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
