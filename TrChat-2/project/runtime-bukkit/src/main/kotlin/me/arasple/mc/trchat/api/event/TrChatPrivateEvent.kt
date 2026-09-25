package me.arasple.mc.trchat.api.event

import me.arasple.mc.trchat.module.display.ChatSession
import me.arasple.mc.trchat.module.display.channel.Channel
import taboolib.module.chat.ComponentText
import taboolib.module.chat.Components
import taboolib.platform.type.BukkitProxyEvent

class TrChatPrivateEvent(
    val channel: Channel,
    val session: ChatSession,
    var component: ComponentText
) : BukkitProxyEvent() {

    val sender = session.player
    var receiver
        get() = session.lastPrivateTo
        set(value) { session.lastPrivateTo = value }

    var message = component.toLegacyText()
        set(value) {
            field = value
            component = Components.text(value)
        }

}