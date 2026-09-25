package me.arasple.mc.trchat.module.internal.proxy.redis

import me.arasple.mc.trchat.api.impl.BukkitProxyManager
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
    var connection: SingleRedisConnection? = null
    var channel = "trchat-message"

    @ConfigNode("Redis.enabled", "settings.yml")
    var enabled = false
        private set

    operator fun invoke(default: Boolean = true): SingleRedisConnection? {
        if (!enabled) {
            return null
        }
        if (connector == null) {
            connector = AlkaidRedis.create().apply {
                fromConfig(Settings.conf.getConfigurationSection("Redis")!!)
            }
        }
        return try {
            connection?.close()
            connection = connector!!.connect().connection()
            if (default) init(connection!!)
            connection
        } catch (ex: Exception) {
            connection = null
            Bukkit.getLogger().warning("TrChat Redis unavailable; continuing with local chat: ${ex.message}")
            null
        }
    }

    fun init(connection: SingleRedisConnection) {
        connection.subscribe(channel) {
            val message = get<TrRedisMessage>(ignoreConstructor = true)
            Bukkit.getGlobalRegionScheduler().run(bukkitPlugin) {
                BukkitProxyManager.processor?.execute(message.data)
            }
        }
    }

    fun sendMessage(message: TrRedisMessage): Boolean {
        if (enabled) {
            return try {
                (connection ?: RedisManager())?.publish(channel, message) ?: return false
                true
            } catch (ex: Exception) {
                connection = null
                Bukkit.getLogger().warning("TrChat Redis publish failed: ${ex.message}")
                false
            }
        }
        return false
    }

    @Awake(LifeCycle.DISABLE)
    fun close() {
        connection?.close()
    }

}
