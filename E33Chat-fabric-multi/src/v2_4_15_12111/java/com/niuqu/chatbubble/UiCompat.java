package com.niuqu.chatbubble;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.ClickEvent;

/** Small compatibility surface for Minecraft 1.21.11's GUI input and drawing. */
public final class UiCompat {
    private UiCompat() {}

    public static Click mouse(double x, double y, int button) {
        return new Click(x, y, new MouseInput(button, 0));
    }

    public static boolean click(Element widget, double x, double y, int button) {
        return widget.mouseClicked(mouse(x, y, button), false);
    }

    public static String clickValue(ClickEvent event) {
        if (event instanceof ClickEvent.SuggestCommand c) return c.command();
        if (event instanceof ClickEvent.RunCommand c) return c.command();
        if (event instanceof ClickEvent.OpenUrl c) return c.uri().toString();
        if (event instanceof ClickEvent.OpenFile c) return c.path();
        if (event instanceof ClickEvent.CopyToClipboard c) return c.value();
        return null;
    }

    public static void drawBorder(DrawContext g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }
}
