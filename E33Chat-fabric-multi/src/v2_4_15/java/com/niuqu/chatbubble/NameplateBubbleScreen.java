//#if MC >= 26000
package com.niuqu.chatbubble;

import com.niuqu.chatbubble.network.BubbleActionPayload;
import com.niuqu.chatbubble.network.BubbleCatalogPayload;
import com.niuqu.chatbubble.store.HistoryStore;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import java.util.ArrayList;
import java.util.List;

/** Visual Custom-Nameplates bubble catalogue; equip is verified by the server. */
public final class NameplateBubbleScreen extends Screen {
    //#if MC >= 260300
    //$$ @Override
    //$$ public void setFocused(Element next) {
    //$$     Element previous = getFocused();
    //$$     if (previous == next) return;
    //$$     if (client != null && previous != null)
    //$$         client.textInputManager().onTextInputFocusChange(previous, false);
    //$$     super.setFocused(next);
    //$$     if (client != null && next != null)
    //$$         client.textInputManager().onTextInputFocusChange(next, true);
    //$$ }
    //#endif

    private static final int ROW_H = 44;
    private static final List<BubbleCatalogPayload.Entry> catalogue = new ArrayList<>();
    private static boolean available;
    private static boolean loading;
    private static boolean received;
    private static boolean timedOut;
    private static String selected = "none";
    private static String selectedSkinSpec = "";
    private final Screen parent;
    private int scroll;
    private long requestStartedAt;
    private static final long REQUEST_TIMEOUT_MS = 5000;

    public NameplateBubbleScreen(Screen parent) {
        super(Text.literal("头顶气泡"));
        this.parent = parent;
    }

    public static void acceptCatalog(BubbleCatalogPayload page) {
        if (page.first()) {
            catalogue.clear();
            selectedSkinSpec = "";
            received = true;
            timedOut = false;
            available = page.available();
        }
        if (!received) return;
        catalogue.addAll(page.entries());
        selected = page.selected();
        loading = !page.last();
        if (page.last()) {
            selectedSkinSpec = "";
            if (available && !"none".equals(selected)) {
                for (BubbleCatalogPayload.Entry entry : catalogue) {
                    if (!entry.id().equals(selected)) continue;
                    selectedSkinSpec = entry.skinSpec();
                    break;
                }
            }
        }
    }

    /** Native CustomNameplates glyphs for the selected style and line count. */
    static NameplateBubbleSkin selectedSkin(int lines) {
        return NameplateBubbleSkin.parse(selectedSkinSpec, lines);
    }

    static boolean hasSelectedSkin() { return !selectedSkinSpec.isEmpty(); }

    public static void clearCatalog() {
        catalogue.clear();
        selectedSkinSpec = "";
        selected = "none";
        available = false;
        received = false;
        loading = false;
        timedOut = false;
    }

    private void refresh() {
        catalogue.clear();
        selectedSkinSpec = "";
        received = false;
        timedOut = false;
        loading = true;
        requestStartedAt = System.currentTimeMillis();
        scroll = 0;
        if (!ClientPlayNetworking.canSend(BubbleActionPayload.ID)) {
            loading = false;
            timedOut = true;
            return;
        }
        ClientPlayNetworking.send(new BubbleActionPayload(0, ""));
    }

