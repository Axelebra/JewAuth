package dev.tokenlogin.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Environment(EnvType.CLIENT)
@Mixin(Minecraft.class)
public interface MinecraftClientAccessor {

    @Mutable
    @Accessor("user")
    void tokenlogin$setSession(User session);
}
