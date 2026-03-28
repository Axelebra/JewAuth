package dev.tokenlogin.client;

import dev.tokenlogin.mixin.HandledScreenAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Hover-loot — shift+left-drag over slots to shift-click them all.
 * Always active; no toggle needed. Ported from simple-loot mod.
 */
@Environment(EnvType.CLIENT)
public class HoverLoot {

    private static final Set<Integer>   currentlyQueued = new HashSet<>();
    private static final Queue<Integer> pendingSlots    = new LinkedList<>();

    private static HandledScreen<?> lastScreen = null;
    private static double lastMouseX = -1.0;
    private static double lastMouseY = -1.0;
    private static boolean wasActive = false;

    public static void tick(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
            if (lastScreen != null) reset();
            return;
        }

        if (lastScreen != screen) {
            reset();
            lastScreen = screen;
        }

        long window = client.getWindow().getHandle();
        boolean shiftHeld = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT)  == GLFW.GLFW_PRESS
                         || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        boolean leftHeld  = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean active    = shiftHeld && leftHeld;

        // Convert raw mouse coords to GUI-scaled coords
        double scaleX = (double) client.getWindow().getScaledWidth()  / client.getWindow().getWidth();
        double scaleY = (double) client.getWindow().getScaledHeight() / client.getWindow().getHeight();
        double mouseX = client.mouse.getX() * scaleX;
        double mouseY = client.mouse.getY() * scaleY;

        // Key released — clear queued set so next drag can re-process slots
        if (!active && wasActive) {
            currentlyQueued.clear();
            pendingSlots.clear();
        }
        wasActive = active;

        if (active) {
            for (Slot slot : getSlotsAlongPath(screen, lastMouseX, lastMouseY, mouseX, mouseY)) {
                if (slot == null || !slot.hasStack()) continue;
                if (currentlyQueued.contains(slot.id)) continue;
                pendingSlots.add(slot.id);
                currentlyQueued.add(slot.id);
            }
        }

        lastMouseX = mouseX;
        lastMouseY = mouseY;

        processQueue(client, screen);
    }

    private static void processQueue(MinecraftClient client, HandledScreen<?> screen) {
        if (pendingSlots.isEmpty()) return;
        if (client.interactionManager == null || client.player == null) return;

        int syncId = screen.getScreenHandler().syncId;
        int processed = 0;

        while (!pendingSlots.isEmpty() && processed < 20) {
            int slotId = pendingSlots.poll();
            currentlyQueued.remove(slotId);
            Slot slot = findSlotById(screen, slotId);
            if (slot != null && slot.hasStack()) {
                client.interactionManager.clickSlot(syncId, slotId, 0, SlotActionType.QUICK_MOVE, client.player);
                processed++;
            }
        }
    }

    private static List<Slot> getSlotsAlongPath(HandledScreen<?> screen,
                                                 double fromX, double fromY,
                                                 double toX,   double toY) {
        List<Slot>   slots   = new ArrayList<>();
        Set<Integer> seen    = new HashSet<>();

        if (fromX < 0 || fromY < 0) {
            Slot s = getSlotAt(screen, toX, toY);
            if (s != null) slots.add(s);
            return slots;
        }

        double dx       = toX - fromX;
        double dy       = toY - fromY;
        double distance = Math.sqrt(dx * dx + dy * dy);
        int    samples  = Math.max(1, (int)(distance / 2.0));

        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            Slot s = getSlotAt(screen, fromX + dx * t, fromY + dy * t);
            if (s == null || seen.contains(s.id)) continue;
            slots.add(s);
            seen.add(s.id);
        }
        return slots;
    }

    private static Slot getSlotAt(HandledScreen<?> screen, double x, double y) {
        return ((HandledScreenAccessor)(Object) screen).invokeGetSlotAt(x, y);
    }

    private static Slot findSlotById(HandledScreen<?> screen, int slotId) {
        var slots = screen.getScreenHandler().slots;
        if (slotId >= 0 && slotId < slots.size()) return slots.get(slotId);
        return null;
    }

    private static void reset() {
        currentlyQueued.clear();
        pendingSlots.clear();
        lastScreen  = null;
        lastMouseX  = -1.0;
        lastMouseY  = -1.0;
        wasActive   = false;
    }
}
