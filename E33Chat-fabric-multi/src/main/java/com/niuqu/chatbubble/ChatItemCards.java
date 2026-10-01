package com.niuqu.chatbubble;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.font.TextRenderer;
//#if MC >= 12000
import net.minecraft.client.gui.DrawContext;
//#endif
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

/**
 * Inline TrChat item, shulker, inventory and ender-chest previews.
 * The click command still opens the server view; this only draws the chat card.
 */
public final class ChatItemCards {
    private static final String MARK = "e33.show/1/";
    private static final int SLOT = 16;

    public static final class Card {
        final Style style;
        final ItemStack icon;
        final String title;
        final int count;
        final int modelData;
        final String itemId;
        final String kind;
        final int columns;
        final List<ItemStack> slots;
        Card(Style style, ItemStack icon, String title, int count, int modelData, String itemId,
             String kind, int columns, List<ItemStack> slots) {
            this.style = style;
            this.icon = icon;
            this.title = title;
            this.count = count;
            this.modelData = modelData;
            this.itemId = itemId;
            this.kind = kind;
            this.columns = columns;
            this.slots = slots;
        }
    }

    public interface Clicks {
        void accept(int x, int y, int w, int h, Style style);
    }

    private ChatItemCards() {}

    public static List<Card> find(Text text) {
        List<Card> cards = new ArrayList<>();
        if (text == null) return cards;
        text.visit((style, part) -> {
            Card card = card(style);
            if (card != null) cards.add(card);
            return Optional.empty();
        }, Style.EMPTY);
        return cards;
    }

    public static Text without(Text text) {
        if (text == null || find(text).isEmpty()) return text;
        MutableText out = Text.empty();
        boolean[] any = {false};
        text.visit((style, part) -> {
            boolean decoration = part.replaceAll("[\\[\\]\\sxX×0-9§&]", "").isEmpty();
            if (card(style) == null && !part.isEmpty() && !decoration) {
                out.append(Text.literal(part).setStyle(style));
                any[0] = true;
            }
            return Optional.empty();
        }, Style.EMPTY);
        return any[0] ? out : Text.empty();
    }

    public static int width(List<Card> cards, TextRenderer font, int maxWidth) {
        int width = 0;
        for (Card card : cards) width = Math.max(width, cardWidth(card, font, maxWidth));
        return Math.min(maxWidth, width);
    }

    public static int height(List<Card> cards, TextRenderer font, int maxWidth) {
        int height = 0;
        for (Card card : cards) height += cardHeight(card, font, maxWidth) + 3;
        return height;
    }

    //#if MC >= 12000
    public static ItemStack drawCards(DrawContext g, TextRenderer font, List<Card> cards, int x, int y,
                                    int maxWidth, int mouseX, int mouseY, float alpha, Clicks clicks) {
        ItemStack hovered = null;
        int cursor = y;
        for (Card card : cards) {
            int w = cardWidth(card, font, maxWidth);
            int h = cardHeight(card, font, maxWidth);
            int chipH = 22;
            RoundRectRenderer.fill(g, x, cursor, x + w, cursor + chipH, 4, fade(0xFF2E9BFF, alpha));
            drawStack(g, card.icon, x + 3, cursor + 3);
            String name = trim(font, card.title, Math.max(8, w - 28));
            g.drawText(font, name, x + 22, cursor + 7, fade(0xFFFFFFFF, alpha), false);
            int line = cursor + chipH + 1;
            if (card.count > 1 || card.modelData > 0) {
                int pen = x + 2;
                if (card.count > 1) {
                    String count = "x" + card.count;
                    g.drawText(font, count, pen, line, fade(0xFFFFAA00, alpha), false);
                    pen += font.getWidth(count) + 4;
                }
                if (card.modelData > 0) {
                    String badge = Integer.toString(card.modelData);
                    int bw = font.getWidth(badge) + 8;
                    RoundRectRenderer.fill(g, pen, line - 1, pen + bw, line + font.fontHeight + 1, 3, fade(0xFF1B6DFF, alpha));
                    g.drawText(font, badge, pen + 4, line, fade(0xFFFFFFFF, alpha), false);
                }
                line += font.fontHeight + 2;
            }
            if (!card.slots.isEmpty()) {
                int slot = slotSize(card, maxWidth);
                for (int i = 0; i < card.slots.size(); i++) {
                    int sx = x + (i % card.columns) * slot;
                    int sy = line + (i / card.columns) * slot;
                    g.fill(sx, sy, sx + slot - 1, sy + slot - 1, fade(0xAA101010, alpha));
                    ItemStack stack = card.slots.get(i);
                    if (stack != null && !stack.isEmpty()) drawStack(g, stack, sx, sy);
                    if (mouseX >= sx && mouseX < sx + slot && mouseY >= sy && mouseY < sy + slot && stack != null && !stack.isEmpty())
                        hovered = stack;
                }
            }
            if (clicks != null) clicks.accept(x, cursor, w, h, card.style);
            if (hovered == null && mouseX >= x && mouseX < x + w && mouseY >= cursor && mouseY < cursor + chipH)
                hovered = card.icon;
            cursor += h + 3;
        }
        return hovered;
    }

