package me.arasple.mc.trchat.module.display.channel

import me.arasple.mc.trchat.TrChat
import me.arasple.mc.trchat.api.event.TrChatEvent
import me.arasple.mc.trchat.e33.E33Bridge
import me.arasple.mc.trchat.api.event.TrChatSendEvent
import me.arasple.mc.trchat.api.impl.BukkitProxyManager
import me.arasple.mc.trchat.module.conf.file.Settings
import me.arasple.mc.trchat.module.display.channel.obj.*
import me.arasple.mc.trchat.module.display.format.Format
import me.arasple.mc.trchat.module.display.format.MsgComponent
import me.arasple.mc.trchat.module.display.function.Function
import me.arasple.mc.trchat.module.internal.TrChatBukkit
import me.arasple.mc.trchat.module.internal.data.ChatLogs
import me.arasple.mc.trchat.module.internal.service.Metrics
import me.arasple.mc.trchat.util.*
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import taboolib.common.platform.command.PermissionDefault
import taboolib.common.platform.command.command
import taboolib.common.platform.function.adaptPlayer
import taboolib.common.platform.function.console
import taboolib.common.platform.function.getProxyPlayer
import taboolib.common.util.Strings
import taboolib.common.util.subList
import taboolib.module.chat.ComponentText
import taboolib.module.chat.Components
import taboolib.module.lang.sendLang
import taboolib.platform.util.onlinePlayers
import taboolib.platform.util.sendLang

/**
 * @author ItsFlicker
 * @since 2021/12/11 22:27
 */
