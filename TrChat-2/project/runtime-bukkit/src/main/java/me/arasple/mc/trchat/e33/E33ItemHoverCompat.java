package me.arasple.mc.trchat.e33;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.entity.Player;

/**
 * Minecraft 26.3 is protocol 777. 26.1, 26.1.1 and 26.1.2 all share protocol 775,
 * and 26.2 is 776. Explorer maps such as the Dappled Forest Camp Map are the item
 * {@code minecraft:abandoned_camp_map}, which those registries do not contain.
 * A {@code show_item} hover is decoded with the chat packet, so an unknown id
 * disconnects the client before the line can render.
 */
public final class E33ItemHoverCompat {
    public static final int PROTOCOL_26_3 = 777;
    private static final int BRIDGE_JSON_LIMIT = 16_384;
    private static final Set<String> ADDED_IN_26_3 = Set.of(
        "abandoned_camp_map", "abandoned_campsite_map",
        "buried_ancient_city_map", "ancient_city_map",
        "buried_mineshaft_map", "mineshaft_map",
        "buried_treasure_map",
        "buried_trial_chambers_map", "trial_explorer_map",
        "desert_pyramid_map",
        "desert_village_map",
        "jungle_pyramid_map", "jungle_explorer_map",
        "ocean_monument_map", "ocean_explorer_map",
        "plains_village_map",
        "savanna_village_map",
        "snowy_village_map",
        "swamp_hut_map", "swamp_explorer_map",
        "taiga_village_map",
        "warm_ocean_ruins_map",
        "woodland_mansion_map", "woodland_explorer_map",
        "shelf_mushroom", "red_shrub", "straw_bed", "cushion"
    );

    private static volatile boolean viaChecked;
    private static volatile Object viaApi;
    private static volatile Method playerVersion;

    private E33ItemHoverCompat() {}

    public static boolean needsLegacyHover(String itemId) {
        String path = path(itemId);
        if (path.isEmpty()) return false;
        if (ADDED_IN_26_3.contains(path)) return true;
        return path.contains("poplar")
            || path.endsWith("_wool_stairs") || path.endsWith("_wool_slab")
            || path.endsWith("_concrete_stairs") || path.endsWith("_concrete_slab")
            || path.endsWith("_cushion");
    }

    public static int protocol(Player player) {
        if (player == null) return PROTOCOL_26_3;
        try {
            if (!viaChecked) {
                viaChecked = true;
                Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
                viaApi = via.getMethod("getAPI").invoke(null);
                if (viaApi != null)
                    playerVersion = viaApi.getClass().getMethod("getPlayerVersion", UUID.class);
            }
            if (viaApi == null || playerVersion == null) return PROTOCOL_26_3;
            Object version = playerVersion.invoke(viaApi, player.getUniqueId());
            if (version instanceof Integer protocol && protocol > 0) return protocol;
        } catch (Throwable ignored) {
            viaChecked = true;
        }
        return PROTOCOL_26_3;
    }

    public static Component componentFor(Player player, Component component) {
        return forProtocol(component, protocol(player));
    }

    public static Component forProtocol(Component component, int protocol) {
        if (component == null || protocol >= PROTOCOL_26_3) return component;
        return rewrite(component);
    }

    public static E33Protocol.BridgeChat adaptBridge(E33Protocol.BridgeChat chat, Player receiver) {
        if (chat == null) return null;
        int protocol = protocol(receiver);
        if (protocol >= PROTOCOL_26_3) return chat;
        String bodyJson = rewriteJson(chat.bodyJson(), protocol);
        String renderedJson = rewriteJson(chat.renderedJson(), protocol);
        if (bodyJson.equals(chat.bodyJson()) && renderedJson.equals(chat.renderedJson())) return chat;
        return new E33Protocol.BridgeChat(chat.messageId(), chat.sender(), chat.account(), chat.displayName(),
            chat.origin(), chat.body(), bodyJson, chat.privateMessage(), chat.recipient(),
            chat.quoteSender(), chat.quoteContent(), chat.mentions(), chat.nameplate(),
            chat.displayJson(), renderedJson);
    }

