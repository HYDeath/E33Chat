package me.arasple.mc.trchat.e33;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.arasple.mc.trchat.module.display.function.standard.EnderChestShow;
import me.arasple.mc.trchat.module.display.function.standard.InventoryShow;
import me.arasple.mc.trchat.module.display.function.standard.ItemShow;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.Style;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Attaches a compact slot snapshot so the E33 client can draw TrChat previews in the bubble. */
public final class E33ItemPreview {
    static final String MARK = "e33.show/1/";

    private E33ItemPreview() {}

    public static Component attach(Component component) {
        if (component == null) return null;
        List<Component> children = new ArrayList<>(component.children());
        Component current = component.children(List.of());
        String snapshot = snapshot(current.style());
        if (snapshot != null && current.style().insertion() == null)
            current = current.style(current.style().insertion(snapshot));
        for (Component child : children) current = current.append(attach(child));
        return current;
    }

    private static String snapshot(Style style) {
        String command = command(style.clickEvent());
        if (command == null) return null;
        String[] parts = command.trim().split("\\s+");
        if (parts.length < 2) return null;
        String head = parts[0].toLowerCase(Locale.ROOT);
        if (head.startsWith("/")) head = head.substring(1);
        String id = parts[1];
        Inventory inventory;
        String kind;
        if (head.startsWith("view-inventory")) {
            inventory = InventoryShow.INSTANCE.getCache().getIfPresent(id);
            kind = "inv";
        } else if (head.startsWith("view-enderchest")) {
            inventory = EnderChestShow.INSTANCE.getCache().getIfPresent(id);
            kind = "ender";
        } else if (head.startsWith("view-item")) {
            inventory = ItemShow.INSTANCE.getCacheInventory().getIfPresent(id);
            kind = shulker(inventory) ? "shulker" : null;
        } else return null;
        if (kind == null || inventory == null) return null;
        StringBuilder encoded = new StringBuilder();
        int count = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (count > 0) encoded.append('\u001e');
            encoded.append(slot(inventory.getItem(slot)));
            count++;
            if (encoded.length() > 7000) return null;
        }
        return MARK + kind + "/9/" + encoded;
    }

    private static boolean shulker(Inventory inventory) {
        if (inventory == null || inventory.getSize() != 27) return false;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType().name().equals("GRAY_STAINED_GLASS_PANE")) return true;
        }
        return false;
    }

    private static String slot(ItemStack item) {
        if (item == null || item.getType().isAir() || placeholder(item)) return "-";
        String name = "";
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName())
            name = item.getItemMeta().getDisplayName().replaceAll("(?i)§.", "");
        name = name.replace('\u001e', ' ').replace('\u001f', ' ').replace('\n', ' ').replace('\r', ' ');
        if (name.length() > 32) name = name.substring(0, 32);
        return item.getType().getKey() + "\u001f" + item.getAmount() + "\u001f" + modelData(item) + "\u001f" + name;
    }

    private static boolean placeholder(ItemStack item) {
        String type = item.getType().name();
        if (!type.endsWith("STAINED_GLASS_PANE")) return false;
        if (!item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return true;
        String name = item.getItemMeta().getDisplayName();
        return name == null || name.isBlank() || name.equals("§f") || name.equals("§f ");
    }

    private static int modelData(ItemStack item) {
        try {
            if (!item.hasItemMeta()) return 0;
            Object meta = item.getItemMeta();
            Object present = meta.getClass().getMethod("hasCustomModelData").invoke(meta);
            if (!Boolean.TRUE.equals(present)) return 0;
            Object value = meta.getClass().getMethod("getCustomModelData").invoke(meta);
            return value instanceof Number number ? number.intValue() : 0;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return 0;
        }
    }

    private static String command(ClickEvent click) {
        if (click == null) return null;
        try {
            Object action = click.getClass().getMethod("action").invoke(click);
            if (action != null && !String.valueOf(action).contains("RUN_COMMAND")) return null;
        } catch (ReflectiveOperationException ignored) { }
        for (String name : new String[] {"value", "command"}) {
            try {
                Object value = click.getClass().getMethod(name).invoke(click);
                if (value instanceof String text && !text.isBlank()) return text;
            } catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }
}