open class Channel(
    val id: String,
    val settings: ChannelSettings,
    val bindings: ChannelBindings,
    val events: ChannelEvents,
    val formats: List<Format>,
    val consoleFormat: List<Format>
) {

    var isUnregistered = false

    val listeners: MutableSet<String> = mutableSetOf()

    open fun init() {
        registerCommand()
        onlinePlayers.filter { it.session.channel == id }.forEach {
            join(it, this, hint = false)
        }
        if (settings.alwaysListen) {
            onlinePlayers.forEach {
                if (canListen(it)) {
                    listeners.add(it.name)
                }
            }
        }
    }

    open fun registerCommand() {
        if (bindings.command.isNullOrEmpty() || TrChatBukkit.isActivated) return
        command(
            name = bindings.command[0],
            aliases = subList(bindings.command, 1),
            description = "TrChat channel $id",
            permission = "trchat.command.channel.${id.lowercase()}",
            permissionDefault = PermissionDefault.TRUE
        ) {
            execute<Player> { sender, _, _ ->
                if (sender.session.channel == id) {
                    quit(sender, setDefault = true)
                } else {
                    join(sender, id)
                }
            }
            dynamic("message", optional = true) {
                execute<CommandSender> { sender, _, argument ->
                    val channel = channels[id] ?: return@execute
                    if (sender is Player) {
                        channel.execute(sender, argument)
                    } else {
                        channel.execute(sender, argument)
                    }
                }
            }
            incorrectSender { sender, _ ->
                sender.sendLang("Command-Not-Player")
            }
        }
    }

    open fun canListen(player: Player): Boolean {
        return player.passPermission(settings.listenPermission)
    }

    open fun canSpeak(player: Player): Boolean {
        return if (settings.speakCondition.isEmpty()) {
            player.passPermission(settings.joinPermission)
        } else {
            settings.speakCondition.pass(player)
        }
    }

    open fun execute(sender: CommandSender, message: String): ChannelExecuteResult {
        Function.clearMentioned()
        if (sender is Player) {
            return execute(sender, message)
        }
        val component = Components.empty()
        consoleFormat.firstOrNull()?.let { format ->
            format.prefix.forEach { prefix ->
                component.append(prefix.value[0].content.toTextComponent(sender)) }
            component.append((format.msg[0].content as MsgComponent).createComponent(sender, message, settings.disabledFunctions))
            format.suffix.forEach { suffix ->
                component.append(suffix.value[0].content.toTextComponent(sender)) }
        } ?: return ChannelExecuteResult(failedReason = ChannelExecuteResult.FailReason.NO_FORMAT)

        if (settings.proxy && BukkitProxyManager.processor != null) {
            BukkitProxyManager.sendBroadcastRaw(
                onlinePlayers.firstOrNull(),
                nilUUID,
                component,
                settings.listenPermission,
                settings.doubleTransfer,
                settings.ports
            )
        } else {
            listeners.forEach { getProxyPlayer(it)?.sendComponent(null, component) }
            sender.sendComponent(null, component)
        }
        return ChannelExecuteResult.success(component)
    }

    open fun execute(player: Player, message: String, toConsole: Boolean = true): ChannelExecuteResult {
        return execute(player, Components.text(message), toConsole)
    }

    open fun execute(player: Player, message: ComponentText, toConsole: Boolean = true): ChannelExecuteResult {
        Function.clearMentioned()
        E33Bridge.rememberMessage(player.uniqueId, null)
        E33Bridge.rememberOriginalLinks(player.uniqueId, message.toPlainText())
        var plain = message.toPlainText()
        if (!checkLimits(player, plain)) {
            return ChannelExecuteResult(failedReason = ChannelExecuteResult.FailReason.LIMITED)
        }
        val session = player.session
        session.lastChannel = this
        session.lastPublicMessage = plain
        session.similarPeriodMessages.add(System.currentTimeMillis() to plain)
        val event = TrChatEvent(this, session, message)
        if (!event.call()) {
            return ChannelExecuteResult(failedReason = ChannelExecuteResult.FailReason.EVENT)
        }
        var msg = events.process(player, event.component)
            ?: return ChannelExecuteResult(failedReason = ChannelExecuteResult.FailReason.EVENT)
        plain = TrChat.api().getFilterManager().filter(msg.toPlainText()).filtered
        if (plain != msg.toPlainText()) msg = Components.text(plain)
        if (!player.data.isShadowMuted) E33Bridge.rememberMessage(player.uniqueId, plain)
        ChatLogs.logNormal(player.name, plain)
        Metrics.increase(0)

        var component = Components.empty()
        var mentioned = emptySet<String>()
        formats.firstOrNull { it.condition.pass(player) }?.let { format ->
            format.prefix
                .mapNotNull { prefix -> prefix.value.firstOrNull { it.condition.pass(player) }?.content?.toTextComponent(player) }
                .forEach { prefix -> component.append(prefix) }
            format.msg.firstOrNull { it.condition.pass(player) }
                ?.let {
                    val body = (it.content as MsgComponent).createComponent(player, msg, settings.disabledFunctions)
                    component.append(body)
                    E33Bridge.rememberBody(player.uniqueId, body.toAdventureObject())
                }
                ?: return ChannelExecuteResult(failedReason = ChannelExecuteResult.FailReason.NO_FORMAT)
            mentioned = Function.takeMentioned()
            format.suffix
                .mapNotNull { suffix -> suffix.value.firstOrNull { it.condition.pass(player) }?.content?.toTextComponent(player) }
                .forEach { suffix -> component.append(suffix) }
        } ?: return ChannelExecuteResult(failedReason = ChannelExecuteResult.FailReason.NO_FORMAT)
        if (session.cancelChat) {
            session.cancelChat = false
            return ChannelExecuteResult(failedReason = ChannelExecuteResult.FailReason.EVENT)
        }
        E33Bridge.rememberMentions(player.uniqueId, mentioned)
        val sendEvent = TrChatSendEvent(this, session, component)
        if (!sendEvent.call()) {
            return ChannelExecuteResult(failedReason = ChannelExecuteResult.FailReason.EVENT)
        }
        component = sendEvent.component
        E33Bridge.takeQuoteNotifyTarget(player.uniqueId)?.let { mentioned = mentioned + it }
        if (Components.useAdventure) {
            component = taboolib.module.chat.impl.AdventureComponent(
                E33Bridge.expandCraftEmoji(player, component.toAdventureObject()))
        }
        E33Bridge.rememberRendered(player.uniqueId, component.toAdventureObject())

        if (player.data.isShadowMuted) {
            if (events.send(player, player.name, plain)) {
                player.sendComponent(player, component)
                if (player.name in mentioned) {
                    BukkitProxyManager.sendProxyLang(player, player.name, "Function-Mention-Notify", player.name)
                }
            }
            if (toConsole) {
                console().sendComponent(player, component)
            }
            return ChannelExecuteResult(failedReason = ChannelExecuteResult.FailReason.LIMITED)
        }

        // Proxy
        if (settings.proxy) {
            if (BukkitProxyManager.processor != null || settings.forceProxy) {
                BukkitProxyManager.sendBroadcastRaw(
                    player,
                    player.uniqueId,
                    component,
                    settings.listenPermission,
                    settings.doubleTransfer,
                    settings.ports,
                    senderName = player.name,
                    mentioned = mentioned.joinToString(","),
                    bridgeChat = E33Bridge.stagedBase64(player.uniqueId)
                )
                E33Bridge.headBubble(player, plain)
                return ChannelExecuteResult.success(component)
            }
        }
        // Local
        when (settings.range.type) {
            ChannelRange.Type.ALL -> {
                val receivers = listeners.filter { events.send(player, it, plain) }
                receivers.forEach {
                    getProxyPlayer(it)?.sendComponent(player, component)
                }
                notifyMentioned(player, receivers, mentioned)
            }
            ChannelRange.Type.SINGLE_WORLD -> {
                val receivers = onlinePlayers.filter { it.name in listeners
                        && it.world == player.world
                        && events.send(player, it.name, plain) }
                receivers.forEach {
                    it.sendComponent(player, component)
                }
                notifyMentioned(player, receivers.map { it.name }, mentioned)
            }
            ChannelRange.Type.DISTANCE -> {
                val receivers = onlinePlayers.filter { it.name in listeners
                        && it.world == player.world
                        && it.location.distance(player.location) <= settings.range.distance
                        && events.send(player, it.name, plain) }
                receivers.forEach {
                    it.sendComponent(player, component)
                }
                notifyMentioned(player, receivers.map { it.name }, mentioned)
            }
            ChannelRange.Type.SELF -> {
                if (events.send(player, player.name, plain)) {
                    player.sendComponent(player, component)
                    notifyMentioned(player, listOf(player.name), mentioned)
                }
            }
        }
        if (toConsole) {
            console().sendComponent(player, component)
        }
        E33Bridge.headBubble(player, plain)
        return ChannelExecuteResult.success(component)
    }

    /** 只有真正收到（能看到）该消息且被 @ 的玩家才会收到提示 */
    protected fun notifyMentioned(sender: Player, receivers: Collection<String>, mentioned: Set<String>) {
        if (mentioned.isEmpty()) return
        receivers.filter { it in mentioned && !E33Bridge.isV1Client(it) }.forEach {
            BukkitProxyManager.sendProxyLang(sender, it, "Function-Mention-Notify", sender.name)
        }
    }

    open fun checkLimits(player: Player, message: String): Boolean {
        if (player.hasPermission("trchat.bypass.*")) {
            return true
        }
        if (!player.checkMute()) {
            return false
        }
        if (!canSpeak(player)) {
            player.sendLang("Channel-No-Speak-Permission")
            return false
        }
        if (settings.filterBeforeSending && TrChat.api().getFilterManager().filter(message, adaptPlayer(player)).sensitiveWords > 0) {
            player.sendLang("Channel-Bad-Language")
            return false
        }
        if (!player.hasPermission("trchat.bypass.chatlength")) {
            if (message.length > Settings.chatLengthLimit) {
                player.sendLang("General-Too-Long", message.length, Settings.chatLengthLimit)
                return false
            }
        }
        if (!player.hasPermission("trchat.bypass.repeat")) {
            val session = player.session
            if (Settings.chatSimilarity > 0 && Settings.chatSimilarity <= 1) {
                val period = Settings.chatSimilarityPeriod.get().takeIf { it > 0 } ?: 60000L
                val now = System.currentTimeMillis()
                session.similarPeriodMessages.removeAll { now - it.first > period }
                // 是否检测周期内所有消息的相似度：true 检测周期内所有消息（更严格）；false 只检测相邻两条消息
                val similar = if (Settings.chatSimilarityCompareAll) {
                    session.similarPeriodMessages.any { Strings.similarDegree(it.second, message) >= Settings.chatSimilarity }
                } else {
                    Strings.similarDegree(session.lastPublicMessage, message) >= Settings.chatSimilarity
                }
                if (similar) {
                    if (session.similarPeriodStart == 0L || now - session.similarPeriodStart >= period) {
                        session.similarPeriodStart = now
                        session.similarCountInPeriod = 0
                    }
                    if (session.similarCountInPeriod >= Settings.chatSimilarityMaxPerPeriod) {
                        player.sendLang("General-Too-Similar")
                        return false
                    }
                    session.similarCountInPeriod++
                }
            }
        }
        if (!player.hasPermission("trchat.bypass.duplicate")) {
            val maxRepeat = Settings.chatDuplicatePhraseMaxRepeat
            if (maxRepeat > 0 && message.length >= 2) {
                val whitelist = Settings.chatDuplicatePhraseWhitelist.toSet()
                if (maxConsecutiveRepeat(message, whitelist) > maxRepeat) {
                    player.sendLang("General-Too-Duplicate")
                    return false
                }
            }
        }
        if (!player.hasPermission("trchat.bypass.chatcd")) {
            val chatCooldown = player.getCooldownLeft(CooldownType.CHAT)
            if (chatCooldown > 0) {
                player.sendLang("Cooldowns-Chat", chatCooldown / 1000)
                return false
            }
        }
        if (Function.functions.any { !it.checkCooldown(player, message) }) {
            return false
        }
        // 消息发送频率检查
        if (!player.hasPermission("trchat.bypass.highfrequency")) {
            val max = Settings.chatHighFrequencyMaxPerPeriod
            if (max > 0) {
                val session = player.session
                val period = Settings.chatHighFrequencyPeriod.get().takeIf { it > 0 } ?: 60000L
                val now = System.currentTimeMillis()
                if (session.totalPeriodStart == 0L || now - session.totalPeriodStart >= period) {
                    session.totalPeriodStart = now
                    session.totalCountInPeriod = 0
                }
                if (session.totalCountInPeriod >= max) {
                    player.sendLang("General-Too-Frequent")
                    return false
                }
                session.totalCountInPeriod++
            }
        }
        player.updateCooldown(CooldownType.CHAT, Settings.chatCooldown.get())
        return true
    }

    open fun unregister() {
        isUnregistered = true
        listeners.clear()
    }

    companion object {

        val channels = mutableMapOf<String, Channel>()

        fun join(player: Player, channel: String, hint: Boolean = true): Boolean {
            val id = channels.keys.firstOrNull { channel.equals(it, ignoreCase = true) } ?: return false
            return join(player, channels[id]!!, hint)
        }

        fun join(player: Player, channel: Channel, hint: Boolean = true): Boolean {
            if (!player.passPermission(channel.settings.joinPermission)) {
                if (hint) {
                    player.sendLang("General-No-Permission")
                }
                return false
            }
            quit(player, hint = false)
            player.session.setChannel(channel)
            channel.listeners.add(player.name)
            channel.events.join(player)

            if (hint) {
                player.sendLang("Channel-Join", channel.id)
            }
            return true
        }

        fun quit(player: Player, setDefault: Boolean = false, hint: Boolean = true) {
            player.session.getChannel()?.let {
                if (!it.settings.alwaysListen) {
                    it.listeners -= player.name
                }
                it.events.quit(player)
                if (hint) {
                    player.sendLang("Channel-Quit", it.id)
                }
            }
            if (!setDefault || !join(player, Settings.defaultChannel)) {
                player.session.setChannel(null)
            }
        }
    }
}

