package me.arasple.mc.trchat.module.internal.hook.impl

import me.arasple.mc.trchat.module.internal.hook.Hook
import me.arasple.mc.trchat.module.internal.hook.HookAbstract
import org.bukkit.entity.Player
import taboolib.common.platform.Platform

/**
 * @author ItsFlicker
 * @since 2022/2/5 22:30
 */
@Hook([Platform.BUKKIT])
class HookItemsAdder : HookAbstract() {

    fun replaceFontImages(message: String, player: Player?): String {
        if (!isHooked) {
            return message
        }
        return try {
            val wrapper = Class.forName("dev.lone.itemsadder.api.FontImages.FontImageWrapper")
            if (player == null) {
                wrapper.getMethod("replaceFontImages", String::class.java).invoke(null, message) as String
            } else {
                wrapper.getMethod("replaceFontImages", Player::class.java, String::class.java)
                    .invoke(null, player, message) as String
            }
        } catch (_: Throwable) {
            message
        }
    }

//    fun replaceFontImages(message: ComponentText, player: Player?): String {
//
//    }
}
