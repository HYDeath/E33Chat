package me.arasple.mc.trchat.neoforge;

import java.util.*;

final class PlayerDirectory {
    record Entry(String account, String display, UUID uuid, String server) { }
    private record Node(long seen, List<Entry> players) { }
    private final Map<String, Node> nodes = new HashMap<>();

    void update(String[] data, long now) {
        if (data.length < 5) throw new IllegalArgumentException("Short directory event");
        List<Entry> players = new ArrayList<>();
        if (!data[2].isEmpty()) {
            String[] accounts = data[2].split(",", -1), displays = data[3].split(",", -1), ids = data[4].split(",", -1);
            if (accounts.length != displays.length || accounts.length != ids.length || accounts.length > 2048)
                throw new IllegalArgumentException("Directory length mismatch");
            for (int i = 0; i < accounts.length; i++)
                players.add(new Entry(accounts[i], displays[i].equals("#") ? accounts[i] : displays[i],
                    parseUuid(ids[i]), data.length > 5 ? data[5] : data[1]));
        }
        nodes.put(data[1], new Node(now, players));
    }
    List<Entry> players(long now) {
        nodes.values().removeIf(node -> now - node.seen > 30000);
        return nodes.values().stream().flatMap(node -> node.players.stream()).toList();
    }
    static UUID parseUuid(String value) {
        if (value.length() == 32) value = value.substring(0, 8) + "-" + value.substring(8, 12) + "-"
            + value.substring(12, 16) + "-" + value.substring(16, 20) + "-" + value.substring(20);
        return UUID.fromString(value);
    }
}
