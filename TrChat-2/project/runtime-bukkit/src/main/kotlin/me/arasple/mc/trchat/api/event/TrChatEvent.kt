package me.arasple.mc.trchat.api.event

import me.arasple.mc.trchat.module.display.ChatSession
import me.arasple.mc.trchat.module.display.channel.Channel
import me.arasple.mc.trchat.module.display.channel.PrivateChannel
import taboolib.module.chat.ComponentText
import taboolib.module.chat.Components
import taboolib.platform.type.BukkitProxyEvent

/**
 * TrChatEvent
 * me.arasple.mc.trchat.api.event
 *
 * 在 ChannelEvents 前触发
 * component 仅包含玩家消息部分
 *
 * @author ItsFlicker
 * @since 2021/8/20 20:53
 */
class TrChatEvent(
    val channel: Channel,
    val session: ChatSession,
    var component: ComponentText,
    @Deprecated("Only for chat preview")
    val forward: Boolean = true
) : BukkitProxyEvent() {

    val player = session.player

    init {
        if (channel is PrivateChannel) {
            val event = TrChatPrivateEvent(channel, session, component)
            event.call()
            this.isCancelled = event.isCancelled
            this.component = event.component
        }
    }

    fun getMessage() = component.toLegacyText()

    fun setMessage(message: String) {
        component = Components.text(message)
    }
}