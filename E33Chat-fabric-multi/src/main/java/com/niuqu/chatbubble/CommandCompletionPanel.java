//#if MC >= 26000
package com.niuqu.chatbubble;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** A visible, scrollable view of the server's Brigadier completions on 26.x. */
final class CommandCompletionPanel {
    private static final int MAX_ROWS = 8;
    private static final int ROW_HEIGHT = 13;
    private final MinecraftClient client;
    private String query = "";
    private int queryCursor = -1;
    private CompletableFuture<Suggestions> pending;
    private List<Suggestion> candidates = List.of();
    private int selected = -1;
    private int firstRow;
    private boolean dismissed;
    private int x, y, width, height;

    CommandCompletionPanel(MinecraftClient client) {
        this.client = client;
    }

    private void update(TextFieldWidget input) {
        String text = input.getText();
        int cursor = input.getCursorPosition();
        if (!text.equals(query) || cursor != queryCursor) {
            query = text;
            queryCursor = cursor;
            candidates = List.of();
            pending = null;
            selected = -1;
            firstRow = 0;
            dismissed = false;
            if (text.startsWith("/") && client.player != null && client.player.networkHandler != null) {
                try {
                    var commands = client.player.networkHandler.getCommands();
                    var source = client.player.networkHandler.getSuggestionsProvider();
                    StringReader reader = new StringReader(text);
                    reader.skip();
                    pending = commands.getCompletionSuggestions(
                        commands.parse(reader, source), Math.min(cursor, text.length()));
                } catch (RuntimeException ignored) {
                    pending = null;
                }
            }
        }
        if (pending != null && pending.isDone()) {
            try {
                Suggestions result = pending.join();
                candidates = result == null ? List.of() : List.copyOf(result.getList());
            } catch (RuntimeException ignored) {
                candidates = List.of();
            }
            pending = null;
        }
    }

    private boolean visible(TextFieldWidget input) {
        return !dismissed && input.isFocused() && query.startsWith("/") && !candidates.isEmpty();
    }

    boolean keyPressed(TextFieldWidget input, int key, int modifiers) {
        update(input);
        if (!input.getText().startsWith("/")) return false;
        if (key == 258) { // Tab and Shift+Tab move through the visible list.
            if (!candidates.isEmpty()) move((modifiers & 1) == 0 ? 1 : -1);
            return true;
        }
        if (!visible(input)) return false;
        if (key == 264) { move(1); return true; }
        if (key == 265) { move(-1); return true; }
        if ((key == 257 || key == 335) && selected >= 0) {
            apply(input, selected);
            return true;
        }
        if (key == 256) { dismissed = true; return true; }
        return false;
    }

    private void move(int direction) {
        dismissed = false;
        selected = selected < 0
            ? (direction > 0 ? 0 : candidates.size() - 1)
            : Math.floorMod(selected + direction, candidates.size());
        if (selected < firstRow) firstRow = selected;
        if (selected >= firstRow + MAX_ROWS) firstRow = selected - MAX_ROWS + 1;
    }

    private void apply(TextFieldWidget input, int index) {
        if (index < 0 || index >= candidates.size()) return;
        Suggestion choice = candidates.get(index);
        String completed = choice.apply(input.getText());
        input.setText(completed);
        input.setCursorPosition(Math.min(completed.length(),
            choice.getRange().getStart() + choice./*brigadier*/getText().length()));
        dismissed = true;
    }

    boolean mouseClicked(TextFieldWidget input, double mouseX, double mouseY, int button) {
        if (!visible(input) || mouseX < x || mouseX >= x + width
            || mouseY < y || mouseY >= y + height) return false;
        if (button == 0) {
            int row = ((int) mouseY - y - 2) / ROW_HEIGHT;
            if (mouseY >= y + 2 && row >= 0 && row < Math.min(MAX_ROWS, candidates.size()))
                apply(input, firstRow + row);
        }
        return true;
    }

    boolean mouseScrolled(TextFieldWidget input, double mouseX, double mouseY, double amount) {
        if (!visible(input) || mouseX < x || mouseX >= x + width
            || mouseY < y || mouseY >= y + height) return false;
        firstRow = Math.max(0, Math.min(Math.max(0, candidates.size() - MAX_ROWS),
            firstRow - (int) Math.signum(amount)));
        return true;
    }

    void render(DrawContext g, TextRenderer font, TextFieldWidget input,
                int screenWidth, int screenHeight, int mouseX, int mouseY) {
        update(input);
        if (!visible(input)) return;
        int rows = Math.min(MAX_ROWS, candidates.size());
        boolean hasMore = candidates.size() > rows;
        height = rows * ROW_HEIGHT + 4 + (hasMore ? 12 : 0);
        width = 120;
        for (int row = 0; row < rows; row++)
            width = Math.max(width,
                font.getWidth(candidates.get(firstRow + row)./*brigadier*/getText()) + 18);
        width = Math.min(width, Math.max(120, screenWidth - 8));
        x = Math.max(4, Math.min(input.getX(), screenWidth - width - 4));
        y = Math.max(4, input.getY() - height - 3);
        RenderHelper.fill(g, x, y, x + width, y + height, 0xEE202127);
        RenderHelper.fill(g, x, y, x + width, y + 1, 0xFF777D88);
        for (int row = 0; row < rows; row++) {
            int index = firstRow + row;
            Suggestion suggestion = candidates.get(index);
            int yy = y + 2 + row * ROW_HEIGHT;
            boolean hover = mouseX >= x && mouseX < x + width
                && mouseY >= yy && mouseY < yy + ROW_HEIGHT;
            if (index == selected || hover)
                RenderHelper.fill(g, x + 2, yy, x + width - 2, yy + ROW_HEIGHT, 0xFF43516C);
            String label = font.trimToWidth(suggestion./*brigadier*/getText(), width - 12);
            RenderHelper.drawText(g, font, label, x + 6, yy + 2, 0xFFFFFFFF, false);
        }
        if (hasMore) {
            String count = (firstRow + 1) + "-" + (firstRow + rows) + " / " + candidates.size();
            RenderHelper.drawText(g, font, count,
                x + width - font.getWidth(count) - 6, y + height - 10, 0xFFAAB1C0, false);
        }
    }
}
//#endif