    public static void tooltip(DrawContext g, TextRenderer font, ItemStack stack, int mouseX, int mouseY, int screenW, int screenH) {
        if (stack == null || stack.isEmpty()) return;
        String id = itemId(stack);
        String namespace = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
        int cmd = modelData(stack);
        String title = stackName(stack) + (cmd > 0 ? " (#" + String.format(Locale.ROOT, "%04d", cmd) + ")" : "");
        String origin = "minecraft".equals(namespace) ? "原版" : namespace;
        int tw = Math.max(font.getWidth(title), Math.max(font.getWidth(origin), font.getWidth(id))) + 28;
        int th = 8 + font.fontHeight * 3;
        int x = mouseX + 12;
        int y = mouseY - 8;
        if (x + tw > screenW) x = Math.max(4, mouseX - tw - 8);
        if (y + th > screenH) y = Math.max(4, screenH - th - 4);
        g.fill(x, y, x + tw, y + th, 0xF0100010);
        g.fill(x, y, x + tw, y + 1, 0xFF5000FF);
        drawStack(g, stack, x + 4, y + 4);
        g.drawText(font, title, x + 24, y + 4, 0xFFFFFFFF, false);
        g.drawText(font, origin, x + 24, y + 4 + font.fontHeight, "minecraft".equals(namespace) ? 0xFFFFFF55 : 0xFF55FFFF, false);
        g.drawText(font, id, x + 24, y + 4 + font.fontHeight * 2, 0xFFAAAAAA, false);
    }
    //#else
    //$$ public static ItemStack render(Object g, TextRenderer font, List<Card> cards, int x, int y,
    //$$                                 int maxWidth, int mouseX, int mouseY, float alpha, Clicks clicks) { return null; }
    //$$ public static void tooltip(Object g, TextRenderer font, ItemStack stack, int mouseX, int mouseY, int screenW, int screenH) {}
    //#endif

    private static int cardWidth(Card card, TextRenderer font, int maxWidth) {
        int chip = Math.min(maxWidth, 28 + font.getWidth(card.title));
        int grid = card.slots.isEmpty() ? 0 : card.columns * slotSize(card, maxWidth);
        return Math.max(48, Math.max(chip, grid));
    }

    private static int cardHeight(Card card, TextRenderer font, int maxWidth) {
        int height = 22;
        if (card.count > 1 || card.modelData > 0) height += font.fontHeight + 3;
        if (!card.slots.isEmpty()) height += ((card.slots.size() + card.columns - 1) / card.columns) * slotSize(card, maxWidth);
        return height;
    }

    private static int slotSize(Card card, int maxWidth) {
        if (card.columns <= 0) return SLOT;
        return Math.max(12, Math.min(SLOT, maxWidth / card.columns));
    }

