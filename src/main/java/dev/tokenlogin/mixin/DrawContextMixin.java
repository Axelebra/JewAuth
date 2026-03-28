package dev.tokenlogin.mixin;

import dev.tokenlogin.client.LobbyAnonymiser;
import dev.tokenlogin.client.NickHider;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts ALL text rendering to replace the player's real name.
 *
 * Uses @Inject + cancel + re-call pattern with a ThreadLocal guard
 * to avoid infinite recursion. This is more reliable than @ModifyVariable
 * which has known issues with overloaded method resolution in some
 * mixin/loom versions.
 */
@Environment(EnvType.CLIENT)
@Mixin(DrawContext.class)
public abstract class DrawContextMixin {

    @Unique
    private static final ThreadLocal<Boolean> tokenlogin$reentrant =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    // ── Text overload ────────────────────────────────────────────────────
    @Inject(
            method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void tokenlogin$replaceText(TextRenderer textRenderer, Text text,
                                        int x, int y, int color, boolean shadow,
                                        CallbackInfo ci) {
        if (tokenlogin$reentrant.get()) return;
        if (text == null) return;

        Text out = NickHider.isEnabled() ? NickHider.replaceInText(text) : text;
        out = LobbyAnonymiser.replaceInText(out);
        if (out == text) return;

        ci.cancel();
        tokenlogin$reentrant.set(Boolean.TRUE);
        try {
            ((DrawContext) (Object) this).drawText(textRenderer, out, x, y, color, shadow);
        } finally {
            tokenlogin$reentrant.set(Boolean.FALSE);
        }
    }

    // ── OrderedText overload ─────────────────────────────────────────────
    @Inject(
            method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;IIIZ)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void tokenlogin$replaceOrdered(TextRenderer textRenderer, OrderedText text,
                                           int x, int y, int color, boolean shadow,
                                           CallbackInfo ci) {
        if (tokenlogin$reentrant.get()) return;
        if (text == null) return;

        OrderedText out = NickHider.isEnabled() ? NickHider.replaceInOrdered(text) : text;
        out = LobbyAnonymiser.replaceInOrdered(out);
        if (out == text) return;

        ci.cancel();
        tokenlogin$reentrant.set(Boolean.TRUE);
        try {
            ((DrawContext) (Object) this).drawText(textRenderer, out, x, y, color, shadow);
        } finally {
            tokenlogin$reentrant.set(Boolean.FALSE);
        }
    }

    // ── String overload (may not exist in all versions → require = 0) ───
    @Inject(
            method = "drawText(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;IIIZ)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void tokenlogin$replaceString(TextRenderer textRenderer, String text,
                                          int x, int y, int color, boolean shadow,
                                          CallbackInfo ci) {
        if (tokenlogin$reentrant.get()) return;
        if (text == null) return;

        String out = NickHider.isEnabled() ? NickHider.replaceInString(text) : text;
        out = LobbyAnonymiser.replaceInString(out);
        if (out == null || out.equals(text)) return;

        ci.cancel();
        tokenlogin$reentrant.set(Boolean.TRUE);
        try {
            ((DrawContext) (Object) this).drawText(textRenderer, out, x, y, color, shadow);
        } finally {
            tokenlogin$reentrant.set(Boolean.FALSE);
        }
    }
}