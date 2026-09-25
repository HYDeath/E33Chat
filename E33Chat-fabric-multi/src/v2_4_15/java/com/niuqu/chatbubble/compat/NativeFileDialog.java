package com.niuqu.chatbubble.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Native image picker shared by the panel background and local emotes.
 */
public final class NativeFileDialog {
    private static final AtomicBoolean OPEN = new AtomicBoolean();

    private NativeFileDialog() {}

    /** Opens a modal image picker; the result (or null on cancel) is delivered on the render thread. */
    public static void pickImage(Consumer<File> callback) {
        if (!OPEN.compareAndSet(false, true)) return;
        KeyBinding.unpressAll();
        MinecraftClient mc = MinecraftClient.getInstance();
        // MC keeps thinking the button is held while the dialog grabs input;
        // clear it so release state restores cleanly after the dialog closes
        if (mc.mouse != null) ((com.niuqu.chatbubble.mixin.MouseHandlerAccessor) mc.mouse).e33chat$setActiveButton(null);

        Thread t = new Thread(() -> {
            File picked = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer patterns = stack.mallocPointer(4);
                patterns.put(stack.UTF8("*.png"));
                patterns.put(stack.UTF8("*.jpg"));
                patterns.put(stack.UTF8("*.jpeg"));
                patterns.put(stack.UTF8("*.gif"));
                patterns.flip();
                String path = TinyFileDialogs.tinyfd_openFileDialog(
                    "Select image", null, patterns, "Image files (PNG, JPG, GIF)", false);
                if (path != null) {
                    File file = new File(path);
                    String name = file.getName().toLowerCase(Locale.ROOT);
                    if (file.isFile() && (name.endsWith(".png") || name.endsWith(".jpg")
                            || name.endsWith(".jpeg") || name.endsWith(".gif"))) picked = file;
                }
            } catch (Throwable e) {
                com.mojang.logging.LogUtils.getLogger().warn("[e33chat] Image picker failed", e);
            } finally {
                OPEN.set(false);
            }
            File result = picked;
            mc.execute(() -> callback.accept(result));
        }, "e33chat-image-picker");
        t.setDaemon(true);
        t.start();
    }
}