    @Override
    protected void init() {
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Text.literal("返回聊天"),
            button -> client.setScreen(parent)).bounds(width / 2 - 104, height - 30, 100, 20).build());
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Text.literal("刷新列表"),
            button -> refresh()).bounds(width / 2 + 4, height - 30, 100, 20).build());
        refresh();
    }

    private int left() { return Math.max(18, width / 2 - Math.min(238, (width - 36) / 2)); }
    private int right() { return width - left(); }
    private int top() { return 47; }
    private int bottom() { return height - 41; }
    private int maxScroll() { return Math.max(0, catalogue.size() * ROW_H - (bottom() - top())); }

    //#if MC >= 26000
    public void extractRenderState(DrawContext g, int mouseX, int mouseY, float tickDelta) {
        if (loading && System.currentTimeMillis() - requestStartedAt >= REQUEST_TIMEOUT_MS) {
            loading = false;
            timedOut = true;
        }
        RenderHelper.fill(g, 0, 0, width, height, 0xEE17191E);
        RenderHelper.drawText(g, textRenderer, title,
            width / 2 - textRenderer.getWidth(title) / 2, 14, 0xFFFFFFFF, false);
        RenderHelper.drawText(g, textRenderer,
            "服务器气泡 · 灰色为未获得权限 · 点击可用款式直接装备",
            left(), 31, 0xFFBFC4CF, false);
        RenderHelper.fill(g, left() - 4, top() - 3, right() + 4, bottom() + 3, 0xBB24272F);
        if (!received && loading) {
            RenderHelper.drawText(g, textRenderer, "正在读取服务器气泡…", left() + 8, top() + 10, 0xFFFFFFFF, false);
        } else if (timedOut) {
            RenderHelper.drawText(g, textRenderer,
                "服务器未返回气泡列表，请检查 TrChat 插件后重试",
                left() + 8, top() + 10, 0xFFFFC38A, false);
        } else if (!available) {
            RenderHelper.drawText(g, textRenderer,
                "当前服务器未启用 Custom-Nameplates 气泡", left() + 8, top() + 10, 0xFFBFC4CF, false);
        } else {
            RenderHelper.enableScissor(g, left(), top(), right(), bottom());
            for (int i = 0; i < catalogue.size(); i++) {
                BubbleCatalogPayload.Entry entry = catalogue.get(i);
                int y = top() + i * ROW_H - scroll;
                if (y + ROW_H <= top() || y >= bottom()) continue;
                boolean equipped = entry.id().equals(selected);
                boolean hover = mouseX >= left() && mouseX < right()
                    && mouseY >= y && mouseY < y + ROW_H;
                int bg = equipped ? 0xAA29503D : hover && entry.unlocked() ? 0xAA393E4B : 0xAA2B2E35;
                RenderHelper.fill(g, left() + 2, y + 1, right() - 2, y + ROW_H - 2, bg);
                int color = entry.unlocked() ? 0xFFF0F1F5 : 0xFF777A83;
                String label = textRenderer.trimToWidth(entry.label(), right() - left() - 96);
                RenderHelper.drawText(g, textRenderer, label, left() + 11, y + 6, color, false);
                String state = equipped ? "已使用" : entry.unlocked() ? "可选择" : "未获得";
                RenderHelper.drawText(g, textRenderer, state, right() - 11 - textRenderer.getWidth(state),
                    y + 6, equipped ? 0xFF8FE8AF : entry.unlocked() ? 0xFFA8C7FF : 0xFF777A83, false);
                Text preview = HistoryStore.componentFromJson(entry.previewJson());
                if (preview != null) {
                    RenderHelper.drawText(g, textRenderer, preview, left() + 14, y + 24,
                        entry.unlocked() ? 0xFFFFFFFF : 0xFF85858B, false);
                } else {
                    RenderHelper.drawText(g, textRenderer, "气泡预览", left() + 14, y + 24,
                        entry.unlocked() ? 0xFFCBD4E8 : 0xFF777A83, false);
                }
            }
            RenderHelper.disableScissor(g);
        }
        super.extractRenderState(g, mouseX, mouseY, tickDelta);
    }

    @Override
    public void extractTransparentBackground(DrawContext g) { }

    @Override
    public void renderBackground(DrawContext g, int mouseX, int mouseY, float tickDelta) { }
    //#endif

    @Override
    public boolean mouseClicked(Click click, boolean inside) {
        if (click.button() == 0 && available && !loading
            && click.x() >= left() && click.x() < right()
            && click.y() >= top() && click.y() < bottom()) {
            int index = ((int) click.y() - top() + scroll) / ROW_H;
            if (index >= 0 && index < catalogue.size()) {
                BubbleCatalogPayload.Entry entry = catalogue.get(index);
                if (entry.unlocked() && !entry.id().equals(selected)
                    && ClientPlayNetworking.canSend(BubbleActionPayload.ID)) {
                    loading = true;
                    ClientPlayNetworking.send(new BubbleActionPayload(1, entry.id()));
                }
                return true;
            }
        }
        return super.mouseClicked(click, inside);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll = MathHelper.clamp(scroll - (int) (vertical * 24), 0, maxScroll());
        return true;
    }

    @Override public boolean shouldPause() { return false; }
}
//#endif
