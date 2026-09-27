package me.arasple.mc.trchat.neoforge;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

final class ChatPermissions {
    static final PermissionNode<Boolean> ADMIN = node("admin", false);
    static final PermissionNode<Boolean> CHAT = node("chat", true);
    static final PermissionNode<Boolean> PRIVATE = node("private", true);
    static final PermissionNode<Boolean> GROUP = node("group", true);
    static final PermissionNode<Boolean> BYPASS = node("bypass", false);
    private static PermissionNode<Boolean> node(String name, boolean normal) {
        return new PermissionNode<>("trchat", name, PermissionTypes.BOOLEAN,
            (player, uuid, context) -> normal || player != null && player.hasPermissions(2));
    }
    static void register(PermissionGatherEvent.Nodes event) { event.addNodes(ADMIN, CHAT, PRIVATE, GROUP, BYPASS); }
    static boolean has(ServerPlayer player, PermissionNode<Boolean> node) { return PermissionAPI.getPermission(player, node); }
    static boolean listen(ServerPlayer player, String permission) {
        if (permission.isBlank()) return true;
        if (permission.equals("trchat.chat")) return has(player, CHAT);
        if (permission.equals("trchat.admin")) return has(player, ADMIN);
        if (permission.equals("trchat.private")) return has(player, PRIVATE);
        if (permission.equals("trchat.group")) return has(player, GROUP);
        // Bukkit permission nodes have no general NeoForge equivalent. Fail closed.
        return player.hasPermissions(2);
    }
}
