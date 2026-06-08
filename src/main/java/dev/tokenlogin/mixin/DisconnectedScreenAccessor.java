package dev.tokenlogin.mixin;

import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.network.DisconnectionInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the private {@code info} field on {@link DisconnectedScreen} so the
 * disconnect reason can be read for {@link dev.tokenlogin.client.SelfBan}'s
 * reconnect-until-banned loop.
 */
@Mixin(DisconnectedScreen.class)
public interface DisconnectedScreenAccessor {
    @Accessor("info")
    DisconnectionInfo tokenlogin$getInfo();
}
