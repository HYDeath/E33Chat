package me.arasple.mc.trchat.e33;

import java.lang.reflect.Method;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/**
 * CraftEngine keeps {@code item_model} and custom model data on the client.
 * The server stack is often still the base material (a stick), so a chat hover
 * built from it renders the vanilla model. This asks CraftEngine for the same
 * client view its inventory packets use, then copies only the vanilla model
 * fields onto the original stack.
 */
public final class E33CraftEngineItems {
    private E33CraftEngineItems() {}

    public static ItemStack present(ItemStack item, Player viewer) {
        if (item == null || item.getType().isAir()) return item;
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("CraftEngine");
            if (plugin == null || !plugin.isEnabled()) return item;
            ItemStack client = clientView(plugin, item.clone(), viewer);
            if (client == null || client.getType().isAir()) return item;
            return applyAppearance(item, client);
        } catch (Throwable ignored) {
            return item;
        }
    }

    private static ItemStack clientView(Plugin plugin, ItemStack copy, Player viewer) throws ReflectiveOperationException {
        ClassLoader loader = plugin.getClass().getClassLoader();
        Class<?> managerClass = Class.forName("net.momirealms.craftengine.bukkit.item.BukkitItemManager", true, loader);
        Object manager = managerClass.getMethod("instance").invoke(null);
        if (manager == null) return null;
        Method s2c = null;
        for (Method method : managerClass.getMethods()) {
            if (!"s2c".equals(method.getName()) || method.getParameterCount() != 2) continue;
            if (!ItemStack.class.isAssignableFrom(method.getParameterTypes()[0])) continue;
            s2c = method;
            break;
        }
        if (s2c == null) return null;
        Object result = s2c.invoke(manager, copy, craftPlayer(loader, viewer));
        ItemStack stack = unwrap(result);
        return stack != null ? stack : copy;
    }

    private static Object craftPlayer(ClassLoader loader, Player viewer) {
        if (viewer == null) return null;
        for (String name : new String[] {
            "net.momirealms.craftengine.bukkit.api.BukkitAdaptor",
            "net.momirealms.craftengine.bukkit.plugin.user.BukkitAdaptors"
        }) {
            try {
                Class<?> type = Class.forName(name, true, loader);
                for (Method method : type.getMethods()) {
                    if (!"adapt".equals(method.getName()) || method.getParameterCount() != 1) continue;
                    if (!method.getParameterTypes()[0].isInstance(viewer)) continue;
                    return method.invoke(null, viewer);
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static ItemStack unwrap(Object result) {
        if (result instanceof ItemStack stack) return stack;
        if (result instanceof Optional<?> optional) {
            Object value = optional.orElse(null);
            if (value instanceof ItemStack stack) return stack;
            Object inner = call(value, "getItem");
            if (inner instanceof ItemStack stack) return stack;
            inner = call(value, "platformItem");
            if (inner instanceof ItemStack stack) return stack;
        }
        return null;
    }

    /**
     * Keep the server item's name and data, and add the model the resource pack selects.
     * If this server's item API cannot see {@code item_model}, use CraftEngine's client stack.
     */
    private static ItemStack applyAppearance(ItemStack server, ItemStack client) {
        int amount = Math.max(1, server.getAmount());
        ItemStack visual = server.clone();
        try {
            if (!client.getType().isAir() && client.getType() != server.getType()) {
                ItemStack swapped = new ItemStack(client.getType(), amount);
                ItemMeta meta = server.hasItemMeta() ? server.getItemMeta() : null;
                if (meta != null) swapped.setItemMeta(meta);
                visual = swapped;
            }
        } catch (Throwable ignored) { }
        boolean modelCopied = false;
        try {
            ItemMeta meta = visual.getItemMeta();
            ItemMeta from = client.hasItemMeta() ? client.getItemMeta() : null;
            if (meta != null && from != null) {
                copyCustomModelData(from, meta);
                modelCopied = copyItemModel(from, meta);
                if (modelCopied || visual.getType() != server.getType()) visual.setItemMeta(meta);
            }
        } catch (Throwable ignored) { }
        // Older packs only have custom model data, which stays on CraftEngine's client stack.
        // item_model is copied above so the hover does not also carry CraftEngine's network tag.
        if (!modelCopied) {
            if (client.getAmount() != amount) client.setAmount(amount);
            return client;
        }
        if (visual.getAmount() != amount) visual.setAmount(amount);
        return visual;
    }

    private static boolean copyCustomModelData(ItemMeta from, ItemMeta to) {
        try {
            Object present = from.getClass().getMethod("hasCustomModelData").invoke(from);
            if (!Boolean.TRUE.equals(present)) return false;
            Object value = from.getClass().getMethod("getCustomModelData").invoke(from);
            if (!(value instanceof Number number) || number.intValue() <= 0) return false;
            int data = number.intValue();
            for (Method method : to.getClass().getMethods()) {
                if (!"setCustomModelData".equals(method.getName()) || method.getParameterCount() != 1) continue;
                Class<?> param = method.getParameterTypes()[0];
                if (param == Integer.class || param == int.class) {
                    method.invoke(to, data);
                    return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean copyItemModel(ItemMeta from, ItemMeta to) {
        try {
            Object key = null;
            for (Method method : from.getClass().getMethods()) {
                if ("getItemModel".equals(method.getName()) && method.getParameterCount() == 0) {
                    key = method.invoke(from);
                    break;
                }
            }
            if (key == null) return false;
            for (Method method : to.getClass().getMethods()) {
                if (!"setItemModel".equals(method.getName()) || method.getParameterCount() != 1) continue;
                if (!method.getParameterTypes()[0].isInstance(key)) continue;
                method.invoke(to, key);
                return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static Object call(Object target, String name) {
        if (target == null) return null;
        try { return target.getClass().getMethod(name).invoke(target); }
        catch (ReflectiveOperationException ignored) { return null; }
    }
}