    private static String rewriteJson(String json, int protocol) {
        if (json == null || json.isEmpty() || !mayNeedRewrite(json)) return json == null ? "" : json;
        try {
            Component parsed = GsonComponentSerializer.gson().deserialize(json);
            Component rewritten = forProtocol(parsed, protocol);
            if (rewritten == parsed) return json;
            String next = GsonComponentSerializer.gson().serialize(rewritten);
            if (next.length() <= BRIDGE_JSON_LIMIT) return next;
            return GsonComponentSerializer.gson().serialize(Component.text(plain(rewritten)));
        } catch (RuntimeException ignored) {
            return json;
        }
    }

    private static boolean mayNeedRewrite(String json) {
        return json.contains("show_item") || json.contains("filled_map.") || json.contains("poplar")
            || json.contains("wool_stairs") || json.contains("wool_slab")
            || json.contains("concrete_stairs") || json.contains("concrete_slab")
            || json.contains("_cushion") || json.contains("straw_bed")
            || json.contains("shelf_mushroom") || json.contains("red_shrub")
            || json.contains("_map");
    }

    private static Component rewrite(Component component) {
        List<Component> children = component.children();
        boolean changed = false;
        List<Component> nextChildren = children;
        if (!children.isEmpty()) {
            List<Component> rewritten = new ArrayList<>(children.size());
            for (Component child : children) {
                Component next = rewrite(child);
                rewritten.add(next);
                if (next != child) changed = true;
            }
            if (changed) nextChildren = rewritten;
        }
        Component current = changed ? component.children(nextChildren) : component;
        if (current instanceof TranslatableComponent translatable && literalKey(translatable.key())) {
            current = Component.text(translate(translatable.key())).style(current.style()).children(current.children());
            changed = true;
        }
        HoverEvent<?> hover = current.hoverEvent();
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_ITEM && legacyHover(hover)) {
            String label = plain(current);
            if (label.isBlank() || label.startsWith("filled_map.") || label.startsWith("item.minecraft.")
                || label.startsWith("block.minecraft."))
                label = "物品";
            current = current.hoverEvent(HoverEvent.showText(Component.text(label)));
        }
        return current;
    }

    private static boolean legacyHover(HoverEvent<?> hover) {
        Object value = hover.value();
        if (!(value instanceof HoverEvent.ShowItem show)) return false;
        return needsLegacyHover(show.item().asString());
    }

    private static boolean literalKey(String key) {
        if (key == null || key.isEmpty()) return false;
        if (key.startsWith("filled_map.")) return true;
        int dot = key.lastIndexOf('.');
        if (dot < 0 || dot == key.length() - 1) return false;
        if (!key.startsWith("item.minecraft.") && !key.startsWith("block.minecraft.")) return false;
        return needsLegacyHover(key.substring(dot + 1));
    }

    private static String translate(String key) {
        try {
            Class<?> type = Class.forName("net.minecraft.locale.Language");
            Object language = type.getMethod("getInstance").invoke(null);
            if (language == null) return key;
            for (Method method : type.getMethods()) {
                if (!"getOrDefault".equals(method.getName()) || method.getParameterCount() != 1) continue;
                if (method.getParameterTypes()[0] != String.class) continue;
                Object value = method.invoke(language, key);
                if (value instanceof String text && !text.isBlank()) return text;
            }
        } catch (Throwable ignored) { }
        return key;
    }

    private static String plain(Component component) {
        StringBuilder out = new StringBuilder();
        appendPlain(component, out);
        return out.toString().trim();
    }

    private static void appendPlain(Component component, StringBuilder out) {
        if (component instanceof TextComponent text) out.append(text.content());
        else if (component instanceof TranslatableComponent translatable) out.append(translatable.key());
        for (Component child : component.children()) appendPlain(child, out);
    }

    private static String path(String itemId) {
        if (itemId == null) return "";
        String id = itemId.trim().toLowerCase(Locale.ROOT);
        int separator = id.indexOf(':');
        return separator >= 0 ? id.substring(separator + 1) : id;
    }
}
