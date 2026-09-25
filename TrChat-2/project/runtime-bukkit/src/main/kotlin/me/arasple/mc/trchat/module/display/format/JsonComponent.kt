package me.arasple.mc.trchat.module.display.format

import me.arasple.mc.trchat.module.display.format.obj.Head
import me.arasple.mc.trchat.module.display.format.obj.Style
import me.arasple.mc.trchat.module.display.format.obj.Style.Companion.applyTo
import me.arasple.mc.trchat.module.display.format.obj.Text
import me.arasple.mc.trchat.util.pass
import org.bukkit.command.CommandSender
import taboolib.module.chat.ComponentText
import taboolib.module.chat.Components

/**
 * @author Arasple
 * @date 2019/11/30 12:42
 */
open class JsonComponent(
    val text: List<Text>? = null,
    val head: List<Head>? = null,
    val style: List<Style>
) {

    open fun toTextComponent(sender: CommandSender, vararg vars: String): ComponentText {
        val component = Components.empty()
        head?.firstOrNull { it.condition.pass(sender) }?.process(sender, *vars)?.let { component += it }
        text?.firstOrNull { it.condition.pass(sender) }?.process(sender, *vars)?.let { component += it }
        style.forEach {
            it.applyTo(component, sender, *vars)
        }
        return component
    }
}