package cn.xingchen.e33.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Local-only support poster. The supplied QR artwork is never re-encoded or sent in chat. */
public final class CustomerSupportScreen extends Screen {
    private static final Identifier POSTER = Identifier.fromNamespaceAndPath("e33chat", "textures/xingchen/customer-support.png");
    private final Screen parent;

    public CustomerSupportScreen(Screen parent) {
        super(Component.literal("官方客服"));
        this.parent = parent;
    }

    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.literal("返回聊天"), button -> onClose())
                .bounds(width / 2 - 46, height - 23, 92, 18).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xF0091829);
        String hint = "用微信扫描寻找官方客服";
        g.text(font, hint, (width - font.width(hint)) / 2, 8, 0xFFFFD68B, false);
        int h = Math.max(1, Math.min(height - 54, (width - 24) * 4 / 3));
        int w = h * 3 / 4;
        int x = (width - w) / 2;
        g.blit(POSTER, x, 23, x + w, 23 + h, 0f, 1f, 0f, 1f);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
