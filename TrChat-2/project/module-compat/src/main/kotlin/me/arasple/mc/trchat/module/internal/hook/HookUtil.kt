package me.arasple.mc.trchat.module.internal.hook

import me.arasple.mc.trchat.module.internal.hook.impl.HookDiscordSRV
import me.arasple.mc.trchat.module.internal.hook.impl.HookItemsAdder
import me.arasple.mc.trchat.module.internal.hook.type.HookVanish
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import taboolib.common.util.unsafeLazy
import java.util.*

val hookDiscordSRV by unsafeLazy { HookPlugin.registry.filterIsInstance<HookDiscordSRV>().first() }
val hookItemsAdder by unsafeLazy { HookPlugin.registry.filterIsInstance<HookItemsAdder>().first() }

fun OfflinePlayer.isVanished(): Boolean {
    HookPlugin.registry.filterIsInstance<HookVanish>().forEach {
        if (it.isVanished(this)) return true
    }
    return false
}

fun UUID.isVanished(): Boolean {
    return Bukkit.getOfflinePlayer(this).isVanished()
}

fun String.isVanished(): Boolean {
    return Bukkit.getOfflinePlayer(this).isVanished()
}