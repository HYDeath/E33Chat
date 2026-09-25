package com.niuqu.chatbubble.ui;
import com.niuqu.chatbubble.texture.UiTextureManager;
import com.niuqu.chatbubble.texture.ColoredTextureRenderer;
import com.niuqu.chatbubble.render.ChatBubbleTheme;
import com.niuqu.chatbubble.ChatBubbleScreen;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

public class ChatEmojiPanel {
    private static final int PANEL_H = 132;
    private static final int TAB_H = 18;
    private static final int COLS = 9;
    private static final int SLOT = 18;
    private static final int KAO_ITEM_H = 13;
    private static final int CRAFT_ITEM_H = 16;
    private static final int KAO_COLS = 2;
    private static final int KAO_COL_W = 90;
    private record CraftEmoji(String keyword, Text preview) {}
    private static volatile java.util.List<CraftEmoji> craftEmojis = java.util.List.of();

    public static void setCraftEmojis(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            craftEmojis = java.util.List.of();
            return;
        }
        java.util.LinkedHashMap<String, CraftEmoji> parsed = new java.util.LinkedHashMap<>();
        for (String line : encoded.split("\\n")) {
            int tab = line.indexOf('\t');
            String keyword = tab < 0 ? line : line.substring(0, tab);
            if (!keyword.matches(":\\S[^:\\s]{0,63}:") || parsed.containsKey(keyword)) continue;
            Text preview = tab < 0 ? null
                : com.niuqu.chatbubble.store.HistoryStore.componentFromJson(line.substring(tab + 1));
            parsed.put(keyword, new CraftEmoji(keyword, preview));
            if (parsed.size() >= 512) break;
        }
        craftEmojis = java.util.List.copyOf(parsed.values());
    }

    // 面板宽度自适应：聊天面板按固定物理宽设计（6x 时 panelW 收缩到 ~166），
    // 表情面板若固定 170 逻辑宽会反超面板 → clamp 边界反转 → 溢出屏幕左边。
    // 收缩到 panelW-4（保证 clamp 右界 ≥ 左界），最小 100。
    private static int fitWidth(int natural, int panelW) {
        return Math.max(100, Math.min(natural, panelW - 4));
    }

    // 表情列数随实际宽度收缩（SLOT 不变）
    private static int gridCols(int pw) {
        return Math.max(1, (pw - 8) / SLOT);
    }

    /** Grid column for a click, or -1 when the point lands in the panel's own
     *  padding band on either edge. Plain integer division maps that band onto
     *  column 0 or onto the next row's first cell, which inserted the wrong
     *  emote on narrow panels. */
    static int gridColumn(int mx, int px, int cellSize, int cols) {
        if (cellSize <= 0 || cols <= 0) return -1;
        int col = (mx - px - 4) / cellSize;
        return (col < 0 || col >= cols) ? -1 : col;
    }

    // 弹层 x 夹在聊天面板内且不超屏幕左右（表情/快捷/搜索共用模式）
    private static int clampX(int px, int pw, int panelX, int panelW) {
        int screenW = net.minecraft.client.MinecraftClient.getInstance().getWindow().getScaledWidth();
        int max = Math.min(panelX + panelW - pw - 2, screenW - pw - 2);
        return MathHelper.clamp(px, Math.min(panelX + 2, max), max);
    }

    private static final String[] EMOTES = {
        "😀","😃","😄","😁","😆","😅","🤣","😂",
        "🙂","😉","😊","😇","🥰","😍","🤩","😘",
        "😋","😛","😜","🤪","😎","🤗","🤔","😐",
        "😢","😭","😤","😡","🥺","😴","😷","🤒",
        "🐱","🐶","🐼","🐨","🐰","🦊","🐸","🐵",
        "🐭","🐹","🐮","🦁","🐯","🐻","🐧","🐤",
        "🐴","🦄","🐝","🐞","🦋","🐙","🦀","🐠",
        "🐷","🐖",
        "❤️","🧡","💛","💚","💙","💜","🖤","💔",
        "💕","💖","💗","💘","💝","💟","❣️","💌",
        "👍","👎","👏","🙌","💪","🤝","👋","✌️",
        "🎮","🎯","🎨","🎵","🎶","🎤","🎧","🎼",
        "⭐","🌟","🔥","💧","🌈","❄️","🎉","🎊",
        "🍕","🍔","🌮","🍩","🍪","🎂","☕","🍺",
        "⬆️","⬇️","✅","❌","❓","❗","💤","💡",
        "💀","🗿","🤡","👀","💯","💢","💬","💭",
    };
    private static final java.util.Set<Integer> COLOR_CODEPOINTS = new java.util.HashSet<>();
    static {
        for (String emoji : EMOTES) COLOR_CODEPOINTS.add(emoji.codePointAt(0));
    }

    public static boolean supports(int codepoint) { return COLOR_CODEPOINTS.contains(codepoint); }

    private static final String[] KAO = {
        "(｡•̀ᴗ-)✧","(๑˃̵ᴗ˂̵)و","(๑•̀ㅂ•́)و✧","(◍•ᴗ•◍)",
        "╰(*°▽°*)╯","(≧∇≦)ﾉ","(＾▽＾)","✧٩(ˊωˋ*)و✧",
        "ฅ^•ﻌ•^ฅ","(•ω•)","(￣▽￣*)","(⌒▽⌒)☆",
        "(o゜▽゜)o☆","＼(￣▽￣)／","(◔◡◔)","／(=✪ x ✪=)＼",
        "¯\\_(ツ)_/¯","(ー_ー゛)","(￢_￢)","(¬_¬)",
        "(⇀‸↼‶)","(｡ŏ_ŏ)","(・∀・)","_(:з」∠)_",
        "(╯°□°）╯︵ ┻━┻","(´;ω;｀)","Σ(°△°|||)","(◎ロ◎)",
        "(∪.∪ )...zzz",
    };

    public boolean visible;
    public int scroll;
    public int tab;

    /** Screen 注入的关闭请求钩子（播放关闭动画）；null 时直接隐藏（D07-6）。 */
    public Runnable closeRequest;

    private void requestClose() {
        if (closeRequest != null) closeRequest.run();
        else visible = false;
    }

    public void render(DrawContext g, int mouseX, int mouseY,
            TextRenderer font, ChatBubbleTheme.Colors c,
            int panelX, int panelW, int barTop, int iconS, int pad, float alpha) {
        if (!visible) return;
        int a255 = (int) (255 * alpha);
        int sendX = panelX + panelW - pad - iconS + 2;

        boolean isKaomoji = tab == 1;
        boolean isCraft = tab == 2;
        int natural = isKaomoji ? KAO_COLS * KAO_COL_W + 8
            : isCraft ? KAO_COLS * KAO_COL_W + 8 : COLS * SLOT + 8;
        int pw = fitWidth(natural, panelW);
        int px = clampX(sendX + iconS / 2 - pw / 2, pw, panelX, panelW);
        int py = Math.max(2, barTop - PANEL_H - 4);

        java.util.List<String> tabLabels = new java.util.ArrayList<>(java.util.List.of(
            Text.translatable("e33chat.emoji.tab_emoji").getString(),
            Text.translatable("e33chat.emoji.tab_kaomoji").getString(),
            Text.translatable("e33chat.emoji.tab_craft").getString()
        ));
        java.util.List<String> shortLabels = new java.util.ArrayList<>(java.util.List.of(
            Text.translatable("e33chat.emoji.tab_emoji_short").getString(),
            Text.translatable("e33chat.emoji.tab_kaomoji_short").getString(),
            Text.translatable("e33chat.emoji.tab_craft_short").getString()
        ));
        com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g,
            com.niuqu.chatbubble.texture.UiTextureManager.rl(com.niuqu.chatbubble.texture.UiElement.TITLE_BAR),
            px, py, pw, TAB_H + 1, alpha);
        for (int t = 0; t < tabLabels.size(); t++) {
            int tx = px + t * pw / tabLabels.size();
            int tabW = px + (t + 1) * pw / tabLabels.size() - tx;
            if (t == tab)
                com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g,
                    com.niuqu.chatbubble.texture.UiTextureManager.rl(com.niuqu.chatbubble.texture.UiElement.INPUT_BG),
                    tx, py, tabW, TAB_H, alpha);
            String label = tabLabels.get(t);
            if (font.getWidth(label) > tabW - 6) label = shortLabels.get(t);
            label = font.trimToWidth(label, Math.max(1, tabW - 4));
            g.drawText(font, label,
                tx + tabW / 2 - font.getWidth(label) / 2, py + (TAB_H - font.fontHeight) / 2,
                com.niuqu.chatbubble.render.ChatBubbleTheme.alphaBlend(c.textPrimary(), a255), false);
        }
        com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g,
            com.niuqu.chatbubble.texture.UiTextureManager.rl(com.niuqu.chatbubble.texture.UiElement.DIVIDER),
            px, py + TAB_H, pw, 1, alpha);

        int cy = py + TAB_H + 1;
        int ch = PANEL_H - TAB_H - 1;
        com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g,
            com.niuqu.chatbubble.texture.UiTextureManager.rl(com.niuqu.chatbubble.texture.UiElement.CONTENT_BG),
            px, cy, pw, py + PANEL_H - cy, alpha);
        g.drawBorder(px, py, pw, PANEL_H, ChatBubbleTheme.alphaBlend(c.divider(), a255));

        if (isKaomoji) {
            renderKaomojiList(g, mouseX, mouseY, font, c, px, cy, pw, ch, alpha);
        } else if (isCraft) {
            renderCraftList(g, mouseX, mouseY, font, c, px, cy, pw, ch, alpha);
        } else {
            renderEmojiGrid(g, mouseX, mouseY, font, c, px, cy, pw, ch, gridCols(pw), alpha);
        }
    }

    private void renderCraftList(DrawContext g, int mouseX, int mouseY,
            TextRenderer font, ChatBubbleTheme.Colors c,
            int px, int cy, int pw, int ch, float alpha) {
        int colW = (pw - 8) / 2;
        if (craftEmojis.isEmpty()) {
            String hint = Text.translatable("e33chat.emoji.craft_empty").getString();
            g.drawText(font, font.trimToWidth(hint, pw - 10), px + 5, cy + 6,
                ChatBubbleTheme.alphaBlend(c.textMuted(), (int) (255 * alpha)), false);
            String help = Text.translatable("e33chat.emoji.craft_help").getString();
            g.drawText(font, font.trimToWidth(help, pw - 10), px + 5, cy + 19,
                ChatBubbleTheme.alphaBlend(c.textMuted(), (int) (255 * alpha)), false);
            return;
        }
        int max = Math.max(0, ((craftEmojis.size() + 1) / 2) * CRAFT_ITEM_H + 8 - ch);
        scroll = MathHelper.clamp(scroll, 0, max);
        g.enableScissor(px + 1, cy + 1, px + pw - 1, cy + ch - 1);
        for (int i = 0; i < craftEmojis.size(); i++) {
            int x = px + 4 + i % 2 * colW;
            int y = cy + 2 + i / 2 * CRAFT_ITEM_H - scroll;
            if (y + CRAFT_ITEM_H <= cy || y >= cy + ch) continue;
            if (mouseX >= x && mouseX < x + colW && mouseY >= y && mouseY < y + CRAFT_ITEM_H)
                ColoredTextureRenderer.drawWithAlpha(g,
                    UiTextureManager.rl(com.niuqu.chatbubble.texture.UiElement.HOVER_BG),
                    x, y, colW, CRAFT_ITEM_H, alpha);
            CraftEmoji entry = craftEmojis.get(i);
            int labelX = x + 2;
            if (entry.preview() != null && !entry.preview().getString().equals(entry.keyword())) {
                g.drawText(font, entry.preview(), x + 2, y + 1,
                    ChatBubbleTheme.alphaBlend(c.textPrimary(), (int) (255 * alpha)), false);
                labelX += Math.min(14, font.getWidth(entry.preview()) + 3);
            }
            String label = font.trimToWidth(entry.keyword(), Math.max(4, x + colW - labelX - 2));
            g.drawText(font, label, labelX, y + 2,
                ChatBubbleTheme.alphaBlend(c.textPrimary(), (int) (255 * alpha)), false);
        }
        g.disableScissor();
    }

    private void renderEmojiGrid(DrawContext g, int mouseX, int mouseY,
            TextRenderer font, ChatBubbleTheme.Colors c,
            int px, int cy, int pw, int ch, int cols, float alpha) {
        int a255 = (int) (255 * alpha);
        int rows = (EMOTES.length + cols - 1) / cols;
        int totalH = rows * SLOT + 4;
        int maxScroll = Math.max(0, totalH - ch + 4);
        scroll = MathHelper.clamp(scroll, 0, maxScroll);

        g.enableScissor(px + 1, cy + 1, px + pw - 1, cy + ch - 1);
        int sy = cy + 2 - scroll;
        for (int i = 0; i < EMOTES.length; i++) {
            int col = i % cols;
            int row = i / cols;
            int ex = px + 4 + col * SLOT;
            int ey = sy + row * SLOT;
            if (ey + SLOT <= cy || ey >= cy + ch) continue;
            if (mouseX >= ex && mouseX <= ex + SLOT - 1
                && mouseY >= ey && mouseY <= ey + SLOT - 1)
                com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g,
                    com.niuqu.chatbubble.texture.UiTextureManager.rl(com.niuqu.chatbubble.texture.UiElement.HOVER_BG),
                    ex, ey, SLOT - 1, SLOT - 1, alpha);
            String emoji = EMOTES[i];
            Text colored = com.niuqu.chatbubble.ColorEmojiText.decorate(Text.literal(emoji));
            g.drawText(font, colored,
                ex + SLOT / 2 - font.getWidth(colored) / 2,
                ey + (SLOT - font.fontHeight) / 2,
                com.niuqu.chatbubble.render.ChatBubbleTheme.alphaBlend(0xFFFFFFFF, a255), false);
        }
        g.disableScissor();
    }

    private void renderKaomojiList(DrawContext g, int mouseX, int mouseY,
            TextRenderer font, ChatBubbleTheme.Colors c,
            int px, int cy, int pw, int ch, float alpha) {
        int a255 = (int) (255 * alpha);
        int kCols = KAO_COLS;
        int kColW = (pw - 8) / kCols;
        int totalH = ((KAO.length + kCols - 1) / kCols) * KAO_ITEM_H + 4;
        int maxScroll = Math.max(0, totalH - ch + 4);
        scroll = MathHelper.clamp(scroll, 0, maxScroll);

        g.enableScissor(px + 1, cy + 1, px + pw - 1, cy + ch - 1);
        int sy = cy + 2 - scroll;
        for (int i = 0; i < KAO.length; i++) {
            int col = i % kCols;
            int row = i / kCols;
            int ex = px + 4 + col * kColW;
            int ey = sy + row * KAO_ITEM_H;
            if (ey + KAO_ITEM_H <= cy || ey >= cy + ch) continue;
            if (mouseX >= ex && mouseX <= ex + kColW - 1
                && mouseY >= ey && mouseY <= ey + KAO_ITEM_H - 1)
                com.niuqu.chatbubble.texture.ColoredTextureRenderer.drawWithAlpha(g,
                    com.niuqu.chatbubble.texture.UiTextureManager.rl(com.niuqu.chatbubble.texture.UiElement.HOVER_BG),
                    ex, ey, kColW - 1, KAO_ITEM_H - 1, alpha);
            g.drawText(font, KAO[i],
                ex + 2, ey + (KAO_ITEM_H - font.fontHeight) / 2, com.niuqu.chatbubble.render.ChatBubbleTheme.alphaBlend(c.textPrimary(), a255), false);
        }
        g.disableScissor();
    }

    public String handleClick(int mx, int my,
            TextRenderer font, ChatBubbleTheme.Colors c,
            int panelX, int panelW, int barTop, int iconS, int pad) {
        if (!visible) return null;
        int sendX = panelX + panelW - pad - iconS + 2;

        int iconY = barTop + (ChatBubbleScreen.BAR_H - iconS) / 2;
        int emojiIconX = sendX - iconS - 6;
        if (mx >= emojiIconX && mx <= emojiIconX + iconS && my >= iconY && my <= iconY + iconS) {
            requestClose();
            return "";
        }

        boolean isKaomoji = tab == 1;
        boolean isCraft = tab == 2;
        int natural = isKaomoji ? KAO_COLS * KAO_COL_W + 8
            : isCraft ? KAO_COLS * KAO_COL_W + 8 : COLS * SLOT + 8;
        int pw = fitWidth(natural, panelW);
        int px = clampX(sendX + iconS / 2 - pw / 2, pw, panelX, panelW);
        int py = Math.max(2, barTop - PANEL_H - 4);

        if (mx < px || mx > px + pw || my < py || my > py + PANEL_H) {
            requestClose();
            return null;
        }

        if (my < py + TAB_H) {
            int tabCount = 3;
            int t = Math.min(tabCount - 1, (mx - px) * tabCount / pw);
            if (t >= 0 && t < tabCount) { tab = t; scroll = 0; }
            return "";
        }

        int cy = py + TAB_H + 1;
        if (isKaomoji) {
            int cw = (pw - 8) / KAO_COLS;
            int col = gridColumn(mx, px, cw, KAO_COLS);
            if (col < 0) return null;
            int row = (my - cy - 2 + scroll) / KAO_ITEM_H;
            int idx = row * KAO_COLS + col;
            if (idx >= 0 && idx < KAO.length) return KAO[idx];
        } else if (isCraft) {
            int colW = (pw - 8) / 2;
            int col = gridColumn(mx, px, colW, 2);
            if (col < 0) return null;
            int row = (my - cy - 2 + scroll) / CRAFT_ITEM_H;
            int idx = row * 2 + col;
            if (idx >= 0 && idx < craftEmojis.size()) return craftEmojis.get(idx).keyword();
        } else {
            int cols = gridCols(pw);
            int col = gridColumn(mx, px, SLOT, cols);
            if (col < 0) return null;
            int row = (my - cy - 2 + scroll) / SLOT;
            int idx = row * cols + col;
            if (idx >= 0 && idx < EMOTES.length) return EMOTES[idx];
        }
        return null;
    }

    /** @param panelW real panel width — the grid column count shrinks with it,
     *  and scroll math derived from a hardcoded width never reached the
     *  bottom of the list on narrow panels. */
    public void handleScroll(double scrollY, int panelW) {
        boolean isKaomoji = tab == 1;
        boolean isCraft = tab == 2;
        int totalH;
        if (isKaomoji) {
            totalH = ((KAO.length + KAO_COLS - 1) / KAO_COLS) * KAO_ITEM_H + 4;
        } else if (isCraft) {
            totalH = ((craftEmojis.size() + 1) / 2) * CRAFT_ITEM_H + 4;
        } else {
            int cols = gridCols(fitWidth(COLS * SLOT + 8, panelW));
            int rows = (EMOTES.length + cols - 1) / cols;
            totalH = rows * SLOT + 4;
        }
        int ch = PANEL_H - TAB_H - 1;
        int maxScroll = Math.max(0, totalH - ch + 4);
        scroll = MathHelper.clamp(scroll - (int) scrollY * 20, 0, maxScroll);
    }
}
