package me.arasple.mc.trchat.module.internal.hook.type

import me.arasple.mc.trchat.module.internal.hook.HookAbstract
import org.bukkit.OfflinePlayer

abstract class HookVanish : HookAbstract() {

    abstract fun isVanished(player: OfflinePlayer): Boolean
}