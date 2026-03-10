package dev.tokenlogin.mixin;

import dev.tokenlogin.client.AntiKick;
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
 * Adds an AntiKick toggle button to the disconnect screen.
 * Button label shows status (ON/OFF, retry count).
 */
@Environment(EnvType.CLIENT)
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin extends Screen {

    @Unique private ButtonWidget tokenlogin$antikickButton;

    protected DisconnectedScreenMixin(Text title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void tokenlogin$onInit(CallbackInfo ci) {
        int btnW = 150;
        int btnH = 20;
        int x = this.width - btnW - 4;
        int y = 4;

        tokenlogin$antikickButton = ButtonWidget.builder(
                Text.literal(tokenlogin$getLabel()),
                btn -> {
                    AntiKick.toggle();
                    btn.setMessage(Text.literal(tokenlogin$getLabel()));
                }
        ).dimensions(x, y, btnW, btnH).build();

        this.addDrawableChild(tokenlogin$antikickButton);

        // If we landed on the disconnect screen while a reconnect was in progress,
        // and there's no reconnect already queued, that means it failed.
        // Schedule a retry automatically (handles rate limits, timeouts, anything).
        if (AntiKick.isEnabled() && AntiKick.isReconnectInProgress()
                && !AntiKick.hasPendingReconnect()) {
            AntiKick.scheduleRetry();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (tokenlogin$antikickButton != null) {
            tokenlogin$antikickButton.setMessage(Text.literal(tokenlogin$getLabel()));
        }
    }

    @Override
    public void removed() {
        super.removed();
        // User navigated away from disconnect screen (pressed Back, etc.)
        // Cancel any pending reconnect so it doesn't hijack the next connection.
        // But DON'T cancel if AntiKick itself is doing the reconnect.
        if (AntiKick.isReconnectInProgress() && !AntiKick.isConnectingNow()) {
            AntiKick.cancelReconnectState();
        }
    }

    @Unique
    private static String tokenlogin$getLabel() {
        if (!AntiKick.isEnabled()) return "AntiKick: OFF";
        if (AntiKick.hasPendingReconnect()) return "AntiKick: Reconnecting...";
        if (AntiKick.isReconnectInProgress()) return "AntiKick: Retry #" + AntiKick.getReconnectRetries();
        return "AntiKick: ON (blocked " + AntiKick.getBlockedCount() + ")";
    }
}
