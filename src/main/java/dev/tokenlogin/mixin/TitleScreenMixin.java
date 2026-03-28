package dev.tokenlogin.mixin;

import net.minecraft.client.gui.screen.SplashTextRenderer;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the vanilla splash text with our own so it inherits
 * the vanilla rotation, scale-bounce, and positioning.
 */
@Mixin(value = TitleScreen.class, priority = 2000)
public abstract class TitleScreenMixin {

    @Shadow @Nullable private SplashTextRenderer splashText;

    @Inject(method = "init", at = @At("TAIL"))
    private void tokenlogin$replaceSplash(CallbackInfo ci) {
        String msg = "discord.gg/jewbz";
        int blue = 0x0038B8;
        int white = 0xFFFFFF;
        MutableText text = Text.empty();
        for (int i = 0; i < msg.length(); i++) {
            int color = (i % 2 == 0) ? blue : white;
            int c = color;
            text.append(Text.literal(String.valueOf(msg.charAt(i))).styled(s -> s.withColor(c)));
        }
        this.splashText = new SplashTextRenderer(text);
    }
}
