package me.arasple.mc.trchat.module.internal.proxy.redis

import me.arasple.mc.trchat.api.impl.BukkitProxyManager
import me.arasple.mc.trchat.module.internal.proxy.ProxyMessageDeduplicator
import org.bukkit.Bukkit
import taboolib.platform.util.bukkitPlugin
import me.arasple.mc.trchat.module.conf.file.Settings
import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.expansion.AlkaidRedis
import taboolib.expansion.SingleRedisConnection
import taboolib.expansion.SingleRedisConnector
import taboolib.expansion.fromConfig
import taboolib.module.configuration.ConfigNode

@PlatformSide(Platform.BUKKIT)
object RedisManager {

    private var connector: SingleRedisConnector? = null
    private var subscribedConnection: SingleRedisConnection? = null
    private val receivedMessages = ProxyMessageDeduplicator()
    @Volatile
    private var stopped = false
    @Volatile
    var connection: SingleRedisConnection? = null
        private set
    var channel = "trchat-message"

    @ConfigNode("Redis.enabled", "settings.yml")
    var enabled = false
        private set

    @Synchronized
    operator fun invoke(default: Boolean = true): SingleRedisConnection? {
        if (!enabled || stopped) {
            return null
        }
        connection?.let {
            if (default) init(it)
            return it
        }
        return try {
            if (connector == null) {
                connector = AlkaidRedis.create().apply {
                    fromConfig(Settings.conf.getConfigurationSection("Redis")!!)
                }
            }
            connection = connector!!.connect().connection()
            if (default) init(connection!!)
            connection
        } catch (ex: Exception) {
            disconnect()
            Bukkit.getLogger().warning("TrChat Redis unavailable; continuing with local chat: ${ex.message}")
            null
        }
    }

    @Synchronized
    fun init(connection: SingleRedisConnection) {
        if (this.connection !== connection || subscribedConnection === connection) return
        subscribedConnection = connection
        connection.subscribe(channel) {
            if (this@RedisManager.connection !== connection) return@subscribe
            val message = try {
                get<TrRedisMessage>(ignoreConstructor = true)
            } catch (ex: Exception) {
                Bukkit.getLogger().warning("Rejected malformed TrChat Redis event: ${ex.message}")
                return@subscribe
            }
            if (!receivedMessages.accept(message.messageId ?: message.neoId)) return@subscribe
            Bukkit.getGlobalRegionScheduler().run(bukkitPlugin) {
                if (this@RedisManager.connection === connection)
                    BukkitProxyManager.processor?.execute(message.data)
            }
        }
    }

    fun sendMessage(message: TrRedisMessage): Boolean {
        if (enabled && !stopped) {
            return try {
                (connection ?: RedisManager())?.publish(channel, message) ?: return false
                true
            } catch (ex: Exception) {
                // Alkaid reconnects the connection and its subscription internally.
                // Replacing it here leaves the old subscriber alive, causing every
                // reconnect to add another delivery of the same public message.
                Bukkit.getLogger().warning("TrChat Redis publish failed: ${ex.message}")
                false
            }
        }
        return false
    }

    @Awake(LifeCycle.DISABLE)
    @Synchronized
    fun close() {
        stopped = true
        disconnect()
    }

    private fun disconnect() {
        val previous = connection
        connection = null
        subscribedConnection = null
        kotlin.runCatching { previous?.close() }
        kotlin.runCatching { connector?.close() }
        connector = null
    }

}
