package dev.tokenlogin.mixin;

import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLPixels;
import org.lwjgl.sdl.SDLSurface;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Surface;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

@Mixin(Minecraft.class)
public class WindowTitleMixin {

    @Unique
    private static boolean iconSet = false;

    @Inject(method = "updateTitle", at = @At("HEAD"), cancellable = true)
    private void overrideWindowTitle(CallbackInfo ci) {
        long windowHandle = Minecraft.getInstance().getWindow().handle();
        Minecraft.getInstance().getWindow().setTitle("JewAuth");

        if (!iconSet) {
            iconSet = true;
            jewauth$setWindowIcon(windowHandle);
        }

        ci.cancel();
    }

    @Unique
    private static void jewauth$setWindowIcon(long windowHandle) {
        // Try multiple paths to find the icon
        String[] paths = {
                "/assets/jewauth/textures/gui/Jew.png",
                "/assets/jewauth/textures/gui/jew.png",
                "/Jew.png",
                "/jew.png"
        };

        InputStream stream = null;
        String foundPath = null;
        for (String path : paths) {
            stream = WindowTitleMixin.class.getResourceAsStream(path);
            if (stream != null) {
                foundPath = path;
                break;
            }
        }

        if (stream == null) {
            System.err.println("[JewAuth] ERROR: Could not find icon at any of these paths:");
            for (String path : paths) {
                System.err.println("[JewAuth]   - " + path);
            }
            return;
        }

        try {
            byte[] bytes = stream.readAllBytes();
            stream.close();
            System.out.println("[JewAuth] Found icon at: " + foundPath + " (" + bytes.length + " bytes)");

            ByteBuffer pngBuffer = MemoryUtil.memAlloc(bytes.length);
            pngBuffer.put(bytes);
            pngBuffer.flip();

            IntBuffer w = MemoryUtil.memAllocInt(1);
            IntBuffer h = MemoryUtil.memAllocInt(1);
            IntBuffer comp = MemoryUtil.memAllocInt(1);

            ByteBuffer pixels = STBImage.stbi_load_from_memory(pngBuffer, w, h, comp, 4);
            MemoryUtil.memFree(pngBuffer);

            if (pixels == null) {
                System.err.println("[JewAuth] STBImage decode failed: " + STBImage.stbi_failure_reason());
                MemoryUtil.memFree(w);
                MemoryUtil.memFree(h);
                MemoryUtil.memFree(comp);
                return;
            }

            int width = w.get(0);
            int height = h.get(0);
            System.out.println("[JewAuth] Decoded icon: " + width + "x" + height);

            MemoryUtil.memFree(w);
            MemoryUtil.memFree(h);
            MemoryUtil.memFree(comp);

            // SDL3 takes one surface rather than GLFW's image array. STB hands
            // back RGBA bytes, which on a little-endian host is ABGR8888.
            SDL_Surface surface = SDLSurface.SDL_CreateSurfaceFrom(
                    width, height, SDLPixels.SDL_PIXELFORMAT_ABGR8888, pixels, width * 4);
            if (surface == null) {
                System.err.println("[JewAuth] SDL_CreateSurfaceFrom failed; icon not set");
            } else {
                SDLVideo.SDL_SetWindowIcon(windowHandle, surface);
                SDLSurface.SDL_DestroySurface(surface);
            }

            STBImage.stbi_image_free(pixels);

            System.out.println("[JewAuth] Window icon set successfully!");
        } catch (Exception e) {
            System.err.println("[JewAuth] Exception setting icon:");
            e.printStackTrace();
        }
    }
}