package me.arasple.mc.trchat.module.internal.proxy.redis

import me.arasple.mc.trchat.util.ArrayConverter
import taboolib.library.configuration.Conversion
import java.util.UUID

class TrRedisMessage @JvmOverloads constructor(
    @Conversion(ArrayConverter::class)
    val data: Array<String>,
    // Nullable because legacy publishers omit this field and deserialization
    // deliberately bypasses constructors. NeoForge uses its own envelope ID.
    val messageId: String? = UUID.randomUUID().toString(),
    val neoId: String? = null
)
