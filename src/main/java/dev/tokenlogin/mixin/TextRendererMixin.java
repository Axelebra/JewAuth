package dev.tokenlogin.mixin;

import dev.tokenlogin.client.LobbyAnonymiser;
import dev.tokenlogin.client.NickHider;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts Font.width() so the scoreboard background is sized to match the
 * replaced text rather than the original. Without this, the scoreboard
 * background box is drawn at the original width BEFORE our DrawContextMixin
 * swaps in the replacement text.
 */
@Mixin(Font.class)
public abstract class TextRendererMixin {

    @Unique
    private static final ThreadLocal<Boolean> tokenlogin$reentrant =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    // FormattedText overload — covers Component (Text) and any FormattedText
    @Inject(method = "width(Lnet/minecraft/network/chat/FormattedText;)I", at = @At("HEAD"), cancellable = true, require = 0)
    private void tokenlogin$widthVisitable(FormattedText text, CallbackInfoReturnable<Integer> cir) {
        if (tokenlogin$reentrant.get() || !(text instanceof Component t)) return;
        Component out = NickHider.isEnabled() ? NickHider.replaceInText(t) : t;
        out = LobbyAnonymiser.replaceInText(out);
        if (out == t) return;
        tokenlogin$reentrant.set(true);
        try {
            cir.setReturnValue(((Font) (Object) this).width(out));
        } finally {
            tokenlogin$reentrant.set(false);
        }
    }

    @Inject(method = "width(Lnet/minecraft/util/FormattedCharSequence;)I", at = @At("HEAD"), cancellable = true)
    private void tokenlogin$widthOrdered(FormattedCharSequence text, CallbackInfoReturnable<Integer> cir) {
        if (tokenlogin$reentrant.get() || text == null) return;
        FormattedCharSequence out = NickHider.isEnabled() ? NickHider.replaceInOrdered(text) : text;
        out = LobbyAnonymiser.replaceInOrdered(out);
        if (out == text) return;
        tokenlogin$reentrant.set(true);
        try {
            cir.setReturnValue(((Font) (Object) this).width(out));
        } finally {
            tokenlogin$reentrant.set(false);
        }
    }

    @Inject(
            method = "width(Ljava/lang/String;)I",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void tokenlogin$widthString(String text, CallbackInfoReturnable<Integer> cir) {
        if (tokenlogin$reentrant.get() || text == null) return;
        String out = NickHider.isEnabled() ? NickHider.replaceInString(text) : text;
        out = LobbyAnonymiser.replaceInString(out);
        if (out == null || out.equals(text)) return;
        tokenlogin$reentrant.set(true);
        try {
            cir.setReturnValue(((Font) (Object) this).width(out));
        } finally {
            tokenlogin$reentrant.set(false);
        }
    }
}
