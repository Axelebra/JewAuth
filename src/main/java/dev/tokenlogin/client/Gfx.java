package dev.tokenlogin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Small rendering shim for the 26.x GuiGraphicsExtractor API.
 *
 * The old {@code DrawContext.drawTextWithShadow(font, text, x, y, color)} was
 * replaced by {@code GuiGraphicsExtractor.text(font, text, x, y, color, shadow)}.
 * This keeps the call sites in the browser screens compact.
 */
@Environment(EnvType.CLIENT)
final class Gfx {
    private Gfx() {}

    static void textShadow(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color) {
        g.text(font, text, x, y, color, true);
    }

    static void centeredShadow(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color) {
        g.centeredText(font, text, x, y, color);
    }
}
