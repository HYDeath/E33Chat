package me.arasple.mc.trchat.neoforge;

import java.util.*;
import java.util.regex.Pattern;

final class Mentions {
    record Hit(int start, int end, PlayerDirectory.Entry player) { }
    private static final Pattern LINKS = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE);
    static List<Hit> find(String text, List<PlayerDirectory.Entry> players) {
        Map<String, PlayerDirectory.Entry> candidates = new HashMap<>();
        Map<String, List<PlayerDirectory.Entry>> displays = new HashMap<>();
        for (var player : players) displays.computeIfAbsent(player.display().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(player);
        displays.forEach((name, entries) -> { if (!name.isBlank() && entries.size() == 1) candidates.put(name, entries.getFirst()); });
        for (var player : players) candidates.put(player.account().toLowerCase(Locale.ROOT), player);
        var names = candidates.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        List<Hit> hits = new ArrayList<>();
        var links = LINKS.matcher(text);
        List<int[]> ranges = new ArrayList<>();
        while (links.find()) ranges.add(new int[]{links.start(), links.end()});
        for (int i = 0; i < text.length() && hits.size() < 128; i++) {
            if (text.charAt(i) != '@' || (i > 0 && word(text.codePointBefore(i)))) continue;
            int position = i;
            if (ranges.stream().anyMatch(r -> position >= r[0] && position < r[1])) continue;
            boolean quoted = i + 1 < text.length() && text.charAt(i + 1) == '"';
            int start = i + (quoted ? 2 : 1);
            for (String name : names) {
                int end = start + name.length();
                if (!text.regionMatches(true, start, name, 0, name.length())) continue;
                if (quoted) { if (end >= text.length() || text.charAt(end) != '"') continue; end++; }
                else if (end < text.length() && word(text.codePointAt(end))) continue;
                hits.add(new Hit(i, end, candidates.get(name))); i = end - 1; break;
            }
        }
        return hits;
    }
    private static boolean word(int c) { return Character.isLetterOrDigit(c) || c == '_'; }
}
