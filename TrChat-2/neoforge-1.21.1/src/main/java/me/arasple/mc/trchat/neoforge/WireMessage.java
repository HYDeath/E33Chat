package me.arasple.mc.trchat.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;

/**
 * TabooLib FAST_JSON {@code TrRedisMessage} envelope.
 * Bukkit writes {@code neoId:null} and, for a one-element payload, a JSON string instead of an array.
 * NightConfig's minimal writer also leaves most control characters raw inside strings.
 */
public record WireMessage(String node, String id, String[] data) {
    public static String encode(String node, String... data) {
        JsonObject object = new JsonObject();
        JsonArray array = new JsonArray();
        for (String value : data) array.add(value);
        object.add("data", array);
        object.addProperty("neoNode", node);
        object.addProperty("neoId", UUID.randomUUID().toString());
        return object.toString();
    }

    public static WireMessage decode(String text) {
        if (text.length() > 262144) throw new IllegalArgumentException("Redis event too large");
        JsonObject object = JsonParser.parseString(escapeRawControls(text)).getAsJsonObject();
        String id = textOrEmpty(object, "messageId");
        if (id.isEmpty()) id = textOrEmpty(object, "neoId");
        return new WireMessage(textOrEmpty(object, "neoNode"), id, readData(object));
    }

    private static String[] readData(JsonObject object) {
        if (!object.has("data") || object.get("data").isJsonNull()) throw new IllegalArgumentException("Invalid data array");
        var element = object.get("data");
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) return new String[] { bounded(element.getAsString()) };
        if (!element.isJsonArray()) throw new IllegalArgumentException("Invalid data array");
        JsonArray array = element.getAsJsonArray();
        if (array.isEmpty() || array.size() > 32) throw new IllegalArgumentException("Invalid data array");
        String[] data = new String[array.size()];
        for (int i = 0; i < data.length; i++) {
            if (!array.get(i).isJsonPrimitive() || !array.get(i).getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Non-string event field");
            data[i] = bounded(array.get(i).getAsString());
        }
        return data;
    }

    /** Gson rejects raw controls. NightConfig only escapes quote, backslash, tab, CR, and LF. */
    private static String escapeRawControls(String text) {
        StringBuilder escaped = null;
        boolean inString = false, slash = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString && slash) {
                slash = false;
                if (escaped != null) escaped.append(c);
                continue;
            }
            if (inString && c == '\\') slash = true;
            else if (c == '"') inString = !inString;
            if (inString && !slash && c < 0x20) {
                if (escaped == null) {
                    escaped = new StringBuilder(text.length() + 8);
                    escaped.append(text, 0, i);
                }
                escaped.append(String.format("\\u%04x", (int) c));
                continue;
            }
            if (escaped != null) escaped.append(c);
        }
        return escaped == null ? text : escaped.toString();
    }

    private static String textOrEmpty(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive() || !object.get(key).getAsJsonPrimitive().isString()) return "";
        return object.get(key).getAsString();
    }

    private static String bounded(String value) {
        if (value.length() > 131068) throw new IllegalArgumentException("Event field too large");
        return value;
    }
}
