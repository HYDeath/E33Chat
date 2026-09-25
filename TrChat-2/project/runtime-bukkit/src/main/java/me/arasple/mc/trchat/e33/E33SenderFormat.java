package me.arasple.mc.trchat.e33;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plain text substitution before the bridge converts legacy colour codes. */
final class E33SenderFormat {
    private static final Pattern VARIABLE = Pattern.compile(
        "\\{(server_name|title|player_id|display_name)\\}");

    private E33SenderFormat() {}

    static String format(String template, String serverName, String title,
                         String playerId, String displayName) {
        Map<String, String> values = Map.of("server_name", serverName, "title", title,
            "player_id", playerId, "display_name", displayName);
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(result,
            Matcher.quoteReplacement(values.get(matcher.group(1))));
        matcher.appendTail(result);
        return result.toString().trim();
    }
}
