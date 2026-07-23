package dev.tokenlogin.mixin;

import dev.tokenlogin.client.LobbyAnonymiser;
import dev.tokenlogin.client.NickHider;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts ALL text rendering to replace the player's real name.
 *
 * In 26.x the immediate-mode GuiGraphics API was replaced by the
 * render-state extraction system: GuiGraphicsExtractor.text(...). We intercept
 * every text() overload (with and without the shadow flag) using the
 * @Inject + cancel + re-call pattern with a ThreadLocal guard to avoid
 * infinite recursion.
 */
@Environment(EnvType.CLIENT)
@Mixin(GuiGraphicsExtractor.class)
public abstract class DrawContextMixin {

    @Unique
    private static final ThreadLocal<Boolean> tokenlogin$reentrant =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    // ── Component overloads ──────────────────────────────────────────────
    @Inject(
            method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void tokenlogin$replaceTextShadow(Font font, Component text,
                                              int x, int y, int color, boolean shadow,
                                              CallbackInfo ci) {
        if (tokenlogin$reentrant.get() || text == null) return;
        Component out = NickHider.isEnabled() ? NickHider.replaceInText(text) : text;
        out = LobbyAnonymiser.replaceInText(out);
        if (out == text) return;

        ci.cancel();
        tokenlogin$reentrant.set(Boolean.TRUE);
        try {
            ((GuiGraphicsExtractor) (Object) this).text(font, out, x, y, color, shadow);
        } finally {
            tokenlogin$reentrant.set(Boolean.FALSE);
        }
    }

    @Inject(
            method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void tokenlogin$replaceText(Font font, Component text,
                                        int x, int y, int color,
                                        CallbackInfo ci) {
        if (tokenlogin$reentrant.get() || text == null) return;
        Component out = NickHider.isEnabled() ? NickHider.replaceInText(text) : text;
        out = LobbyAnonymiser.replaceInText(out);
        if (out == text) return;

        ci.cancel();
        tokenlogin$reentrant.set(Boolean.TRUE);
        try {
            ((GuiGraphicsExtractor) (Object) this).text(font, out, x, y, color);
        } finally {
            tokenlogin$reentrant.set(Boolean.FALSE);
        }
    }

    // ── FormattedCharSequence overloads ──────────────────────────────────
    @Inject(
            method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void tokenlogin$replaceOrderedShadow(Font font, FormattedCharSequence text,
                                                 int x, int y, int color, boolean shadow,
                                                 CallbackInfo ci) {
        if (tokenlogin$reentrant.get() || text == null) return;
        FormattedCharSequence out = NickHider.isEnabled() ? NickHider.replaceInOrdered(text) : text;
        out = LobbyAnonymiser.replaceInOrdered(out);
        if (out == text) return;

        ci.cancel();
        tokenlogin$reentrant.set(Boolean.TRUE);
        try {
            ((GuiGraphicsExtractor) (Object) this).text(font, out, x, y, color, shadow);
        } finally {
            tokenlogin$reentrant.set(Boolean.FALSE);
        }
    }

    @Inject(
            method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void tokenlogin$replaceOrdered(Font font, FormattedCharSequence text,
                                           int x, int y, int color,
                                           CallbackInfo ci) {
        if (tokenlogin$reentrant.get() || text == null) return;
        FormattedCharSequence out = NickHider.isEnabled() ? NickHider.replaceInOrdered(text) : text;
        out = LobbyAnonymiser.replaceInOrdered(out);
        if (out == text) return;

        ci.cancel();
        tokenlogin$reentrant.set(Boolean.TRUE);
        try {
            ((GuiGraphicsExtractor) (Object) this).text(font, out, x, y, color);
        } finally {
            tokenlogin$reentrant.set(Boolean.FALSE);
        }
    }

    // ── String overloads ─────────────────────────────────────────────────
    @Inject(
            method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void tokenlogin$replaceStringShadow(Font font, String text,
                                                int x, int y, int color, boolean shadow,
                                                CallbackInfo ci) {
        if (tokenlogin$reentrant.get() || text == null) return;
        String out = NickHider.isEnabled() ? NickHider.replaceInString(text) : text;
        out = LobbyAnonymiser.replaceInString(out);
        if (out == null || out.equals(text)) return;

        ci.cancel();
        tokenlogin$reentrant.set(Boolean.TRUE);
        try {
            ((GuiGraphicsExtractor) (Object) this).text(font, out, x, y, color, shadow);
        } finally {
            tokenlogin$reentrant.set(Boolean.FALSE);
        }
    }

    @Inject(
            method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void tokenlogin$replaceString(Font font, String text,
                                          int x, int y, int color,
                                          CallbackInfo ci) {
        if (tokenlogin$reentrant.get() || text == null) return;
        String out = NickHider.isEnabled() ? NickHider.replaceInString(text) : text;
        out = LobbyAnonymiser.replaceInString(out);
        if (out == null || out.equals(text)) return;

        ci.cancel();
        tokenlogin$reentrant.set(Boolean.TRUE);
        try {
            ((GuiGraphicsExtractor) (Object) this).text(font, out, x, y, color);
        } finally {
            tokenlogin$reentrant.set(Boolean.FALSE);
        }
    }
}
