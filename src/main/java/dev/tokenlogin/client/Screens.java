package dev.tokenlogin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Cross-version accessor for the current screen.
 *
 * The one place 26.1.2 and 26.2 diverge: in 26.1.2 the active screen is the
 * public field {@code Minecraft.screen}; in 26.2 it moved to {@code Gui.screen()}.
 * We resolve whichever exists reflectively (cached) so a single build runs on
 * both drops.
 */
@Environment(EnvType.CLIENT)
public final class Screens {
    private Screens() {}

    private static boolean triedField;
    private static boolean triedMethod;
    private static Field  screenField;      // 26.1.x: Minecraft.screen
    private static Method guiScreenMethod;  // 26.2+ : Gui.screen()

    public static Screen current(Minecraft mc) {
        if (mc == null) return null;

        // 26.1.x — Minecraft.screen field
        try {
            if (!triedField) {
                triedField = true;
                try { screenField = Minecraft.class.getField("screen"); }
                catch (NoSuchFieldException ignored) {}
            }
            if (screenField != null) return (Screen) screenField.get(mc);
        } catch (Exception ignored) {}

        // 26.2+ — Gui.screen() method
        try {
            if (!triedMethod) {
                triedMethod = true;
                try { guiScreenMethod = mc.gui.getClass().getMethod("screen"); }
                catch (NoSuchMethodException ignored) {}
            }
            if (guiScreenMethod != null) return (Screen) guiScreenMethod.invoke(mc.gui);
        } catch (Exception ignored) {}

        return null;
    }
}
