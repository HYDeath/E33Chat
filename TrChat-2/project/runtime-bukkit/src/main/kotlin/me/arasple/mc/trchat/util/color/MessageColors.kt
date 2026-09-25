package me.arasple.mc.trchat.util.color

import org.bukkit.command.CommandSender
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide

/**
 * @author Arasple
 * @date 2019/8/15 20:52
 */
@PlatformSide(Platform.BUKKIT)
object MessageColors {

    const val COLOR_PERMISSION_NODE = "trchat.color."
    const val FORCE_CHAT_COLOR_PERMISSION_NODE = "trchat.color.force-defaultcolor."
    private val STRIP_COLOR_PATTERN = Regex("&[0-9A-FK-ORX]", RegexOption.IGNORE_CASE)

    private val specialColors = arrayOf(
        "simple",
        "rainbow",
        "gradients",
        "hex",
        "legacy",
        "chat",
        "anvil",
        "sign",
        "book"
    )

    @JvmOverloads
    fun replaceWithPermission(sender: CommandSender, strings: List<String>, type: Type = Type.CHAT): List<String> {
        return strings.map { replaceWithPermission(sender, it, type) }
    }

    @JvmOverloads
    fun replaceWithPermission(sender: CommandSender, s: String, type: Type = Type.CHAT): String {
        return replaceWithPermission(sender, s, listOf(COLOR_PERMISSION_NODE, type.node))
    }

    private fun replaceWithPermission(sender: CommandSender, s: String, nodes: List<String>): String {
        if (nodes.any { node -> sender.hasPermission("$node*") }) {
            return s.colorify()
        }
        var string = s

        // 2025/7/14 必须清除无权限的颜色，否则会被CustomColor连带处理
        string = if (nodes.any { node -> sender.hasPermission(node + "rainbow") }) {
            string.parseRainbow()
        } else {
            string.replace(HexUtils.RAINBOW_PATTERN.toRegex(), "")
        }

        string = if (nodes.any { node -> sender.hasPermission(node + "gradients") }) {
            string.parseGradients()
        } else {
            string.replace(HexUtils.GRADIENT_PATTERN.toRegex(), "")
        }

        nodes.forEach { node ->
            getColors(sender, node).forEach { color ->
                string = string.replace(color, CustomColor.get(color).color)
            }
        }

        if (nodes.any { node -> sender.hasPermission(node + "hex") }) {
            string = string.parseHex()
        } else {
            HexUtils.HEX_PATTERNS.forEach { string = string.replace(it.toRegex(), "") }
        }

        string = if (nodes.any { node -> sender.hasPermission(node + "legacy") }) {
            string.parseLegacy()
        } else {
            string.replace(STRIP_COLOR_PATTERN, "")
        }

        return string
    }

    private fun getColorsFromPermissions(sender: CommandSender, prefix: String): List<String> {
        sender.recalculatePermissions()
        return sender.effectivePermissions.mapNotNull {
            val permission = it.permission
            if (permission.startsWith(prefix)) {
                permission.removePrefix(prefix).let { color -> if (color.length == 1) "&$color" else color }
            } else {
                null
            }
        }.filter { it !in specialColors }
    }

    fun getColors(sender: CommandSender, node: String = COLOR_PERMISSION_NODE): List<String> {
        return getColorsFromPermissions(sender, node)
    }

    fun getForceColors(sender: CommandSender): List<String> {
        return getColorsFromPermissions(sender, FORCE_CHAT_COLOR_PERMISSION_NODE)
    }

    enum class Type(val node: String) {
        CHAT(COLOR_PERMISSION_NODE + "chat."),
        ANVIL(COLOR_PERMISSION_NODE + "anvil."),
        SIGN(COLOR_PERMISSION_NODE + "sign."),
        BOOK(COLOR_PERMISSION_NODE + "book.")
    }
}