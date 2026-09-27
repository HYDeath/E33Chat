package me.arasple.mc.trchat.neoforge;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Native NeoForge names. Account identity is kept separate from the styled display. */
final class PlayerNames {
    private PlayerNames() { }

    static String account(ServerPlayer player) { return player.getGameProfile().getName(); }

    static Component display(ServerPlayer player) {
        // NeoForge already applies NameFormat, scoreboard teams, prefixes and suffixes here.
        Component name = player.getDisplayName();
        return name == null || name.getString().isBlank() ? Component.literal(account(player)) : name.copy();
    }

    static String plain(ServerPlayer player) { return display(player).getString(); }
}
