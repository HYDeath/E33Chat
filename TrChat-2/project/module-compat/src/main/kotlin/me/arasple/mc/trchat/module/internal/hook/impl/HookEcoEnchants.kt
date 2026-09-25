package me.arasple.mc.trchat.module.internal.hook.impl

import com.willfp.eco.core.display.Display
import me.arasple.mc.trchat.module.internal.hook.Hook
import me.arasple.mc.trchat.module.internal.hook.type.HookDisplayItem
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.Platform
import taboolib.platform.util.isAir

/**
 * @author ItsFlicker
 * @since 2022/2/5 22:30
 */
@Hook([Platform.BUKKIT])
class HookEcoEnchants : HookDisplayItem() {

    override fun displayItem(item: ItemStack, player: Player): ItemStack {
        if (!isHooked || item.isAir()) {
            return item
        }
        return try {
            Display.displayAndFinalize(item, player)
        } catch (_: Throwable){
            Display.displayAndFinalize(item)
        }
    }
}