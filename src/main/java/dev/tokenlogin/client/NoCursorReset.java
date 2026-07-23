package dev.tokenlogin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * No Cursor Reset — always on, no toggle (like HoverLoot).
 *
 * When the game chains straight from one inventory/container screen into another
 * (e.g. clicking through Hypixel menus, which closes the old GUI and opens a new
 * one back-to-back), vanilla mouse unlock recenters the mouse to the middle of
 * the window. This keeps the cursor where it was so menu navigation stays put.
 *
 * The position is only restored when the next screen opens immediately after the
 * previous one closed (within {@link #CHAIN_WINDOW_MS}); a fresh inventory opened
 * from gameplay after a pause still centers as normal.
 */
@Environment(EnvType.CLIENT)
public class NoCursorReset {

    /** Max gap between closing one handled screen and opening the next to treat
     *  it as a server-driven menu chain (and thus preserve the cursor). */
    private static final long CHAIN_WINDOW_MS = 300L;

    private static double savedX  = -1.0;
    private static double savedY  = -1.0;
    private static long   savedAt = 0L;

    /** Called when a handled screen is removed — remember where the cursor was. */
    public static void save(Minecraft client) {
        if (client == null || client.mouseHandler == null) return;
        savedX  = client.mouseHandler.xpos();
        savedY  = client.mouseHandler.ypos();
        savedAt = System.currentTimeMillis();
    }

    /** Called when a handled screen finishes init — restore the cursor if this
     *  open chained straight off the previous close. */
    public static void restore(Minecraft client) {
        if (client == null || client.getWindow() == null) return;

        long when = savedAt;
        savedAt = 0L; // consume regardless, so it only applies to the next open
        if (when == 0L) return;
        if (System.currentTimeMillis() - when > CHAIN_WINDOW_MS) return;
        if (savedX < 0 || savedY < 0) return;

        GLFW.glfwSetCursorPos(client.getWindow().handle(), savedX, savedY);
    }
}
