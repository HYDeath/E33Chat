package me.arasple.mc.trchat.api.event

import me.arasple.mc.trchat.module.display.ChatSession
import me.arasple.mc.trchat.module.display.channel.Channel
import taboolib.module.chat.ComponentText
import taboolib.module.chat.Components
import taboolib.platform.type.BukkitProxyEvent

/**
 * TrChatSendEvent
 * me.arasple.mc.trchat.api.event
 *
 * 在消息链构造完成后触发
 * component 包含整条即将发送的聊天消息
 *
 */
class TrChatSendEvent(
    val channel: Channel,
    val session: ChatSession,
    var component: ComponentText,
    val type: Type = Type.COMMON
) : BukkitProxyEvent() {

    val player = session.player

    fun getMessage() = component.toLegacyText()

    fun setMessage(message: String) {
        component = Components.text(message)
    }

    enum class Type {
        COMMON, SENDER, RECEIVER
    }

}