// 检查重复的子字符串并统计
private fun maxConsecutiveRepeat(message: String, whitelist: Set<String>): Int {
    val n = message.length
    if (n < 2) return 1
    var max = 1
    val checkWhitelist = whitelist.isNotEmpty()
    var i = 0
    while (i < n) {
        // 剪枝①：剩余字符数不足超过当前最大值，后续起点不可能更大
        if (n - i <= max) break

        val maxLen = (n - i) / 2
        var len = 1
        while (len <= maxLen) {
            // 剪枝②：当前长度下理论最大重复次数不超过 max，后续长度更不可能
            val maxPossible = (n - i) / len
            if (maxPossible <= max) break

            if (checkWhitelist && isWhitelistedUnit(message, i, len, whitelist)) {
                len++
                continue
            }
            var count = 1
            var j = i + len
            while (j + len <= n && message.regionMatches(j, message, j - len, len)) {
                count++
                j += len
            }
            if (count > max) max = count
            len++
        }
        i++
    }
    return max
}

// 判断重复单元是否被白名单豁免
private fun isWhitelistedUnit(message: String, start: Int, len: Int, whitelist: Set<String>): Boolean {
    for (w in whitelist) {
        val wl = w.length
        if (wl == 0 || len < wl || len % wl != 0) continue
        val repeat = len / wl
        var ok = true
        var k = 0
        while (k < repeat) {
            if (!message.regionMatches(start + k * wl, w, 0, wl)) {
                ok = false
                break
            }
            k++
        }
        if (ok) return true
    }
    return false
}
