package dev.tokenlogin.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes HandledScreen's protected getSlotAt method. */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Invoker("getSlotAt")
    @Nullable Slot invokeGetSlotAt(double x, double y);
}
