package dev.tokenlogin.mixin;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
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

@Mixin(MinecraftClient.class)
public class WindowTitleMixin {

    @Unique
    private static boolean iconSet = false;

    @Inject(method = "updateWindowTitle", at = @At("HEAD"), cancellable = true)
    private void overrideWindowTitle(CallbackInfo ci) {
        long windowHandle = MinecraftClient.getInstance().getWindow().getHandle();
        GLFW.glfwSetWindowTitle(windowHandle, "JewAuth");

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

            GLFWImage image = GLFWImage.malloc();
            image.set(width, height, pixels);

            GLFWImage.Buffer icons = GLFWImage.malloc(1);
            icons.put(0, image);

            GLFW.glfwSetWindowIcon(windowHandle, icons);

            STBImage.stbi_image_free(pixels);
            image.free();
            icons.free();

            System.out.println("[JewAuth] Window icon set successfully!");
        } catch (Exception e) {
            System.err.println("[JewAuth] Exception setting icon:");
            e.printStackTrace();
        }
    }
}