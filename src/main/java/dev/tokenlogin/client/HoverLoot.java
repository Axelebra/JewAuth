package dev.tokenlogin.client;

import dev.tokenlogin.mixin.HandledScreenAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
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

    private static AbstractContainerScreen<?> lastScreen = null;
    private static double lastMouseX = -1.0;
    private static double lastMouseY = -1.0;
    private static boolean wasActive = false;

    public static void tick(Minecraft client) {
        if (!(Screens.current(client) instanceof AbstractContainerScreen<?> screen)) {
            if (lastScreen != null) reset();
            return;
        }

        if (lastScreen != screen) {
            reset();
            lastScreen = screen;
        }

        long window = client.getWindow().handle();
        boolean shiftHeld = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT)  == GLFW.GLFW_PRESS
                         || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        boolean leftHeld  = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean active    = shiftHeld && leftHeld;

        // Convert raw mouse coords to GUI-scaled coords
        double mouseX = client.mouseHandler.getScaledXPos(client.getWindow());
        double mouseY = client.mouseHandler.getScaledYPos(client.getWindow());

        // Key released — clear queued set so next drag can re-process slots
        if (!active && wasActive) {
            currentlyQueued.clear();
            pendingSlots.clear();
        }
        wasActive = active;

        if (active) {
            for (Slot slot : getSlotsAlongPath(screen, lastMouseX, lastMouseY, mouseX, mouseY)) {
                if (slot == null || !slot.hasItem()) continue;
                if (currentlyQueued.contains(slot.index)) continue;
                pendingSlots.add(slot.index);
                currentlyQueued.add(slot.index);
            }
        }

        lastMouseX = mouseX;
        lastMouseY = mouseY;

        processQueue(client, screen);
    }

    private static void processQueue(Minecraft client, AbstractContainerScreen<?> screen) {
        if (pendingSlots.isEmpty()) return;
        if (client.gameMode == null || client.player == null) return;

        int syncId = screen.getMenu().containerId;
        int processed = 0;

        while (!pendingSlots.isEmpty() && processed < 20) {
            int slotId = pendingSlots.poll();
            currentlyQueued.remove(slotId);
            Slot slot = findSlotById(screen, slotId);
            if (slot != null && slot.hasItem()) {
                client.gameMode.handleContainerInput(syncId, slotId, 0, ContainerInput.QUICK_MOVE, client.player);
                processed++;
            }
        }
    }

    private static List<Slot> getSlotsAlongPath(AbstractContainerScreen<?> screen,
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
            if (s == null || seen.contains(s.index)) continue;
            slots.add(s);
            seen.add(s.index);
        }
        return slots;
    }

    private static Slot getSlotAt(AbstractContainerScreen<?> screen, double x, double y) {
        return ((HandledScreenAccessor)(Object) screen).invokeGetSlotAt(x, y);
    }

    private static Slot findSlotById(AbstractContainerScreen<?> screen, int slotId) {
        var slots = screen.getMenu().slots;
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
