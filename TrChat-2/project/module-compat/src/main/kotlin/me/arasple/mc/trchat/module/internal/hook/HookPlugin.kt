package me.arasple.mc.trchat.module.internal.hook

import me.arasple.mc.trchat.module.internal.hook.type.HookDisplayItem
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.common.io.runningClassesWithoutLibrary
import taboolib.common.platform.Platform
import taboolib.common.platform.function.console
import taboolib.common.platform.function.runningPlatform
import taboolib.module.lang.sendLang
import java.util.function.BiFunction

/**
 * @author Arasple
 * @date 2021/1/26 22:04
 */
object HookPlugin {

    val registry = runningClassesWithoutLibrary
        .filter { it.hasAnnotation(Hook::class.java) }
        .filter { runningPlatform in it.getAnnotation(Hook::class.java).enumList<Platform>("platforms") }
        .map { it.newInstance() as HookAbstract }
        .toMutableList()

    fun printInfo() {
        registry.filter { it.isHooked }.forEach {
            it.init()
            console().sendLang("Plugin-Dependency-Hooked", it.name)
        }
    }

    fun addHook(element: HookAbstract) {
        registry.add(element)
        console().sendLang("Plugin-Dependency-Hooked", element.name)
    }

    fun registerDisplayItemHook(name: String, func: BiFunction<ItemStack, Player, ItemStack>) {
        addHook(object : HookDisplayItem() {
            override fun getPluginName(): String {
                return name
            }
            override fun displayItem(item: ItemStack, player: Player): ItemStack {
                return func.apply(item, player)
            }
        })
    }
}