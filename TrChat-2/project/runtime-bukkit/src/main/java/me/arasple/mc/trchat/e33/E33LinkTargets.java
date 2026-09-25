package me.arasple.mc.trchat.e33;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps URL destinations available when a TrChat formatter displays [链接]. */
public final class E33LinkTargets {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HIDDEN = Pattern.compile("\\[链接]");

    private E33LinkTargets() {}

    public static List<String> fromMessage(String message) {
        if (message == null || message.isEmpty()) return List.of();
        List<String> links = new ArrayList<>();
        Matcher matcher = URL.matcher(message);
        while (matcher.find() && links.size() < 16) {
            String target = normalize(matcher.group());
            if (isWebUrl(target)) links.add(target);
        }
        return List.copyOf(links);
    }

    /** Accept client supplied targets only for the chat text the server actually processed. */
    public static List<String> fromMatchedMessage(String original, String filtered) {
        if (original == null || filtered == null) return List.of();
        List<String> links = fromMessage(original);
        if (links.isEmpty()) return links;
        String masked = URL.matcher(original).replaceAll("[链接]");
        if (!filtered.equals(masked)) return List.of();
        return links;
    }

    public static String restorePlaceholders(String filtered, List<String> originalLinks) {
        if (filtered == null || originalLinks == null || originalLinks.isEmpty()) return filtered;
        Matcher hidden = HIDDEN.matcher(filtered);
        StringBuilder out = new StringBuilder(filtered.length());
        int from = 0, link = 0;
        while (hidden.find() && link < originalLinks.size()) {
            out.append(filtered, from, hidden.start()).append(originalLinks.get(link++));
            from = hidden.end();
        }
        if (link == 0) return filtered;
        out.append(filtered, from, filtered.length());
        return out.length() <= 8192 ? out.toString() : filtered;
    }

    public static String normalize(String candidate) {
        String url = candidate.replace("\\_", "_").replace("\\&", "&");
        while (!url.isEmpty() && ".,!?;:，。！？；：)]}".indexOf(url.charAt(url.length() - 1)) >= 0)
            url = url.substring(0, url.length() - 1);
        return url;
    }

    private static boolean isWebUrl(String candidate) {
        if (candidate.length() > 4096) return false;
        try {
            URI uri = URI.create(candidate);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
