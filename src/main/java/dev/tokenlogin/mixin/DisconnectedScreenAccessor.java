package dev.tokenlogin.mixin;

import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the private {@code details} field on {@link DisconnectedScreen} so the
 * disconnect reason can be read for {@link dev.tokenlogin.client.SelfBan}'s
 * reconnect-until-banned loop.
 */
@Mixin(DisconnectedScreen.class)
public interface DisconnectedScreenAccessor {
    @Accessor("details")
    DisconnectionDetails tokenlogin$getInfo();
}
