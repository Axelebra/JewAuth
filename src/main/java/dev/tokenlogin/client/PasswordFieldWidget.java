package dev.tokenlogin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
public class PasswordFieldWidget extends EditBox {

    public PasswordFieldWidget(Font font, int x, int y, int width, int height, Component text) {
        super(font, x, y, width, height, text);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Swap real text for asterisks, extract, then restore
        String real = this.getValue();
        String masked = "*".repeat(real.length());
        super.setValue(masked);
        super.extractWidgetRenderState(context, mouseX, mouseY, delta);
        super.setValue(real);
    }
}
