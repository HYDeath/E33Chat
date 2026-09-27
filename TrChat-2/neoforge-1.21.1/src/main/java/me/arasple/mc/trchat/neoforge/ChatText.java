package me.arasple.mc.trchat.neoforge;

import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;

/** Converts configured colors into native components, without modifying substituted components. */
final class ChatText {
    private static final Pattern CODES = Pattern.compile("[&§]#([0-9a-fA-F]{6})|[&§][xX]((?:[&§][0-9a-fA-F]){6})|[&§]([0-9a-fA-Fk-oK-OrR])|\\{(server|prefix|display|player|message)\\}");
    static MutableComponent parse(String text) { return render(text, Map.of()); }
    static MutableComponent render(String text, Map<String, Component> values) { return new Builder().append(text, values).build(); }
    static final class Builder {
        private final MutableComponent result = Component.empty();
        private Style style = Style.EMPTY;
        Builder append(Component component) { result.append(component.copy().withStyle(component.getStyle().applyTo(style))); return this; }
        Builder append(String text) { return append(text, Map.of()); }
        MutableComponent build() { return result; }
        private Builder append(String text, Map<String, Component> values) {
            int offset = 0;
            var matcher = CODES.matcher(text);
            while (matcher.find()) {
                if (matcher.start() > offset) result.append(Component.literal(text.substring(offset, matcher.start())).setStyle(style));
                if (matcher.group(1) != null) style = Style.EMPTY.withColor(Integer.parseInt(matcher.group(1), 16));
                else if (matcher.group(2) != null) {
                    style = Style.EMPTY.withColor(Integer.parseInt(matcher.group(2).replace("&", "").replace("§", ""), 16));
                } else if (matcher.group(3) != null) {
                    ChatFormatting code = ChatFormatting.getByCode(Character.toLowerCase(matcher.group(3).charAt(0)));
                    style = code == ChatFormatting.RESET ? Style.EMPTY : code.isColor() ? Style.EMPTY.applyFormat(code) : style.applyFormat(code);
                } else {
                    Component value = values.get(matcher.group(4));
                    if (value == null) result.append(Component.literal(matcher.group()).setStyle(style)); else append(value);
                }
                offset = matcher.end();
            }
            if (offset < text.length()) result.append(Component.literal(text.substring(offset)).setStyle(style));
            return this;
        }
    }
}
