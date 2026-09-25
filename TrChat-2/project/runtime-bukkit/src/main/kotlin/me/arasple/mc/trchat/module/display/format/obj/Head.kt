package me.arasple.mc.trchat.module.display.format.obj

import me.arasple.mc.trchat.module.internal.script.Condition
import me.arasple.mc.trchat.module.internal.script.kether.KetherHandler
import me.arasple.mc.trchat.util.setPlaceholders
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import taboolib.common.util.replaceWithOrder
import taboolib.common5.cbool
import taboolib.common5.util.parseUUID
import taboolib.module.chat.ComponentText
import taboolib.module.chat.Components

class Head(content: String, condition: Condition?) : Text(content.split(':')[0], condition) {

    val hat = (content.split(':').getOrNull(1) ?: "true").cbool

    override fun process(sender: CommandSender, vararg vars: String): ComponentText {
        var text = KetherHandler.parseInline(content, sender)
        if (sender is Player && dynamic) {
            text = text.setPlaceholders(sender)
        }
        text = text.replaceWithOrder(*vars)
        val uuid = text.parseUUID()
        return if (uuid != null) {
            Components.empty().appendHead(id = uuid, hat = hat)
        } else if (text.contains('/')) {
            Components.empty().appendHead(texture = text, hat = hat)
        } else {
            Components.empty().appendHead(name = text, hat = hat)
        }
    }

}