    private static Card card(Style style) {
        if (style == null) return null;
        ItemStack shown = present(hoverItem(style.getHoverEvent()));
        String command = command(style.getClickEvent());
        String kind = declaredKind(style.getInsertion());
        if (kind == null) kind = kind(command);
        List<ItemStack> slots = presentSlots(slots(style.getInsertion()));
        if ("shulker".equals(kind) && slots.isEmpty() && shown != null) slots = container(shown);
        if (!"shulker".equals(kind) && !"inv".equals(kind) && !"ender".equals(kind)) slots = List.of();
        int columns = slots.isEmpty() ? 9 : columns(style.getInsertion(), slots);
        if ("inv".equals(kind) || "ender".equals(kind)) slots = compactRows(slots, columns);
        if (shown == null && slots.isEmpty() && kind == null) return null;
        if (shown == null && "item".equals(kind) && slots.isEmpty()) return null;
        if (shown != null && shown.isEmpty() && slots.isEmpty()) return null;
        ItemStack icon = shown != null && !shown.isEmpty() ? shown : first(slots);
        if (icon == null || icon.isEmpty()) {
            icon = stack("inv".equals(kind) ? "minecraft:chest" : "ender".equals(kind) ? "minecraft:ender_chest" : "minecraft:paper", 1, 0);
        }
        if (icon == null || icon.isEmpty()) return null;
        String title = shown != null && !shown.isEmpty() ? stackName(shown)
            : "inv".equals(kind) ? "背包" : "ender".equals(kind) ? "末影箱" : stackName(icon);
        int count = shown != null && !shown.isEmpty() ? shown.getCount() : 1;
        int cmd = shown != null ? modelData(shown) : 0;
        return new Card(style, icon, title.isBlank() ? itemId(icon) : title, count, cmd, itemId(icon),
            kind == null ? "item" : kind, columns, slots);
    }

    private static String kind(String command) {
        if (command == null) return null;
        String head = command.trim().toLowerCase(Locale.ROOT);
        if (head.startsWith("/")) head = head.substring(1);
        if (head.startsWith("view-inventory")) return "inv";
        if (head.startsWith("view-enderchest")) return "ender";
        if (head.startsWith("view-item")) return "item";
        return null;
    }

    private static String declaredKind(String insertion) {
        if (insertion == null || !insertion.startsWith(MARK)) return null;
        String[] head = insertion.substring(MARK.length()).split("/", 3);
        if (head.length < 3 || head[2].isEmpty()) return null;
        return switch (head[0]) {
            case "shulker", "inv", "ender" -> head[0];
            default -> null;
        };
    }

    private static List<ItemStack> slots(String insertion) {
        if (declaredKind(insertion) == null) return new ArrayList<>();
        String encoded = insertion.substring(MARK.length()).split("/", 3)[2];
        List<ItemStack> slots = new ArrayList<>();
        for (String piece : encoded.split("\u001e", -1)) {
            if (piece.isEmpty() || "-".equals(piece)) {
                slots.add(ItemStack.EMPTY);
                continue;
            }
            String[] fields = piece.split("\u001f", -1);
            int count = fields.length > 1 ? parse(fields[1], 1) : 1;
            int cmd = fields.length > 2 ? parse(fields[2], 0) : 0;
            slots.add(stack(fields[0], count, cmd));
        }
        return slots;
    }

    private static List<ItemStack> compactRows(List<ItemStack> slots, int columns) {
        List<ItemStack> kept = new ArrayList<>();
        int rows = (slots.size() + columns - 1) / columns;
        for (int row = 0; row < rows; row++) {
            boolean any = false;
            for (int col = 0; col < columns && row * columns + col < slots.size(); col++) {
                ItemStack stack = slots.get(row * columns + col);
                if (stack != null && !stack.isEmpty()) any = true;
            }
            if (!any) continue;
            for (int col = 0; col < columns; col++) {
                int index = row * columns + col;
                kept.add(index < slots.size() && slots.get(index) != null ? slots.get(index) : ItemStack.EMPTY);
            }
        }
        return kept;
    }

