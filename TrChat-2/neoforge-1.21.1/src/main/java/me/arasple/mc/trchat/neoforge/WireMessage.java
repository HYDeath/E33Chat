package me.arasple.mc.trchat.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;

/** TabooLib FAST_JSON TrRedisMessage envelope; extra fields are ignored by Bukkit. */
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
        JsonObject object = JsonParser.parseString(text).getAsJsonObject();
        JsonArray array = object.getAsJsonArray("data");
        if (array == null || array.isEmpty() || array.size() > 32) throw new IllegalArgumentException("Invalid data array");
        String[] data = new String[array.size()];
        for (int i = 0; i < data.length; i++) {
            if (!array.get(i).isJsonPrimitive() || !array.get(i).getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Non-string event field");
            data[i] = array.get(i).getAsString();
            if (data[i].length() > 131068) throw new IllegalArgumentException("Event field too large");
        }
        return new WireMessage(object.has("neoNode") ? object.get("neoNode").getAsString() : "",
            object.has("neoId") ? object.get("neoId").getAsString() : "", data);
    }
}
