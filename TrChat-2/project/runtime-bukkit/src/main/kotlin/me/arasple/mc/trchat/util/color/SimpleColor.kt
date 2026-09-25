package me.arasple.mc.trchat.util.color

import java.awt.Color

class SimpleColor(
    val r: Int,
    val g: Int,
    val b: Int,
    val a: Int
) {

    constructor(r: Int, g: Int, b: Int) : this(r, g, b, (0.25f * 0xff).toInt())

    constructor(color: Color, hasAlpha: Boolean) : this(color.red, color.green, color.blue, if (hasAlpha) color.alpha else (0.25f * 0xff).toInt())

    fun toJavaColor(): Color {
        return Color(r, g, b, a)
    }

    companion object {

        @JvmStatic
        val NONE = SimpleColor(0, 0, 0, 0)

        fun fromARGB(argb: Int, hasAlpha: Boolean): SimpleColor {
            return SimpleColor(Color(argb, hasAlpha), hasAlpha)
        }

        fun fromRGBA(hex: String): SimpleColor {
            val r = hex.substring(0, 2).toIntOrNull(16) ?: 0
            val g = hex.substring(2, 4).toIntOrNull(16) ?: 0
            val b = hex.substring(4, 6).toIntOrNull(16) ?: 0
            val a = hex.substring(6, 8).toIntOrNull(16) ?: 0
            return SimpleColor(r, g, b, a)
        }

    }

}