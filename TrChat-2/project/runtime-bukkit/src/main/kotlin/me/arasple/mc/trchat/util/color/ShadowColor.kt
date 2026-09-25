package me.arasple.mc.trchat.util.color

import taboolib.common.platform.function.warning
import taboolib.common.util.orNull
import taboolib.common.util.t
import taboolib.module.chat.StandardColors
import taboolib.module.chat.parseToHexColor
import java.awt.Color

fun String.parseToShadowColor(): SimpleColor {
    if (this.lowercase() == "false") {
        return SimpleColor.NONE
    }
    val args = this.split(':', limit = 2)
    return when (args.size) {
        1 -> args[0].parseHexToColor()
        2 -> {
            val color = Color(args[0].parseToHexColor(), false)
            val alpha = args[1].toFloatOrNull() ?: 0.25f
            SimpleColor(color.red, color.green, color.blue, (alpha * 0xff).toInt())
        }
        else -> SimpleColor.NONE
    }
}

private fun String.parseHexToColor(): SimpleColor {
    // HEX: #ffffff / #ffffffff
    if (startsWith('#')) {
        val hex = substring(1)
        when (hex.length) {
            6 -> return SimpleColor.fromARGB(hex.toIntOrNull(16) ?: 0, false)
            8 -> return SimpleColor.fromRGBA(hex)
        }
    }
    // NAMED: white
    val knownColor = StandardColors.match(this)
    if (knownColor.orNull()?.toChatColor()?.color != null) {
        return SimpleColor.fromARGB(knownColor.get().toChatColor().color.rgb, false)
    }
    warning(
        """
        $this 不是一个阴影颜色。
        $this is not a shadow color.
        """.t()
    )
    return SimpleColor.NONE
}