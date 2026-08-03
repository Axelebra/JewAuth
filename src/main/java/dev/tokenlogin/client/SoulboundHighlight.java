package dev.tokenlogin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * Paints a red overlay over any slot holding a player-soulbound item.
 * Always active; no toggle needed.
 *
 * Hypixel writes the marker into the item's lore as
 * {@code §8§l* §8Soulbound §8§l*}, which strips down to the plain string
 * {@code "* Soulbound *"}. Co-op items use {@code "* Co-op Soulbound *"} —
 * a plain {@code contains("Soulbound")} test would match those too, so the
 * comparison here is exact equality against the stripped line. Co-op items are
 * therefore excluded with no extra logic.
 */
@Environment(EnvType.CLIENT)
public final class SoulboundHighlight {

    /** Plain lore line marking a player-soulbound (NOT co-op) item. */
    private static final String SOULBOUND_LINE = "* Soulbound *";

    /** ARGB overlay tint — translucent red, drawn on top of the item icon. */
    private static final int OVERLAY_COLOR = 0x60FF2020;

    private SoulboundHighlight() {}

    /**
     * Draws the overlay for every soulbound slot in the container.
     *
     * @param left the screen's {@code leftPos} — slot coords are container-relative
     * @param top  the screen's {@code topPos}
     */
    public static void renderSlotOverlays(GuiGraphicsExtractor ctx, List<Slot> slots, int left, int top) {
        for (Slot slot : slots) {
            if (!slot.hasItem() || !slot.isActive()) continue;
            if (!isSoulbound(slot.getItem())) continue;

            int x = left + slot.x;
            int y = top  + slot.y;
            ctx.fill(x, y, x + 16, y + 16, OVERLAY_COLOR);
        }
    }

    /**
     * True only for player-soulbound items. Co-op soulbound returns false.
     */
    public static boolean isSoulbound(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;

        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;

        for (Component line : lore.lines()) {
            if (SOULBOUND_LINE.equals(plain(line))) return true;
        }
        return false;
    }

    /**
     * Visible text of a lore line with styling removed. Hypixel normally sends
     * styled components, but legacy {@code §} codes can survive inside the
     * literal text, so strip those as well before comparing.
     */
    private static String plain(Component line) {
        if (line == null) return "";
        String s = ChatFormatting.stripFormatting(line.getString());
        return s == null ? "" : s;
    }
}
