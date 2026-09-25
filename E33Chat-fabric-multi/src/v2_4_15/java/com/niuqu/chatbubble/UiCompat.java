package com.niuqu.chatbubble;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.ClickEvent;

/** 26.x input records used by the ported 2.4.15 screens. */
public final class UiCompat {
    private UiCompat() {}

    public static MouseButtonEvent mouse(double x, double y, int button) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
    }

    public static boolean click(GuiEventListener widget, double x, double y, int button) {
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
}
