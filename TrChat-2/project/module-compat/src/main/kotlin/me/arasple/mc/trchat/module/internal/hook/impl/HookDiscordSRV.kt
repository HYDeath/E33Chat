package me.arasple.mc.trchat.module.internal.hook.impl

import me.arasple.mc.trchat.module.internal.hook.Hook
import me.arasple.mc.trchat.module.internal.hook.HookAbstract
import taboolib.common.platform.Platform

@Hook([Platform.BUKKIT])
class HookDiscordSRV : HookAbstract() {

    fun registerListener(listener: Any) {
        if (!isHooked) return
        runCatching {
            val apiClass = Class.forName("github.scarsz.discordsrv.DiscordSRV")
            val api = apiClass.getMethod("getApi").invoke(null)
            api.javaClass.getMethod("subscribe", Any::class.java).invoke(api, listener)
        }
    }

}
