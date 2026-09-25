package com.niuqu.chatbubble;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Clickable chat URLs, with bounded previews only for the two trusted sites. */
public final class ChatLinks {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HIDDEN_LINK = Pattern.compile("\\[链接]");
    private static final Pattern BILI_VIDEO = Pattern.compile("(?i)^/video/(BV[0-9a-z]{10}|av[0-9]+)(?:/|$)");
    private static final Pattern META = Pattern.compile("<meta\\s+[^>]{0,2048}>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z:-]+)\\s*=\\s*([\\\"'])(.*?)\\2", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Map<String, CompletableFuture<Preview>> PREVIEWS = new ConcurrentHashMap<>();
    // The server can replace the visible URL with [链接] before it reaches the
    // semantic bridge. Keep only targets from our own recent sends, and apply
    // them only to our own echo. Never guess a target for another player.
    private record OutgoingLinks(List<String> urls, String shape, long sentAt) {}
    private static final ArrayDeque<OutgoingLinks> OUTGOING = new ArrayDeque<>();
    private static final java.util.concurrent.ExecutorService PREVIEW_WORKERS =
        java.util.concurrent.Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "e33chat-link-preview");
            thread.setDaemon(true);
            return thread;
        });
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    public record Preview(String url, String site, String title, String author, String imageUrl) {}

    private ChatLinks() {}

    public static Text linkify(Text source) {
        String plain = source.getString();
        Matcher matcher = URL.matcher(plain);
        MutableText out = Text.empty();
        int at = 0;
        boolean found = false;
        while (matcher.find()) {
            String candidate = trimPunctuation(matcher.group());
            String target = normalizeWebUrl(candidate);
            if (!isWebUrl(target)) continue;
            int end = matcher.start() + candidate.length();
            out.append(ChatMessageStore.sliceStyled(source, at, matcher.start()));
            Text original = ChatMessageStore.sliceStyled(source, matcher.start(), end);
            if (isTrustedDirectUrl(target)) out.append(Text.literal(displayLabel(target)).fillStyle(Style.EMPTY
                .withColor(Formatting.AQUA).withUnderline(true)
                .withClickEvent(openEvent(target)).withHoverEvent(hoverEvent(target))));
            else if (clickUrl(original) != null) out.append(original);
            else out.append(Text.literal(target).fillStyle(Style.EMPTY
                .withColor(Formatting.AQUA).withUnderline(true)
                .withClickEvent(openEvent(target)).withHoverEvent(hoverEvent(target))));
            at = end;
            found = true;
        }
        if (!found) return source;
        out.append(ChatMessageStore.sliceStyled(source, at, plain.length()));
        return out;
    }

    public static String firstCardUrl(String text) {
        Matcher m = URL.matcher(text);
        while (m.find()) {
            String url = normalizeWebUrl(trimPunctuation(m.group()));
            if (url.length() > 2048 || !isWebUrl(url)) continue;
            if (isTrustedDirectUrl(url)) return url;
        }
        return null;
    }

    /** TrChat may show a URL as [链接], with its destination only in the component style. */
    public static String firstCardUrl(Text text) {
        for (String target : clickUrls(text))
            if (isTrustedDirectUrl(target)) return target;
        return firstCardUrl(text.getString());
    }

    /** Restore a target when a server-side formatter kept only a visual [链接] label. */
    public static Text restoreHiddenLinks(Text body, String rawBody, String bodyJson, String renderedJson) {
        Matcher hidden = HIDDEN_LINK.matcher(body.getString());
        List<String> destinations = urlsIn(rawBody);
        if (destinations.isEmpty()) destinations = urlsInJson(bodyJson);
        if (destinations.isEmpty()) destinations = urlsInJson(renderedJson);
        MutableText out = Text.empty();
        int from = 0;
        int nextLink = 0;
        boolean changed = false;
        while (hidden.find()) {
            if (nextLink >= destinations.size()) break;
            String url = destinations.get(nextLink++);
            out.append(ChatMessageStore.sliceStyled(body, from, hidden.start()));
            Text label = ChatMessageStore.sliceStyled(body, hidden.start(), hidden.end());
            String existing = clickUrl(label);
            final String target = existing == null ? url : existing;
            if (!isTrustedDirectUrl(target)) {
                out.append(Text.literal(target).fillStyle(Style.EMPTY.withColor(Formatting.AQUA)
                    .withUnderline(true).withClickEvent(openEvent(target))
                    .withHoverEvent(hoverEvent(target))));
            } else out.append(Text.literal(displayLabel(target)).fillStyle(Style.EMPTY
                .withColor(Formatting.AQUA).withUnderline(true)
                .withClickEvent(openEvent(target)).withHoverEvent(hoverEvent(target))));
            from = hidden.end();
            changed = true;
        }
        if (!changed) return body;
        out.append(ChatMessageStore.sliceStyled(body, from, body.getString().length()));
        return out;
    }

    public static void rememberOutgoing(String message) {
        List<String> urls = urlsIn(message);
        if (urls.isEmpty()) return;
        purgeOutgoing();
        if (OUTGOING.size() >= 16) OUTGOING.removeFirst();
        OUTGOING.addLast(new OutgoingLinks(List.copyOf(urls), URL.matcher(message)
            .replaceAll("[链接]"), System.currentTimeMillis()));
    }

    public static boolean hasWebUrl(String message) { return !urlsIn(message).isEmpty(); }

    public static void clearOutgoing() { OUTGOING.clear(); }

    /** Use a recent local send when the server erased its URL from the echo. */
    public static Text reconcileOwnEcho(Text content) {
        purgeOutgoing();
        if (OUTGOING.isEmpty()) return content;
        List<String> known = urlsIn(content.getString());
        if (known.isEmpty()) known = clickUrls(content);
        if (!known.isEmpty()) {
            String first = normalizeWebUrl(known.get(0));
            OUTGOING.removeIf(sent -> sent.urls().contains(first));
            return content;
        }
        if (!HIDDEN_LINK.matcher(content.getString()).find()) return content;
        OutgoingLinks match = null;
        String visible = content.getString();
        for (OutgoingLinks sent : OUTGOING) {
            if (visible.equals(sent.shape()) || visible.contains(sent.shape())) {
                match = sent;
                break;
            }
        }
        if (match == null && OUTGOING.size() == 1) match = OUTGOING.peekFirst();
        if (match == null) return content;
        OUTGOING.remove(match);
        Text restored = restoreHiddenLinks(content, String.join(" ", match.urls()), "", "");
        E33Log.info("[e33chat] Restored own hidden link from recent send: targets="
            + match.urls().size() + ", card=" + (firstCardUrl(restored) != null));
        return restored;
    }

    private static void purgeOutgoing() {
        long cutoff = System.currentTimeMillis() - 15_000;
        while (!OUTGOING.isEmpty() && OUTGOING.peekFirst().sentAt() < cutoff)
            OUTGOING.removeFirst();
    }

    private static List<String> urlsIn(String raw) {
        List<String> urls = new ArrayList<>();
        if (raw == null) return urls;
        Matcher matcher = URL.matcher(raw);
        while (matcher.find() && urls.size() < 16) {
            String url = normalizeWebUrl(trimPunctuation(matcher.group()));
            if (isWebUrl(url)) urls.add(url);
        }
        return urls;
    }

    private static List<String> urlsInJson(String json) {
        List<String> urls = new ArrayList<>();
        if (json == null || json.isEmpty()) return urls;
        try { collectClickUrls(JsonParser.parseString(json), urls, 0); }
        catch (RuntimeException ignored) { }
        return urls;
    }

    private static void collectClickUrls(JsonElement element, List<String> urls, int depth) {
        if (element == null || depth > 32 || urls.size() >= 16) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectClickUrls(child, urls, depth + 1);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject obj = element.getAsJsonObject();
        JsonElement event = obj.has("clickEvent") ? obj.get("clickEvent") : obj.get("click_event");
        if (event != null && event.isJsonObject()) {
            JsonObject click = event.getAsJsonObject();
            String action = click.has("action") ? click.get("action").getAsString() : "";
            JsonElement value = click.has("url") ? click.get("url") : click.get("value");
            if ("open_url".equalsIgnoreCase(action) && value != null && value.isJsonPrimitive()) {
                String url = normalizeWebUrl(value.getAsString());
                if (isWebUrl(url)) urls.add(url);
            }
        }
        if (obj.has("extra")) collectClickUrls(obj.get("extra"), urls, depth + 1);
        if (obj.has("with")) collectClickUrls(obj.get("with"), urls, depth + 1);
    }

    public static String normalizeWebUrl(String url) {
        return url == null ? "" : url.replace("\\_", "_").replace("\\&", "&");
    }

    /** Drop tracking parameters for metadata requests; opening still uses the full URL. */
    public static String previewRequestUrl(String url) {
        URI uri = URI.create(normalizeWebUrl(url));
        if (site(uri.getHost()) == null) return url;
        if (!"B站".equals(site(uri.getHost())) || uri.getHost().equalsIgnoreCase("b23.tv"))
            return uri.toString();
        Matcher video = BILI_VIDEO.matcher(uri.getPath());
        if (!video.find()) return uri.toString();
        return "https://www.bilibili.com/video/" + video.group(1) + "/";
    }

    private static List<String> clickUrls(Text text) {
        List<String> urls = new ArrayList<>(1);
        text.visit((style, part) -> {
            ClickEvent event = style.getClickEvent();
            //#if MC >= 12105
            if (event instanceof ClickEvent.OpenUrl link) urls.add(link.uri().toString());
            //#else
            //$$ if (event != null && event.getAction() == ClickEvent.Action.OPEN_URL) urls.add(event.getValue());
            //#endif
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return urls;
    }

    private static String clickUrl(Text text) {
        for (String url : clickUrls(text)) if (isWebUrl(url)) return url;
        return null;
    }

    public static boolean isTrustedDirectUrl(String url) {
        if (!isWebUrl(url)) return false;
        URI uri = URI.create(normalizeWebUrl(url));
        return uri.getPort() == -1 && site(uri.getHost()) != null;
    }

    public static String displayLabel(String url) {
        if (!isTrustedDirectUrl(url)) return url;
        URI uri = URI.create(normalizeWebUrl(url));
        if ("B站".equals(site(uri.getHost())))
            return BILI_VIDEO.matcher(uri.getPath()).find()
                ? "这是一个视频，点击打开" : "打开 B站链接";
        return "打开瑶光天工官网";
    }

    public static Preview preview(String url) {
        URI uri = URI.create(normalizeWebUrl(url));
        String site = site(uri.getHost());
        if (site == null) return null;
        Preview fallback = new Preview(url, site, site.equals("B站") ? "哔哩哔哩" : "瑶光天工官网", host(uri), "");
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getPort() != -1) return fallback;
        if (PREVIEWS.size() >= 128 && !PREVIEWS.containsKey(url)) return fallback;
        CompletableFuture<Preview> future = PREVIEWS.computeIfAbsent(url,
            key -> CompletableFuture.supplyAsync(() -> fetch(key, fallback), PREVIEW_WORKERS));
        return future.getNow(fallback);
    }

    public static boolean isWebUrl(String candidate) {
        try {
            URI uri = URI.create(normalizeWebUrl(candidate));
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null && candidate.length() <= 4096;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static Preview fetch(String url, Preview fallback) {
        try {
            URI page = URI.create(previewRequestUrl(url));
            for (int redirects = 0; redirects <= 3; redirects++) {
                HttpRequest request = HttpRequest.newBuilder(page).timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "Mozilla/5.0 E33Chat-LinkPreview")
                    .header("Accept", "text/html")
                    .header("Accept-Encoding", "gzip").GET().build();
                HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream raw = response.body()) {
                    int status = response.statusCode();
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        if (redirects == 3) return fallback;
                        String location = response.headers().firstValue("location").orElse("");
                        URI next = page.resolve(location);
                        if (next.toString().length() > 2048
                            || !"https".equalsIgnoreCase(next.getScheme())
                            || !isTrustedDirectUrl(next.toString())
                            || !fallback.site().equals(site(next.getHost()))) return fallback;
                        page = next;
                        continue;
                    }
                    if (status != 200 || !response.headers().firstValue("content-type")
                        .orElse("").toLowerCase(Locale.ROOT).contains("text/html")) return fallback;
                    InputStream stream = response.headers().firstValue("content-encoding").orElse("")
                        .toLowerCase(Locale.ROOT).contains("gzip") ? new GZIPInputStream(raw) : raw;
                    byte[] bytes = stream.readNBytes(256 * 1024);
                    String html = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    String title = "", author = "", image = "";
                    Matcher tags = META.matcher(html);
                    while (tags.find()) {
                        String property = "", content = "";
                        Matcher attrs = ATTR.matcher(tags.group());
                        while (attrs.find()) {
                            if (attrs.group(1).equalsIgnoreCase("property") || attrs.group(1).equalsIgnoreCase("name")) property = attrs.group(3);
                            if (attrs.group(1).equalsIgnoreCase("content")) content = attrs.group(3);
                        }
                        if (property.equalsIgnoreCase("og:title")) title = clean(content);
                        if (property.equalsIgnoreCase("author") || property.equalsIgnoreCase("og:site_name")) author = clean(content);
                        if (property.equalsIgnoreCase("og:image")) image = content;
                    }
                    if (title.isEmpty()) {
                        Matcher t = TITLE.matcher(html);
                        if (t.find()) title = clean(t.group(1));
                    }
                    if (image.startsWith("//")) image = "https:" + image;
                    if (!allowedImage(image, fallback.site())) image = "";
                    return new Preview(url, fallback.site(), title.isEmpty() ? fallback.title() : title,
                        author.isEmpty() ? fallback.author() : author, image);
                }
            }
        } catch (Exception ignored) {
            return fallback;
        }
        return fallback;
    }

    private static boolean allowedImage(String url, String site) {
        try {
            URI image = URI.create(url);
            String host = host(image);
            return url.length() <= 4096 && "https".equalsIgnoreCase(image.getScheme()) && image.getUserInfo() == null
                && image.getPort() == -1 && (site.equals("B站")
                    ? domain(host, "hdslb.com") || domain(host, "bilibili.com")
                    : domain(host, "uwqq.com"));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String site(String host) {
        if (host == null) return null;
        if (domain(host, "bilibili.com") || host.equalsIgnoreCase("b23.tv")) return "B站";
        return domain(host, "uwqq.com") ? "官网" : null;
    }

    private static boolean domain(String host, String domain) {
        return host.equalsIgnoreCase(domain) || host.toLowerCase(Locale.ROOT).endsWith("." + domain);
    }

    private static String host(URI uri) { return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT); }

    private static String clean(String value) {
        String cleaned = value.replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .replaceAll("<[^>]+>", "").strip();
        return cleaned.substring(0, Math.min(120, cleaned.length()));
    }

    private static String trimPunctuation(String url) {
        while (!url.isEmpty() && ".,!?;:，。！？；：)]}".indexOf(url.charAt(url.length() - 1)) >= 0)
            url = url.substring(0, url.length() - 1);
        return url;
    }

    public static ClickEvent openEvent(String url) {
        //#if MC >= 12105
        return new ClickEvent.OpenUrl(URI.create(normalizeWebUrl(url)));
        //#else
        //$$ return new ClickEvent(ClickEvent.Action.OPEN_URL, url);
        //#endif
    }

    private static HoverEvent hoverEvent(String url) {
        //#if MC >= 12105
        return new HoverEvent.ShowText(Text.literal((isTrustedDirectUrl(url) ? "点击打开：" : "点击确认后打开：") + url));
        //#else
        //$$ return new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal((isTrustedDirectUrl(url) ? "点击打开：" : "点击确认后打开：") + url));
        //#endif
    }
}
