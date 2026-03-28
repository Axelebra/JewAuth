package dev.tokenlogin.mixin;

import dev.tokenlogin.client.LobbyAnonymiser;
import dev.tokenlogin.client.NickHider;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts TextRenderer.getWidth() so the scoreboard background is sized
 * to match the replaced text rather than the original. Without this, the
 * scoreboard background box is drawn at the original width BEFORE our
 * DrawContextMixin swaps in the replacement text.
 */
@Mixin(TextRenderer.class)
public abstract class TextRendererMixin {

    @Unique
    private static final ThreadLocal<Boolean> tokenlogin$reentrant =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    // Text overload — may not exist in all versions, so require = 0
    @Inject(method = "getWidth(Lnet/minecraft/text/Text;)I", at = @At("HEAD"), cancellable = true, require = 0)
    private void tokenlogin$widthText(Text text, CallbackInfoReturnable<Integer> cir) {
        if (tokenlogin$reentrant.get() || text == null) return;
        Text out = NickHider.isEnabled() ? NickHider.replaceInText(text) : text;
        out = LobbyAnonymiser.replaceInText(out);
        if (out == text) return;
        tokenlogin$reentrant.set(true);
        try {
            cir.setReturnValue(((TextRenderer) (Object) this).getWidth(out));
        } finally {
            tokenlogin$reentrant.set(false);
        }
    }

    // StringVisitable overload — the actual signature in 1.21.x
    @Inject(method = "getWidth(Lnet/minecraft/text/StringVisitable;)I", at = @At("HEAD"), cancellable = true, require = 0)
    private void tokenlogin$widthVisitable(StringVisitable text, CallbackInfoReturnable<Integer> cir) {
        if (tokenlogin$reentrant.get() || !(text instanceof Text t)) return;
        Text out = NickHider.isEnabled() ? NickHider.replaceInText(t) : t;
        out = LobbyAnonymiser.replaceInText(out);
        if (out == t) return;
        tokenlogin$reentrant.set(true);
        try {
            cir.setReturnValue(((TextRenderer) (Object) this).getWidth(out));
        } finally {
            tokenlogin$reentrant.set(false);
        }
    }

    @Inject(method = "getWidth(Lnet/minecraft/text/OrderedText;)I", at = @At("HEAD"), cancellable = true)
    private void tokenlogin$widthOrdered(OrderedText text, CallbackInfoReturnable<Integer> cir) {
        if (tokenlogin$reentrant.get() || text == null) return;
        OrderedText out = NickHider.isEnabled() ? NickHider.replaceInOrdered(text) : text;
        out = LobbyAnonymiser.replaceInOrdered(out);
        if (out == text) return;
        tokenlogin$reentrant.set(true);
        try {
            cir.setReturnValue(((TextRenderer) (Object) this).getWidth(out));
        } finally {
            tokenlogin$reentrant.set(false);
        }
    }

    @Inject(
            method = "getWidth(Ljava/lang/String;)I",
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
            cir.setReturnValue(((TextRenderer) (Object) this).getWidth(out));
        } finally {
            tokenlogin$reentrant.set(false);
        }
    }
}
