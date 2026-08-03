package dev.tokenlogin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;

/**
 * No Cursor Reset — always on, no toggle (like HoverLoot).
 *
 * When the game chains straight from one inventory/container screen into another
 * (e.g. clicking through Hypixel menus, which closes the old GUI and opens a new
 * one back-to-back), vanilla mouse unlock recenters the mouse to the middle of
 * the window. This keeps the cursor where it was so menu navigation stays put.
 *
 * The saved position is held across the gap between one screen closing and the
 * next opening. During that gap the client has no screen and re-grabs the mouse,
 * so it looks identical to normal play — the two can only be told apart by how
 * long it lasts. The position is therefore dropped after
 * {@link #GAMEPLAY_RESET_TICKS} consecutive in-world ticks, which is long enough
 * to cover a laggy server sending the next menu but short enough that an
 * inventory opened during real gameplay still centers as normal.
 */
@Environment(EnvType.CLIENT)
public class NoCursorReset {

    /** Consecutive in-world ticks (mouse grabbed, no screen) before the saved
     *  cursor position is dropped. Covers slow server-driven menu chains. */
    private static final int GAMEPLAY_RESET_TICKS = 60; // ~3s

    private static double savedX = -1.0;
    private static double savedY = -1.0;
    private static boolean hasSaved = false;

    private static int gameplayTicks = 0;

    /** Called when a handled screen is removed — remember where the cursor was. */
    public static void save(Minecraft client) {
        if (client == null || client.mouseHandler == null) return;
        savedX = client.mouseHandler.xpos();
        savedY = client.mouseHandler.ypos();
        hasSaved = true;
        gameplayTicks = 0;
    }

    /**
     * Called every client tick. Counts how long the player has been back in the
     * world; once that passes the threshold the stored position is forgotten so
     * the next inventory opens centered.
     */
    public static void tick(Minecraft client) {
        if (!hasSaved) return;
        if (client == null || client.mouseHandler == null) return;

        // In a menu (or transitioning between two) the mouse is ungrabbed —
        // hold the saved position for however long that takes.
        if (Screens.current(client) != null || !client.mouseHandler.isMouseGrabbed()) {
            gameplayTicks = 0;
            return;
        }

        if (++gameplayTicks >= GAMEPLAY_RESET_TICKS) {
            hasSaved = false;
            gameplayTicks = 0;
        }
    }

    /** Called when a handled screen finishes init — put the cursor back. */
    public static void restore(Minecraft client) {
        if (client == null || !hasSaved) return;

        Window window = client.getWindow();
        if (window == null) return;
        if (savedX < 0 || savedY < 0) return;

        // The window may have been resized while we held the position.
        double x = clamp(savedX, window.getScreenWidth());
        double y = clamp(savedY, window.getScreenHeight());

        GLFW.glfwSetCursorPos(window.handle(), x, y);
        gameplayTicks = 0;
    }

    private static double clamp(double value, int size) {
        if (size <= 0) return value;
        return Math.max(0.0, Math.min(value, size - 1.0));
    }
}
