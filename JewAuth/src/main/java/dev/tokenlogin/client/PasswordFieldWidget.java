package dev.tokenlogin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public class PasswordFieldWidget extends TextFieldWidget {

    public PasswordFieldWidget(TextRenderer textRenderer, int x, int y, int width, int height, Text text) {
        super(textRenderer, x, y, width, height, text);
    }

    @Override
    public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        // Swap real text for asterisks, render, then restore
        String real = this.getText();
        String masked = "*".repeat(real.length());
        super.setText(masked);
        super.renderWidget(context, mouseX, mouseY, delta);
        super.setText(real);
    }
}