package dev.tokenlogin.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes AbstractContainerScreen's private slot-under-cursor lookup. */
@Mixin(AbstractContainerScreen.class)
public interface HandledScreenAccessor {
    @Invoker("getHoveredSlot")
    @Nullable Slot invokeGetSlotAt(double x, double y);
}