    private static int columns(String insertion, List<ItemStack> slots) {
        if (insertion != null && insertion.startsWith(MARK)) {
            String[] head = insertion.substring(MARK.length()).split("/", 3);
            if (head.length > 1) {
                int cols = parse(head[1], 9);
                if (cols > 0 && cols <= 9) return cols;
            }
        }
        return slots.size() > 0 && slots.size() % 9 == 0 ? 9 : Math.min(9, Math.max(1, slots.size()));
    }

    private static ItemStack hoverItem(Object hover) {
        if (hover == null) return null;
        String action = String.valueOf(call(hover, "getAction", call(hover, "action")));
        boolean show = hover.getClass().getSimpleName().contains("ShowItem") || action.contains("SHOW_ITEM");
        if (!show) return null;
        Object value = call(hover, "item");
        ItemStack stack = asStack(value);
        if (stack != null) return stack;
        Object actionArg = call(hover, "getAction");
        if (actionArg != null) {
            try {
                for (Method method : hover.getClass().getMethods()) {
                    if ("getValue".equals(method.getName()) && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isInstance(actionArg)) {
                        stack = asStack(method.invoke(hover, actionArg));
                        if (stack != null) return stack;
                    }
                }
            } catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }

    /** TACZ bridge guns arrive as paper plus GunId. The client mod already rebuilds those in the inventory. */
    private static ItemStack present(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return stack;
        try {
            Class<?> rebuilder = Class.forName("com.tacz.guns.client.util.ProxyGunRebuilder");
            Object rebuilt = rebuilder.getMethod("tryRebuild", ItemStack.class).invoke(null, stack);
            if (rebuilt instanceof ItemStack next && !next.isEmpty()) return next;
        } catch (Throwable ignored) { }
        return stack;
    }

    private static List<ItemStack> presentSlots(List<ItemStack> slots) {
        if (slots == null || slots.isEmpty()) return slots;
        List<ItemStack> out = new ArrayList<>(slots.size());
        for (ItemStack slot : slots) out.add(present(slot));
        return out;
    }

    private static ItemStack asStack(Object value) {
        if (value instanceof ItemStack stack) return stack.isEmpty() ? null : stack;
        Object created = call(value, "create", call(value, "asStack"));
        if (created instanceof ItemStack stack && !stack.isEmpty()) return stack;
        return null;
    }

    private static String command(Object click) {
        if (click == null) return null;
        Object action = call(click, "getAction", call(click, "action"));
        if (action != null && !String.valueOf(action).contains("RUN_COMMAND")) return null;
        Object value = call(click, "getValue", call(click, "value", call(click, "command")));
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static Object call(Object target, String name) {
        if (target == null) return null;
        try { return target.getClass().getMethod(name).invoke(target); }
        catch (ReflectiveOperationException ignored) { return null; }
    }

    private static Object call(Object target, String name, Object fallback) {
        Object value = call(target, name);
        return value != null ? value : fallback;
    }

    private static List<ItemStack> container(ItemStack stack) {
        List<ItemStack> slots = new ArrayList<>();
        try {
            Class<?> types = Class.forName("net.minecraft.component.DataComponentTypes");
            Object componentType = types.getField("CONTAINER").get(null);
            Object container = stack.getClass().getMethod("get", componentType.getClass().getInterfaces().length > 0
                ? componentType.getClass().getInterfaces()[0] : componentType.getClass()).invoke(stack, componentType);
            if (container == null) {
                for (Method method : stack.getClass().getMethods()) {
                    if ("get".equals(method.getName()) && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isInstance(componentType)) {
                        container = method.invoke(stack, componentType);
                        break;
                    }
                }
            }
            if (container == null) return slots;
            Object copied = call(container, "stream");
            if (copied instanceof java.util.stream.Stream<?> stream) {
                stream.forEach(entry -> { if (entry instanceof ItemStack item) slots.add(item); });
            }
        } catch (Throwable ignored) { }
        boolean placeholder = !slots.isEmpty();
        for (ItemStack slot : slots) {
            if (slot != null && !slot.isEmpty() && !itemId(slot).equals("minecraft:stone")) placeholder = false;
        }
        return placeholder ? List.of() : slots;
    }

    private static ItemStack stack(String id, int count, int modelData) {
        try {
            String[] parts = id.split(":", 2);
            String namespace = parts.length == 2 ? parts[0] : "minecraft";
            String path = parts.length == 2 ? parts[1] : parts[0];
            Class<?> identifiers = Class.forName("net.minecraft.util.Identifier");
            Object identifier;
            try { identifier = identifiers.getMethod("of", String.class, String.class).invoke(null, namespace, path); }
            catch (NoSuchMethodException ex) { identifier = identifiers.getConstructor(String.class, String.class).newInstance(namespace, path); }
            Object registry = registryItem();
            Object item = null;
            for (Method method : registry.getClass().getMethods()) {
                if (("get".equals(method.getName()) || "getValue".equals(method.getName())) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(identifier)) {
                    item = method.invoke(registry, identifier);
                    break;
                }
            }
            if (!(item instanceof net.minecraft.item.Item)) return ItemStack.EMPTY;
            ItemStack stack = new ItemStack((net.minecraft.item.Item) item, Math.max(1, count));
            return stack;
        } catch (Throwable ignored) { return ItemStack.EMPTY; }
    }

    private static Object registryItem() throws ReflectiveOperationException {
        try { return Class.forName("net.minecraft.registry.Registries").getField("ITEM").get(null); }
        catch (ClassNotFoundException ex) { return Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField("ITEM").get(null); }
    }

    private static int modelData(ItemStack stack) {
        try {
            Class<?> types = Class.forName("net.minecraft.component.DataComponentTypes");
            Object componentType = types.getField("CUSTOM_MODEL_DATA").get(null);
            Object data = null;
            for (Method method : stack.getClass().getMethods()) {
                if ("get".equals(method.getName()) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(componentType)) {
                    data = method.invoke(stack, componentType);
                    break;
                }
            }
            if (data == null) return 0;
            Object floats = call(data, "floats");
            if (floats instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Number number)
                return Math.round(number.floatValue());
            Object value = call(data, "value");
            return value instanceof Number number ? number.intValue() : 0;
        } catch (Throwable ignored) { return 0; }
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "minecraft:air";
        try {
            Object item = stack.getItem();
            Object registry = registryItem();
            // getId(T) is the numeric id. A different getId overload throws and used to
            // abort the lookup, so every item was reported as minecraft:air.
            for (Method method : registry.getClass().getMethods()) {
                if (!"getKey".equals(method.getName()) || method.getParameterCount() != 1) continue;
                if (!method.getParameterTypes()[0].isInstance(item)) continue;
                try {
                    Object key = method.invoke(registry, item);
                    if (key != null && key.toString().indexOf(':') >= 0) return key.toString();
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return "minecraft:air";
    }

    //#if MC >= 12000
    private static void drawStack(DrawContext g, ItemStack stack, int x, int y) {
        if (stack == null || stack.isEmpty()) return;
        for (String name : new String[] {"item", "drawItem", "renderItem"}) {
            try {
                g.getClass().getMethod(name, ItemStack.class, int.class, int.class).invoke(g, stack, x, y);
                return;
            } catch (ReflectiveOperationException ignored) { }
        }
    }
    //#endif

    private static String stackName(ItemStack stack) {
        Object name = call(stack, "getHoverName");
        if (name == null) name = call(stack, "getName");
        if (name instanceof Text text) return text.getString();
        return name == null ? "" : String.valueOf(name);
    }

    private static ItemStack first(List<ItemStack> slots) {
        for (ItemStack stack : slots) if (stack != null && !stack.isEmpty()) return stack;
        return null;
    }

    private static int parse(String text, int fallback) {
        try { return Integer.parseInt(text); }
        catch (NumberFormatException ex) { return fallback; }
    }

    private static String trim(TextRenderer font, String text, int width) {
        if (font.getWidth(text) <= width) return text;
        String cut = font.trimToWidth(text, Math.max(0, width - font.getWidth("...")));
        return cut + "...";
    }

    private static int fade(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(((color >>> 24) & 0xFF) * alpha)));
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
