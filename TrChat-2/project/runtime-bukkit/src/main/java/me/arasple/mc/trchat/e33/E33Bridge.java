package me.arasple.mc.trchat.e33;

import me.arasple.mc.trchat.api.event.TrChatSendEvent;
import me.arasple.mc.trchat.module.display.channel.Channel;
import me.arasple.mc.trchat.module.display.channel.PrivateChannel;
import me.arasple.mc.trchat.module.internal.command.main.CommandReply;
import me.arasple.mc.trchat.module.internal.proxy.redis.RedisManager;
import me.arasple.mc.trchat.module.internal.proxy.redis.TrRedisMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import taboolib.expansion.SingleRedisConnection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Optional E33 client bridge. TrChat remains the chat owner and public API. */
public final class E33Bridge implements Listener, PluginMessageListener {
    private static final String PREFIX = "e33chat:";
    private static final String HISTORY_KEY = "e33chat:v1:history";
    private static final String SETTINGS_KEY = "e33chat:v1:settings";
    private static final int CHUNK = 30_000;
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"']+", Pattern.CASE_INSENSITIVE);
    private static volatile E33Bridge instance;

    private final Plugin plugin;
    private final String serverId;
    private final String nodeId = UUID.randomUUID().toString();
    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private final java.util.Set<String> modClients = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> bridgeClients = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Alias> aliases = new ConcurrentHashMap<>();
    private final Map<String, Long> seenRemote = new ConcurrentHashMap<>();
    private final Map<UUID, String> rawMessages = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> originalLinks = new ConcurrentHashMap<>();
    private final Map<UUID, String> bodyJsons = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> resolvedMentions = new ConcurrentHashMap<>();
    private final Map<UUID, String> quoteNotifyTargets = new ConcurrentHashMap<>();
    private final Map<UUID, StagedChat> stagedChats = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> privateIds = new ConcurrentHashMap<>();
    private final Map<UUID, E33Protocol.Quote> quotes = new ConcurrentHashMap<>();
    private final Deque<E33Protocol.HistoryEntry> localHistory = new ArrayDeque<>();
    private volatile E33Protocol.Settings settings;
    private volatile boolean nameplatesOwnBubbles = true;
    private record Alias(String account, String nickname, long seenAt) {}
    private record StagedChat(byte[] bytes, long createdAt) {}

    private E33Bridge(Plugin plugin) {
        this.plugin = plugin;
        // A backend port is an implementation detail, not a player-facing server
        // name. Operators can opt in to a label through e33.server-id.
        this.serverId = plugin.getConfig().getString("e33.server-id", "").trim();
        this.settings = new E33Protocol.Settings(false, true, false,
            List.of("{prefix}{display_name}{sep}{content}"),
            List.of("[☬] [我 ➦ {target}] {content}",
                "[☬] [{sender} ➥ 我] {content}"), true, true);
        try {
            String local = plugin.getConfig().getString("e33.settings");
            if (local != null) this.settings = E33Protocol.settings(Base64.getDecoder().decode(local));
        } catch (Exception ex) {
            plugin.getLogger().warning("Invalid local E33 settings; using defaults");
        }
        try {
            String saved = redis() != null ? redis().get(SETTINGS_KEY) : null;
            if (saved != null) this.settings = E33Protocol.settings(Base64.getDecoder().decode(saved));
        } catch (Exception ex) {
            plugin.getLogger().warning("E33 shared settings unavailable: " + ex.getMessage());
        }
    }

    public static void enable(Plugin plugin) {
        E33Bridge bridge = new E33Bridge(plugin);
        instance = bridge;
        for (String channel : List.of("quote_sync", "server_config_save", "media_request", "bridge_hello_v1", "emoji_catalog", "bubble_action"))
            Bukkit.getMessenger().registerIncomingPluginChannel(plugin, PREFIX + channel, bridge);
        for (String channel : List.of("chat_meta", "chat_history", "config_sync", "config_sync_v2",
            "server_config_screen", "media_upload_ack", "media_response", "media_cap",
            "bridge_ready_v1", "bridge_chat_v1", "bridge_chat_v2", "emoji_catalog", "bubble_catalog", "downstream_v3"))
            Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, PREFIX + channel);
        Bukkit.getPluginManager().registerEvents(bridge, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) bridge.onJoinPlayer(player);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            long cutoff = System.currentTimeMillis() - 30_000;
            bridge.aliases.entrySet().removeIf(entry -> !bridge.players.containsKey(entry.getKey())
                && entry.getValue().seenAt() < cutoff);
            bridge.seenRemote.entrySet().removeIf(entry -> entry.getValue() < System.currentTimeMillis() - 60_000);
            for (Player player : bridge.players.values())
                player.getScheduler().run(plugin, ignored -> bridge.updateAlias(player), null);
        }, 20L, 200L);
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> bridge.enableNameplates(), 20L);
        Bukkit.getCommandMap().register("trchat", new Command("e33chat") {
            @Override public boolean execute(CommandSender sender, String label, String[] args) {
                return bridge.command(sender, args);
            }
        });
    }

    public static void remote(String[] data) {
        E33Bridge bridge = instance;
        if (bridge == null || data.length < 2) return;
        try {
            if ("E33Meta".equals(data[0]) && data.length >= 5) {
                if (bridge.nodeId.equals(data[1]) || bridge.seenRemote.putIfAbsent(data[2], System.currentTimeMillis()) != null)
                    return;
                byte[] payload = Base64.getDecoder().decode(data[4]);
                bridge.sendMeta(data[3], payload);
            } else if ("E33Settings".equals(data[0]) && data.length >= 3) {
                if (bridge.nodeId.equals(data[1])) return;
                bridge.settings = E33Protocol.settings(Base64.getDecoder().decode(data[2]));
                bridge.sendAll("config_sync_v2", E33Protocol.config(bridge.settings));
            } else if ("E33PrivateMeta".equals(data[0]) && data.length >= 5) {
                if (bridge.nodeId.equals(data[1]) || bridge.seenRemote.putIfAbsent(data[2], System.currentTimeMillis()) != null)
                    return;
                for (Player player : bridge.players.values()) {
                    Alias alias = bridge.aliases.get(player.getUniqueId());
                    if (alias != null && alias.account().equalsIgnoreCase(data[3])
                        && !bridge.bridgeClients.contains(player.getUniqueId()))
                        bridge.send(player, "chat_meta", Base64.getDecoder().decode(data[4]));
                }
            } else if ("E33Alias".equals(data[0]) && data.length >= 5) {
                if (!bridge.nodeId.equals(data[1]))
                    bridge.aliases.put(UUID.fromString(data[2]), new Alias(data[3], data[4], System.currentTimeMillis()));
            } else if ("E33AliasGone".equals(data[0]) && data.length >= 3) {
                if (!bridge.nodeId.equals(data[1])) bridge.aliases.remove(UUID.fromString(data[2]));
            } else if ("E33Reply".equals(data[0]) && data.length >= 5) {
                if (!bridge.nodeId.equals(data[1])) acceptReply(data[3], data[4]);
            }
        } catch (Exception ex) {
            bridge.plugin.getLogger().warning("Rejected E33 Redis event: " + ex.getMessage());
        }
    }

    private SingleRedisConnection redis() {
        return RedisManager.INSTANCE.getConnection();
    }

    private void publish(String... data) {
        try {
            if (redis() != null) RedisManager.INSTANCE.sendMessage(new TrRedisMessage(data));
        } catch (Exception ex) {
            plugin.getLogger().warning("E33 Redis publish failed: " + ex.getMessage());
        }
    }

    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent event) {
        Player player = event.getPlayer();
        if (event.getChannel().startsWith(PREFIX)) modClients.add(player.getName().toLowerCase(Locale.ROOT));
        switch (event.getChannel()) {
            case PREFIX + "config_sync" -> send(player, "config_sync", E33Protocol.encode(out -> out.writeBoolean(settings.useTpa())));
            case PREFIX + "config_sync_v2" -> send(player, "config_sync_v2", E33Protocol.config(settings));
            case PREFIX + "chat_history" -> sendHistory(player);
            case PREFIX + "media_cap" -> send(player, "media_cap", new byte[] {0});
            case PREFIX + "emoji_catalog" -> sendCraftEmojiCatalog(player, true);
            case E33DownstreamProtocol.CHANNEL -> {
                send(player, "config_sync_v2", E33Protocol.config(settings));
                send(player, "media_cap", new byte[] {0});
                sendHistory(player);
                sendCraftEmojiCatalog(player, true);
            }
            default -> { }
        }
    }

    /** CraftEngine owns shortcode expansion; E33 only exposes usable keywords in its picker. */
    private void sendCraftEmojiCatalog(Player player) { sendCraftEmojiCatalog(player, false); }

    private void sendCraftEmojiCatalog(Player player, boolean explicitRequest) {
        java.util.TreeSet<String> keywords = new java.util.TreeSet<>();
        try {
            Plugin craftEngine = Bukkit.getPluginManager().getPlugin("CraftEngine");
            if (craftEngine != null && craftEngine.isEnabled()) {
                Class<?> api = Class.forName("net.momirealms.craftengine.core.plugin.CraftEngine",
                    true, craftEngine.getClass().getClassLoader());
                Object engine = api.getMethod("instance").invoke(null);
                Object fonts = api.getMethod("fontManager").invoke(engine);
                Object emojis = fonts.getClass().getMethod("emojis").invoke(fonts);
                if (emojis instanceof Map<?, ?> map) for (Object emoji : map.values()) {
                    String permission = (String) emoji.getClass().getMethod("permission").invoke(emoji);
                    if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) continue;
                    Object aliases = emoji.getClass().getMethod("keywords").invoke(emoji);
                    if (aliases instanceof List<?> list) for (Object alias : list) {
                        if (alias instanceof String name && name.matches(":\\S[^:\\s]{0,63}:"))
                            keywords.add(name);
                    }
                }
            }
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("CraftEngine emoji catalogue unavailable: " + ex.getMessage());
        }
        StringBuilder data = new StringBuilder();
        int encodedBytes = 0;
        for (String keyword : keywords) {
            // Send the actual CraftEngine glyph as a styled component. A shortcode
            // alone cannot be drawn by the client without its resource-pack font.
            Component glyph = expandCraftEmoji(player, Component.text(keyword));
            String preview = glyph.equals(Component.text(keyword)) ? ""
                : GsonComponentSerializer.gson().serialize(glyph);
            String line = keyword + (preview.isEmpty() ? "" : "\t" + preview) + "\n";
            int nextBytes = line.getBytes(StandardCharsets.UTF_8).length;
            if (encodedBytes + nextBytes > 24_000) continue;
            data.append(line);
            encodedBytes += nextBytes;
        }
        byte[] payload = data.toString().getBytes(StandardCharsets.UTF_8);
        if (explicitRequest) {
            // Forge 1.20.1 can request a Bukkit channel before its vanilla
            // REGISTER advertisement reaches Paper. The 26.x client receives
            // only the multiplexed downstream channel, so use it when present.
            player.getScheduler().run(plugin, task -> {
                if (!player.isOnline()) return;
                if (player.getListeningPluginChannels().contains(E33DownstreamProtocol.CHANNEL))
                    sendNow(player, "emoji_catalog", payload);
                else player.sendPluginMessage(plugin, PREFIX + "emoji_catalog", payload);
            }, null);
        } else send(player, "emoji_catalog", payload);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { onJoinPlayer(event.getPlayer()); }

    private void onJoinPlayer(Player player) {
        players.put(player.getUniqueId(), player);
        updateAlias(player);
        CompletableFuture.runAsync(() -> {
            try {
                SingleRedisConnection connection = redis();
                String from = connection == null ? null : connection.get("e33chat:v1:reply:" + player.getUniqueId());
                if (from != null && !from.isBlank())
                    CommandReply.INSTANCE.getLastMessageFrom().putIfAbsent(player.getName(), from);
            } catch (Exception ex) {
                plugin.getLogger().warning("E33 reply state unavailable: " + ex.getMessage());
            }
        });
    }

    public static void acceptReply(String to, String from) {
        if (to != null && !to.isBlank() && from != null && !from.isBlank())
            CommandReply.INSTANCE.getLastMessageFrom().put(to, from);
    }

    public static void recordReply(String to, String from) {
        acceptReply(to, from);
        E33Bridge bridge = instance;
        if (bridge == null) return;
        UUID targetId = null;
        for (Map.Entry<UUID, Alias> entry : bridge.aliases.entrySet())
            if (entry.getValue().account().equalsIgnoreCase(to)) { targetId = entry.getKey(); break; }
        String id = targetId == null ? "" : targetId.toString();
        CompletableFuture.runAsync(() -> {
            try {
                SingleRedisConnection connection = bridge.redis();
                if (connection != null && !id.isEmpty())
                    connection.eval("return redis.call('SETEX', KEYS[1], ARGV[1], ARGV[2])",
                        List.of("e33chat:v1:reply:" + id), List.of("604800", from));
                bridge.publish("E33Reply", bridge.nodeId, id, to, from);
            } catch (Exception ex) {
                bridge.plugin.getLogger().warning("E33 reply state remains local: " + ex.getMessage());
            }
        });
    }

    private void updateAlias(Player player) {
        Alias alias = new Alias(player.getName(), displayName(player), System.currentTimeMillis());
        aliases.put(player.getUniqueId(), alias);
        publish("E33Alias", nodeId, player.getUniqueId().toString(), alias.account(), alias.nickname());
    }

    public static String serverId() { return instance == null ? "" : instance.serverId; }

    public static boolean isClient(String account) {
        return instance != null && instance.modClients.contains(account.toLowerCase(Locale.ROOT));
    }

    public static boolean isV1Client(String account) {
        Player player = Bukkit.getPlayerExact(account);
        return player != null && instance != null && instance.bridgeClients.contains(player.getUniqueId());
    }

    public static Component clickableLinks(Component component) {
        return component.replaceText(TextReplacementConfig.builder().match(URL)
            .replacement((match, builder) -> {
                String candidate = match.group();
                int length = candidate.length();
                while (length > 0 && ".,!?;:，。！？；：)]}".indexOf(candidate.charAt(length - 1)) >= 0)
                    length--;
                String url = E33LinkTargets.normalize(candidate.substring(0, length));
                try {
                    java.net.URI uri = java.net.URI.create(url);
                    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null) return Component.text(candidate);
                    return Component.text(url).color(NamedTextColor.AQUA).decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(url)).hoverEvent(Component.text("点击确认后打开链接"))
                        .append(Component.text(candidate.substring(length)));
                } catch (IllegalArgumentException ex) {
                    return Component.text(candidate);
                }
            })
            .build());
    }

    /** Keep a formatter's [链接] label, but give it the URL from the filtered bridge body. */
    private static Component clickableHiddenLinks(Component component, String raw) {
        List<String> targets = E33LinkTargets.fromMessage(raw);
        if (targets.isEmpty()) return component;
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        return component.replaceText(TextReplacementConfig.builder().match("\\[链接]")
            .replacement((match, builder) -> {
                int index = next.getAndIncrement();
                if (index >= targets.size()) return Component.text(match.group());
                String target = targets.get(index);
                return Component.text(match.group()).color(NamedTextColor.AQUA)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(target))
                    .hoverEvent(Component.text("点击打开链接"));
            }).build());
    }

    /** E33 renders bodyJson in its bubble; renderedJson only feeds the vanilla HUD. */
    private static String clickableBodyJson(String bodyJson, String fallback) {
        try {
            Component body = GsonComponentSerializer.gson().deserialize(bodyJson);
            String linked = GsonComponentSerializer.gson().serialize(
                clickableLinks(clickableHiddenLinks(body, fallback)));
            if (linked.length() <= 16_384) return linked;
        } catch (RuntimeException ignored) {
            // Malformed third-party components should not prevent chat delivery.
        }
        return GsonComponentSerializer.gson().serialize(clickableLinks(Component.text(fallback)));
    }

    public static String nicknameFor(String account) {
        E33Bridge bridge = instance;
        if (bridge != null && account != null)
            for (Alias alias : bridge.aliases.values())
                if (alias.account().equalsIgnoreCase(account)) return alias.nickname();
        return account == null ? "" : account;
    }

    /** The formatted CMI/Bukkit display name used by TrChat channel placeholders. */
    public static String styledNameFor(String account) {
        Player player = account == null ? null : Bukkit.getPlayerExact(account);
        if (player != null) {
            E33Bridge bridge = instance;
            return bridge == null ? player.getDisplayName() : bridge.styledDisplayName(player);
        }
        return nicknameFor(account);
    }

    public static boolean isAmbiguous(String nickname) {
        E33Bridge bridge = instance;
        if (bridge == null || nickname == null) return false;
        int matches = 0;
        for (Alias alias : bridge.aliases.values())
            if (alias.nickname().equalsIgnoreCase(nickname) && ++matches > 1) return true;
        return false;
    }

    public static String resolveAccount(String input) {
        E33Bridge bridge = instance;
        if (bridge == null || input == null) return null;
        for (Alias alias : bridge.aliases.values())
            if (alias.account().equalsIgnoreCase(input)) return alias.account();
        if (isAmbiguous(input)) return null;
        for (Alias alias : bridge.aliases.values())
            if (alias.nickname().equalsIgnoreCase(input)) return alias.account();
        return null;
    }

    private void enableNameplates() {
        Plugin nameplates = Bukkit.getPluginManager().getPlugin("CustomNameplates");
        if (nameplates == null || !nameplates.isEnabled()) {
            nameplatesOwnBubbles = false;
            return;
        }
        try {
            ClassLoader loader = nameplates.getClass().getClassLoader();
            Class<?> config = Class.forName("net.momirealms.customnameplates.api.ConfigManager", true, loader);
            boolean bubbleEnabled = (boolean) config.getMethod("bubbleModule").invoke(null);
            if (!bubbleEnabled) {
                nameplatesOwnBubbles = false;
                return;
            }
            Class<?> apiType = Class.forName("net.momirealms.customnameplates.api.CustomNameplates", true, loader);
            Class<?> managerType = Class.forName("net.momirealms.customnameplates.api.feature.chat.ChatManager", true, loader);
            Class<?> providerType = Class.forName("net.momirealms.customnameplates.api.feature.chat.ChatMessageProvider", true, loader);
            Object api = apiType.getMethod("getInstance").invoke(null);
            Object manager = apiType.getMethod("getChatManager").invoke(api);
            Object current = managerType.getMethod("chatProvider").invoke(manager);
            if (current != null && current.getClass().getSimpleName().equals("TrChatProvider")) {
                nameplatesOwnBubbles = true;
                return;
            }
            Class<?> trProvider = Class.forName(
                "net.momirealms.customnameplates.bukkit.compatibility.chat.TrChatProvider", true, loader);
            Object adapter = trProvider.getConstructor(apiType, managerType).newInstance(api, manager);
            boolean installed = (boolean) managerType.getMethod("setCustomChatProvider", providerType)
                .invoke(manager, adapter);
            nameplatesOwnBubbles = installed;
            if (installed) plugin.getLogger().info("CustomNameplates TrChat bubbles enabled");
        } catch (ReflectiveOperationException | LinkageError ex) {
            nameplatesOwnBubbles = false;
            plugin.getLogger().warning("CustomNameplates integration unavailable: " + ex.getMessage());
        }
    }

    @EventHandler public void onPluginEnable(PluginEnableEvent event) {
        if ("CustomNameplates".equals(event.getPlugin().getName()))
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> enableNameplates(), 2L);
        if ("CraftEngine".equals(event.getPlugin().getName()))
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
                for (Player player : players.values()) sendCraftEmojiCatalog(player);
            }, 2L);
    }

    @EventHandler public void onPluginDisable(PluginDisableEvent event) {
        if ("CustomNameplates".equals(event.getPlugin().getName())) nameplatesOwnBubbles = false;
        if ("CraftEngine".equals(event.getPlugin().getName()))
            for (Player player : players.values())
                send(player, "emoji_catalog", new byte[0]);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        players.remove(id);
        HeadBubbleFallback.clear(id, plugin);
        bridgeClients.remove(id);
        stagedChats.remove(id);
        bodyJsons.remove(id);
        privateIds.remove(id);
        aliases.remove(id);
        modClients.remove(event.getPlayer().getName().toLowerCase(Locale.ROOT));
        publish("E33AliasGone", nodeId, id.toString());
        rawMessages.remove(id);
        originalLinks.remove(id);
        quotes.remove(id);
    }

    /** Called after TrChat's content processing, so history/meta never expose pre-filter text. */
    public static void rememberMessage(UUID sender, String filteredContent) {
        E33Bridge bridge = instance;
        if (bridge == null) return;
        if (filteredContent == null) {
            bridge.rawMessages.remove(sender);
            bridge.originalLinks.remove(sender);
            bridge.bodyJsons.remove(sender);
            bridge.stagedChats.remove(sender);
            bridge.privateIds.remove(sender);
        }
        else bridge.rawMessages.put(sender, filteredContent);
    }

    /** Capture only URL targets before channel scripts and display functions replace text. */
    public static void rememberOriginalLinks(UUID sender, String message) {
        E33Bridge bridge = instance;
        if (bridge == null) return;
        List<String> links = E33LinkTargets.fromMessage(message);
        if (links.isEmpty()) bridge.originalLinks.remove(sender);
        else bridge.originalLinks.put(sender, links);
    }

    public static void rememberBody(UUID sender, Component body) {
        E33Bridge bridge = instance;
        if (bridge != null && body != null)
            bridge.bodyJsons.put(sender, GsonComponentSerializer.gson().serialize(
                expandCraftEmoji(bridge.players.get(sender), body)));
    }

    public static void rememberMentions(UUID sender, java.util.Set<String> names) {
        E33Bridge bridge = instance;
        if (bridge != null) bridge.resolvedMentions.put(sender, List.copyOf(names));
    }

    public static String takeQuoteNotifyTarget(UUID sender) {
        E33Bridge bridge = instance;
        return bridge == null ? null : bridge.quoteNotifyTargets.remove(sender);
    }

    /** Attach TrChat's final channel component after all prefix/message/suffix formatting. */
    public static void rememberRendered(UUID sender, Component rendered) {
        E33Bridge bridge = instance;
        if (bridge == null || rendered == null) return;
        StagedChat staged = bridge.stagedChats.get(sender);
        if (staged == null) return;
        try {
            E33Protocol.BridgeChat old = E33Protocol.bridgeChat(staged.bytes());
            Player player = Bukkit.getPlayer(sender);
            String styled = player == null ? old.displayName() : bridge.styledDisplayName(player);
            String displayJson = GsonComponentSerializer.gson().serialize(styledNameComponent(styled));
            String renderedJson = GsonComponentSerializer.gson().serialize(
                clickableLinks(clickableHiddenLinks(rendered, old.body())));
            E33Protocol.BridgeChat updated = new E33Protocol.BridgeChat(old.messageId(), old.sender(),
                old.account(), old.displayName(), old.origin(), old.body(), old.bodyJson(),
                old.privateMessage(), old.recipient(), old.quoteSender(), old.quoteContent(),
                old.mentions(), old.nameplate(), displayJson, renderedJson);
            bridge.stagedChats.put(sender, new StagedChat(E33Protocol.bridgeChat(updated), staged.createdAt()));
        } catch (Exception ex) {
            bridge.plugin.getLogger().warning("E33 channel format unavailable: " + ex.getMessage());
        }
    }

    /** Expands CraftEngine chat shortcodes with its own permission and font rules. */
    public static Component expandCraftEmoji(Player sender, Component body) {
        if (body == null || body.toString().indexOf(':') < 0) return body;
        Plugin craftEngine = Bukkit.getPluginManager().getPlugin("CraftEngine");
        if (craftEngine == null || !craftEngine.isEnabled()) return body;
        try {
            ClassLoader loader = craftEngine.getClass().getClassLoader();
            Class<?> api = Class.forName("net.momirealms.craftengine.core.plugin.CraftEngine", true, loader);
            Object engine = api.getMethod("instance").invoke(null);
            Object fonts = api.getMethod("fontManager").invoke(engine);
            Class<?> adapter = Class.forName("net.momirealms.craftengine.bukkit.api.BukkitAdaptor", true, loader);
            Object craftPlayer = sender == null ? null : adapter.getMethod("adapt", Player.class).invoke(null, sender);
            Class<?> craftPlayerType = Class.forName("net.momirealms.craftengine.core.entity.player.Player", true, loader);
            Class<?> useCaseType = Class.forName("net.momirealms.craftengine.core.font.EmojiUseCase", true, loader);
            Object chat = useCaseType.getField("CHAT").get(null);
            // CraftEngine shades Adventure, so its Component is not Bukkit's
            // net.kyori.adventure.text.Component. JSON is the shared boundary.
            String json = GsonComponentSerializer.gson().serialize(body);
            Object result = fonts.getClass().getMethod("replaceJsonEmoji",
                String.class, craftPlayerType, useCaseType).invoke(fonts, json, craftPlayer, chat);
            if ((boolean) result.getClass().getMethod("replaced").invoke(result))
                return GsonComponentSerializer.gson().deserialize(
                    (String) result.getClass().getMethod("text").invoke(result));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) { }
        return body;
    }

    public static void headBubble(Player player, String filteredContent) {
        E33Bridge bridge = instance;
        if (bridge == null) return;
        Plugin cn = Bukkit.getPluginManager().getPlugin("CustomNameplates");
        if (!bridge.nameplatesOwnBubbles || cn == null || !cn.isEnabled())
            HeadBubbleFallback.show(bridge.plugin, player, filteredContent);
    }

    public static void disable() {
        E33Bridge bridge = instance;
        if (bridge != null) HeadBubbleFallback.clearAll(bridge.plugin);
        instance = null;
    }

    public static boolean sendBridgeComponent(Player receiver, UUID sender) {
        E33Bridge bridge = instance;
        if (bridge == null || sender == null) return false;
        StagedChat staged = bridge.stagedChats.get(sender);
        if (staged == null || System.currentTimeMillis() - staged.createdAt() > 5_000) return false;
        if (!bridge.activateBridgeReceiver(receiver)) return false;
        try {
            bridge.sendBridgePacket(receiver, staged.bytes());
            return true;
        } catch (IOException ex) {
            bridge.plugin.getLogger().warning("Invalid staged E33 chat: " + ex.getMessage());
            return false;
        }
    }

    public static boolean sendBridgeBytes(Player receiver, String encoded) {
        E33Bridge bridge = instance;
        if (bridge == null || encoded.isEmpty()) return false;
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            E33Protocol.bridgeChat(bytes);
            if (!bridge.activateBridgeReceiver(receiver)) return false;
            bridge.sendBridgePacket(receiver, bytes);
            return true;
        } catch (Exception ex) {
            bridge.plugin.getLogger().warning("Rejected remote E33 chat: " + ex.getMessage());
            return false;
        }
    }

    private boolean activateBridgeReceiver(Player receiver) {
        // Fabric may advertise its receiving channels after JOIN. The channel
        // itself identifies an upgraded client even if bridge_hello was late.
        if (!receiver.getListeningPluginChannels().contains(E33DownstreamProtocol.CHANNEL)
            && !receiver.getListeningPluginChannels().contains(PREFIX + "bridge_chat_v1")
            && !receiver.getListeningPluginChannels().contains(PREFIX + "bridge_chat_v2")) return false;
        if (bridgeClients.add(receiver.getUniqueId()))
            send(receiver, "bridge_ready_v1", new byte[] {1});
        return true;
    }

    private void sendBridgePacket(Player receiver, byte[] bytes) throws IOException {
        E33Protocol.BridgeChat chat = E33Protocol.bridgeChat(bytes);
        if (receiver.getListeningPluginChannels().contains(E33DownstreamProtocol.CHANNEL)
            || receiver.getListeningPluginChannels().contains(PREFIX + "bridge_chat_v2"))
            sendNow(receiver, "bridge_chat_v2", E33Protocol.bridgeChat(chat));
        else sendNow(receiver, "bridge_chat_v1", E33Protocol.bridgeChatV1(chat));
    }

    public static String stagedBase64(UUID sender) {
        E33Bridge bridge = instance;
        StagedChat staged = bridge == null ? null : bridge.stagedChats.get(sender);
        return staged == null ? "" : Base64.getEncoder().encodeToString(staged.bytes());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSend(TrChatSendEvent event) {
        if (event.getChannel() instanceof PrivateChannel) {
            onPrivateSend(event);
            return;
        }
        Player sender = event.getPlayer();
        String raw = rawMessages.remove(sender.getUniqueId());
        if (raw == null || raw.isBlank()) return;
        E33Protocol.Quote quote = quotes.remove(sender.getUniqueId());
        List<String> links = originalLinks.remove(sender.getUniqueId());
        if ((links == null || links.isEmpty()) && quote != null)
            links = E33LinkTargets.fromMatchedMessage(quote.target(), raw);
        raw = E33LinkTargets.restorePlaceholders(raw, links);
        Alias source = aliases.get(sender.getUniqueId());
        String name = source == null ? sender.getName() : source.account();
        String nickname = source == null ? name : source.nickname();
        List<String> recordedMentions = resolvedMentions.remove(sender.getUniqueId());
        List<String> mentions = new ArrayList<>(recordedMentions == null ? List.of() : recordedMentions);
        Map<String, Integer> nicknameCounts = new HashMap<>();
        for (Alias alias : aliases.values())
            nicknameCounts.merge(alias.nickname().toLowerCase(Locale.ROOT), 1, Integer::sum);
        for (Alias alias : aliases.values()) {
            boolean accountMention = raw.contains("@" + alias.account());
            boolean uniqueNicknameMention = !alias.nickname().isBlank()
                && nicknameCounts.getOrDefault(alias.nickname().toLowerCase(Locale.ROOT), 0) == 1
                && raw.contains("@" + alias.nickname());
            if (accountMention || uniqueNicknameMention)
                mentions.add(alias.account());
        }
        if (quote != null && !quote.sender().isBlank())
            quoteNotifyTargets.put(sender.getUniqueId(), quote.sender());
        List<UUID> mentionIds = new ArrayList<>();
        for (String mention : mentions)
            for (Map.Entry<UUID, Alias> entry : aliases.entrySet())
                if (entry.getValue().account().equalsIgnoreCase(mention)) mentionIds.add(entry.getKey());
        String bodyJson = bodyJsons.remove(sender.getUniqueId());
        if (bodyJson == null) bodyJson = GsonComponentSerializer.gson().serialize(Component.text(raw));
        if (bodyJson.length() > 16_384) bodyJson = GsonComponentSerializer.gson().serialize(Component.text(raw));
        bodyJson = clickableBodyJson(bodyJson, raw);
        String nameplate = nameplateComponentJson(sender);
        if (nameplate.length() > 8192) nameplate = "";
        try {
            byte[] bridgeChat = E33Protocol.bridgeChat(new E33Protocol.BridgeChat(UUID.randomUUID(),
                sender.getUniqueId(), name, nickname, serverId, raw, bodyJson, false, "",
                quote == null ? "" : quote.sender(), quote == null ? "" : quote.content(),
                mentionIds, nameplate, "", ""));
            stagedChats.put(sender.getUniqueId(), new StagedChat(bridgeChat, System.currentTimeMillis()));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("E33 semantic chat too large; using ordinary chat: " + ex.getMessage());
        }
        byte[] meta = E33Protocol.meta(sender.getUniqueId(), name, String.valueOf(raw.hashCode()),
            quote == null ? "" : quote.sender(), quote == null ? "" : quote.content(), mentions);
        sendMeta(event.getChannel().getId(), meta);
        publish("E33Meta", nodeId, UUID.randomUUID().toString(), event.getChannel().getId(),
            Base64.getEncoder().encodeToString(meta));

        E33Protocol.HistoryEntry entry = new E33Protocol.HistoryEntry(sender.getUniqueId(),
            (serverId.isEmpty() ? "" : "[" + serverId + "] ") + nickname,
            raw, System.currentTimeMillis(), false,
            quote == null ? "" : quote.content(), quote == null ? "" : quote.sender());
        synchronized (localHistory) {
            localHistory.addLast(entry);
            while (localHistory.size() > 50) localHistory.removeFirst();
        }
        CompletableFuture.runAsync(() -> {
            try {
                SingleRedisConnection redis = redis();
                if (redis != null) redis.eval("redis.call('LPUSH', KEYS[1], ARGV[1]); redis.call('LTRIM', KEYS[1], 0, 49); return 1",
                    List.of(HISTORY_KEY), List.of(Base64.getEncoder().encodeToString(E33Protocol.history(List.of(entry)))));
            } catch (Exception ex) {
                plugin.getLogger().warning("E33 history remains local: " + ex.getMessage());
            }
        });
    }

    private void onPrivateSend(TrChatSendEvent event) {
        Player sender = event.getPlayer();
        UUID senderId = sender.getUniqueId();
        boolean recipientSide = event.getType() == TrChatSendEvent.Type.RECEIVER;
        String raw = recipientSide ? rawMessages.remove(senderId) : rawMessages.get(senderId);
        if (raw == null || raw.isBlank()) return;
        List<String> links = recipientSide ? originalLinks.remove(senderId) : originalLinks.get(senderId);
        E33Protocol.Quote quote = recipientSide ? quotes.remove(senderId) : quotes.get(senderId);
        if ((links == null || links.isEmpty()) && quote != null)
            links = E33LinkTargets.fromMatchedMessage(quote.target(), raw);
        raw = E33LinkTargets.restorePlaceholders(raw, links);
        Alias source = aliases.get(senderId);
        String account = source == null ? sender.getName() : source.account();
        UUID messageId = recipientSide
            ? privateIds.getOrDefault(senderId, UUID.randomUUID())
            : privateIds.computeIfAbsent(senderId, ignored -> UUID.randomUUID());
        String bodyJson = recipientSide ? bodyJsons.remove(senderId) : bodyJsons.get(senderId);
        if (bodyJson == null) bodyJson = GsonComponentSerializer.gson().serialize(Component.text(raw));
        if (bodyJson.length() > 16_384) bodyJson = GsonComponentSerializer.gson().serialize(Component.text(raw));
        bodyJson = clickableBodyJson(bodyJson, raw);
        String nameplate = nameplateComponentJson(sender);
        if (nameplate.length() > 8192) nameplate = "";
        try {
            byte[] bridgeChat = E33Protocol.bridgeChat(new E33Protocol.BridgeChat(messageId,
                senderId, account, source == null ? account : source.nickname(), serverId, raw,
                bodyJson, true, event.getSession().getLastPrivateTo(),
                quote == null ? "" : quote.sender(), quote == null ? "" : quote.content(),
                List.of(), nameplate, "", ""));
            stagedChats.put(senderId, new StagedChat(bridgeChat, System.currentTimeMillis()));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("E33 semantic private chat too large: " + ex.getMessage());
        }
        byte[] meta = E33Protocol.meta(senderId, account, String.valueOf(raw.hashCode()),
            quote == null ? "" : quote.sender(), quote == null ? "" : quote.content(), List.of());
        if (!recipientSide) {
            if (!bridgeClients.contains(senderId)) send(sender, "chat_meta", meta);
            return;
        }
        String target = event.getSession().getLastPrivateTo();
        for (Player player : players.values()) {
            Alias alias = aliases.get(player.getUniqueId());
            if (alias != null && alias.account().equalsIgnoreCase(target)
                && !bridgeClients.contains(player.getUniqueId())) send(player, "chat_meta", meta);
        }
        publish("E33PrivateMeta", nodeId, UUID.randomUUID().toString(), target,
            Base64.getEncoder().encodeToString(meta));
    }

    private String displayName(Player player) {
        String styled = styledDisplayName(player);
        String plain = styled.replaceAll("(?i)[§&]x(?:[§&][0-9a-f]){6}", "")
            .replaceAll("(?i)[§&]#[0-9a-f]{6}", "")
            .replaceAll("(?i)[§&][0-9a-fk-or]", "")
            .replaceAll("<[^>]+>", "");
        return plain == null || plain.isBlank() ? player.getName() : plain;
    }

    private String styledDisplayName(Player player) {
        String title = playerTitle(player);
        String name = null;
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                String value = (String) papi.getMethod("setPlaceholders", Player.class, String.class)
                    .invoke(null, player, "%cmi_user_display_name%");
                if (value != null && !value.isBlank() && !value.contains("%cmi_"))
                    name = value;
            }
        } catch (ReflectiveOperationException ignored) { }
        if (name == null) {
            String legacy = player.getDisplayName();
            name = legacy == null || legacy.isBlank() ? player.getName() : legacy;
        }
        return title.isEmpty() || name.contains(title.trim()) ? name : title + name;
    }

    private String playerTitle(Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return "";
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            String value = (String) papi.getMethod("setPlaceholders", Player.class, String.class)
                .invoke(null, player, "%PlayerTitle_use%");
            if (value == null || value.isBlank() || value.contains("%PlayerTitle_")
                || value.equalsIgnoreCase("none") || value.equalsIgnoreCase("null")
                || value.equals("无") || value.equals("无称号")) return "";
            return value.stripTrailing() + " ";
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return "";
        }
    }

    private static Component styledNameComponent(String input) {
        if (input.contains("<gradient:") || input.contains("<color:")) {
            try { return MiniMessage.miniMessage().deserialize(input); }
            catch (RuntimeException ignored) { }
        }
        Component result = Component.empty();
        StringBuilder run = new StringBuilder();
        TextColor color = null;
        boolean bold = false, italic = false, underline = false, strike = false, obfuscated = false;
        for (int i = 0; i < input.length();) {
            char marker = input.charAt(i);
            if ((marker == '§' || marker == '&') && i + 1 < input.length()) {
                String hex = null;
                if (Character.toLowerCase(input.charAt(i + 1)) == 'x' && i + 13 < input.length()) {
                    StringBuilder digits = new StringBuilder(6);
                    for (int j = 0; j < 6; j++) {
                        if (input.charAt(i + 2 + j * 2) != marker) { digits.setLength(0); break; }
                        digits.append(input.charAt(i + 3 + j * 2));
                    }
                    if (digits.length() == 6) hex = digits.toString();
                } else if (input.charAt(i + 1) == '#' && i + 7 < input.length()) {
                    hex = input.substring(i + 2, i + 8);
                }
                if (hex != null && hex.matches("[0-9a-fA-F]{6}")) {
                    result = appendStyledRun(result, run, color, bold, italic, underline, strike, obfuscated);
                    color = TextColor.color(Integer.parseInt(hex, 16));
                    bold = italic = underline = strike = obfuscated = false;
                    i += input.charAt(i + 1) == '#' ? 8 : 14;
                    continue;
                }
                char code = Character.toLowerCase(input.charAt(i + 1));
                TextColor next = switch (code) {
                    case '0' -> NamedTextColor.BLACK; case '1' -> NamedTextColor.DARK_BLUE;
                    case '2' -> NamedTextColor.DARK_GREEN; case '3' -> NamedTextColor.DARK_AQUA;
                    case '4' -> NamedTextColor.DARK_RED; case '5' -> NamedTextColor.DARK_PURPLE;
                    case '6' -> NamedTextColor.GOLD; case '7' -> NamedTextColor.GRAY;
                    case '8' -> NamedTextColor.DARK_GRAY; case '9' -> NamedTextColor.BLUE;
                    case 'a' -> NamedTextColor.GREEN; case 'b' -> NamedTextColor.AQUA;
                    case 'c' -> NamedTextColor.RED; case 'd' -> NamedTextColor.LIGHT_PURPLE;
                    case 'e' -> NamedTextColor.YELLOW; case 'f' -> NamedTextColor.WHITE;
                    default -> null;
                };
                if (next != null || "klmnor".indexOf(code) >= 0) {
                    result = appendStyledRun(result, run, color, bold, italic, underline, strike, obfuscated);
                    if (next != null || code == 'r') {
                        color = next;
                        bold = italic = underline = strike = obfuscated = false;
                    } else if (code == 'k') obfuscated = true;
                    else if (code == 'l') bold = true;
                    else if (code == 'm') strike = true;
                    else if (code == 'n') underline = true;
                    else if (code == 'o') italic = true;
                    i += 2;
                    continue;
                }
            }
            run.append(marker);
            i++;
        }
        return appendStyledRun(result, run, color, bold, italic, underline, strike, obfuscated);
    }

    private static Component appendStyledRun(Component result, StringBuilder run, TextColor color,
                                             boolean bold, boolean italic, boolean underline,
                                             boolean strike, boolean obfuscated) {
        if (run.length() == 0) return result;
        Component part = Component.text(run.toString());
        run.setLength(0);
        if (color != null) part = part.color(color);
        if (bold) part = part.decorate(TextDecoration.BOLD);
        if (italic) part = part.decorate(TextDecoration.ITALIC);
        if (underline) part = part.decorate(TextDecoration.UNDERLINED);
        if (strike) part = part.decorate(TextDecoration.STRIKETHROUGH);
        if (obfuscated) part = part.decorate(TextDecoration.OBFUSCATED);
        return result.append(part);
    }

    /** CustomNameplates' own %np_tag% already contains its configured glyphs and player tag. */
    private String nameplateComponentJson(Player player) {
        Plugin nameplates = Bukkit.getPluginManager().getPlugin("CustomNameplates");
        if (nameplates == null || !nameplates.isEnabled()) return "";
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return "";
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            String tag = (String) papi.getMethod("setPlaceholders", Player.class, String.class)
                .invoke(null, player, "%np_tag%");
            if (tag == null || tag.isBlank() || tag.contains("%np_tag%")) return "";
            Component nameplate = MiniMessage.miniMessage().deserialize(tag);
            String title = playerTitle(player);
            if (!title.isEmpty() && !tag.contains(title.trim()))
                nameplate = styledNameComponent(title).append(nameplate);
            return GsonComponentSerializer.gson().serialize(nameplate);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return "";
        }
    }

    private record BubbleOption(String id, String label, String previewJson, boolean unlocked, String skinSpec) {}

    private void equipNameplateBubble(Player player, String id) {
        Plugin nameplates = Bukkit.getPluginManager().getPlugin("CustomNameplates");
        if (nameplates == null || !nameplates.isEnabled()) return;
        try {
            ClassLoader loader = nameplates.getClass().getClassLoader();
            Class<?> apiType = Class.forName("net.momirealms.customnameplates.api.CustomNameplates", true, loader);
            Class<?> cnPlayerType = Class.forName("net.momirealms.customnameplates.api.CNPlayer", true, loader);
            Class<?> managerType = Class.forName("net.momirealms.customnameplates.api.feature.bubble.BubbleManager", true, loader);
            Object api = apiType.getMethod("getInstance").invoke(null);
            Object cnPlayer = apiType.getMethod("getPlayer", UUID.class).invoke(api, player.getUniqueId());
            if (cnPlayer == null || !(boolean) cnPlayerType.getMethod("isLoaded").invoke(cnPlayer)) return;
            Object manager = apiType.getMethod("getBubbleManager").invoke(api);
            boolean allowed = "none".equals(id);
            if (!allowed && id.matches("[A-Za-z0-9_.:-]{1,128}")) {
                Object config = managerType.getMethod("bubbleConfigById", String.class).invoke(manager, id);
                allowed = config != null && (boolean) managerType
                    .getMethod("hasBubble", cnPlayerType, String.class).invoke(manager, cnPlayer, id);
            }
            if (!allowed) return;
            cnPlayerType.getMethod("setBubbleData", String.class).invoke(cnPlayer, id);
            cnPlayerType.getMethod("save").invoke(cnPlayer);
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("CustomNameplates bubble equip unavailable: " + ex.getMessage());
        }
    }

    private void sendNameplateBubbleCatalog(Player player) {
        Plugin nameplates = Bukkit.getPluginManager().getPlugin("CustomNameplates");
        if (nameplates == null || !nameplates.isEnabled()) {
            sendNow(player, "bubble_catalog", bubblePage(false, true, true, "none", List.of()));
            return;
        }
        try {
            ClassLoader loader = nameplates.getClass().getClassLoader();
            Class<?> apiType = Class.forName("net.momirealms.customnameplates.api.CustomNameplates", true, loader);
            Class<?> cnPlayerType = Class.forName("net.momirealms.customnameplates.api.CNPlayer", true, loader);
            Class<?> managerType = Class.forName("net.momirealms.customnameplates.api.feature.bubble.BubbleManager", true, loader);
            Class<?> configType = Class.forName("net.momirealms.customnameplates.api.feature.bubble.BubbleConfig", true, loader);
            Class<?> bubbleType = Class.forName("net.momirealms.customnameplates.api.feature.bubble.Bubble", true, loader);
            Class<?> settingsType = Class.forName("net.momirealms.customnameplates.api.ConfigManager", true, loader);
            if (!(boolean) settingsType.getMethod("bubbleModule").invoke(null)) {
                sendNow(player, "bubble_catalog", bubblePage(false, true, true, "none", List.of()));
                return;
            }
            Object api = apiType.getMethod("getInstance").invoke(null);
            Object cnPlayer = apiType.getMethod("getPlayer", UUID.class).invoke(api, player.getUniqueId());
            if (cnPlayer == null || !(boolean) cnPlayerType.getMethod("isLoaded").invoke(cnPlayer)) {
                sendNow(player, "bubble_catalog", bubblePage(false, true, true, "none", List.of()));
                return;
            }
            Object manager = apiType.getMethod("getBubbleManager").invoke(api);
            String selected = (String) cnPlayerType.getMethod("bubbleData").invoke(cnPlayer);
            if (selected == null) selected = "none";
            String defaultId = (String) managerType.getMethod("defaultBubbleId").invoke(manager);
            String font = settingsType.getMethod("namespace").invoke(null) + ":"
                + settingsType.getMethod("font").invoke(null);
            Object defaultConfig = managerType.getMethod("bubbleConfigById", String.class).invoke(manager, defaultId);
            List<BubbleOption> options = new ArrayList<>();
            options.add(new BubbleOption("none", "默认气泡",
                bubblePreview(defaultConfig, configType, bubbleType, font), true, ""));
            Object configs = managerType.getMethod("bubbleConfigs").invoke(manager);
            if (configs instanceof java.util.Collection<?> collection) for (Object config : collection) {
                String id = (String) configType.getMethod("id").invoke(config);
                if (id == null || id.length() > 128 || !id.matches("[A-Za-z0-9_.:-]+")) continue;
                String label = (String) configType.getMethod("displayName").invoke(config);
                if (label == null || label.isBlank()) label = id;
                label = label.replaceAll("<[^>]*>|§.", "");
                if (label.length() > 256) label = label.substring(0, 256);
                boolean unlocked = (boolean) managerType.getMethod("hasBubble", cnPlayerType, String.class)
                    .invoke(manager, cnPlayer, id);
                options.add(new BubbleOption(id, label,
                    bubblePreview(config, configType, bubbleType, font), unlocked,
                    bubbleSkinSpec(config, configType, bubbleType, font)));
            }
            boolean first = true;
            List<BubbleOption> page = new ArrayList<>();
            for (BubbleOption option : options) {
                List<BubbleOption> candidate = new ArrayList<>(page);
                candidate.add(option);
                if (!page.isEmpty() && (candidate.size() > 16
                    || bubblePage(true, first, false, selected, candidate).length > 28_000)) {
                    sendNow(player, "bubble_catalog", bubblePage(true, first, false, selected, page));
                    first = false;
                    page.clear();
                }
                page.add(option);
            }
            sendNow(player, "bubble_catalog", bubblePage(true, first, true, selected, page));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            plugin.getLogger().warning("CustomNameplates bubble catalogue unavailable: " + ex.getMessage());
            sendNow(player, "bubble_catalog", bubblePage(false, true, true, "none", List.of()));
        }
    }

    private static String bubblePreview(Object config, Class<?> configType, Class<?> bubbleType,
                                        String font) {
        if (config == null) return "";
        try {
            Object[] bubbles = (Object[]) configType.getMethod("bubbles").invoke(config);
            if (bubbles.length == 0 || bubbles[0] == null) return "";
            Object bubble = bubbles[0];
            String prefix = (String) bubbleType.getMethod("createImagePrefix", float.class, float.class, float.class)
                .invoke(bubble, 36f, 1f, 1f);
            String suffix = (String) bubbleType.getMethod("createImageSuffix", float.class, float.class, float.class)
                .invoke(bubble, 36f, 1f, 1f);
            Component preview = MiniMessage.miniMessage().deserialize("<font:" + font + ">" + prefix
                + "</font>预览<font:" + font + ">" + suffix + "</font>");
            String json = GsonComponentSerializer.gson().serialize(preview);
            return json.length() <= 3000 ? json : "";
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return "";
        }
    }

    /** Native CustomNameplates glyph metrics for each supported line count. */
    private static String bubbleSkinSpec(Object config, Class<?> configType, Class<?> bubbleType,
                                         String font) {
        if (config == null || font == null || !font.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return "";
        try {
            Object[] bubbles = (Object[]) configType.getMethod("bubbles").invoke(config);
            StringBuilder spec = new StringBuilder(font);
            for (int i = 0; i < Math.min(bubbles.length, 12); i++) {
                spec.append('|');
                Object bubble = bubbles[i];
                if (bubble == null) continue;
                Object left = bubbleType.getMethod("left").invoke(bubble);
                Object right = bubbleType.getMethod("right").invoke(bubble);
                Object middle = bubbleType.getMethod("middle").invoke(bubble);
                Object tail = bubbleType.getMethod("tail").invoke(bubble);
                Object[] parts = {left, right, middle, tail};
                for (Object part : parts) {
                    Class<?> type = part.getClass();
                    spec.append(((Character) type.getMethod("character").invoke(part)).charValue()).append(',');
                    spec.append(((Number) type.getMethod("advance").invoke(part)).floatValue()).append(',');
                }
                Class<?> leftType = left.getClass();
                spec.append(((Number) leftType.getMethod("height").invoke(left)).intValue()).append(',');
                spec.append(((Number) leftType.getMethod("ascent").invoke(left)).intValue());
            }
            return spec.length() <= 2048 ? spec.toString() : "";
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return "";
        }
    }

    private static byte[] bubblePage(boolean available, boolean first, boolean last,
                                     String selected, List<BubbleOption> entries) {
        return E33Protocol.encode(out -> {
            out.writeBoolean(available);
            out.writeBoolean(first);
            out.writeBoolean(last);
            E33Protocol.string(out, selected);
            E33Protocol.varInt(out, entries.size());
            for (BubbleOption entry : entries) {
                E33Protocol.string(out, entry.id());
                E33Protocol.string(out, entry.label());
                E33Protocol.string(out, entry.previewJson());
                out.writeBoolean(entry.unlocked());
                E33Protocol.string(out, entry.skinSpec());
            }
        });
    }

    private void send(Player player, String channel, byte[] payload) {
        player.getScheduler().run(plugin, task -> {
            if (player.isOnline()) sendNow(player, channel, payload);
        }, null);
    }

    private void sendNow(Player player, String channel, byte[] payload) {
        if (player.getListeningPluginChannels().contains(E33DownstreamProtocol.CHANNEL)) {
            player.sendPluginMessage(plugin, E33DownstreamProtocol.CHANNEL,
                E33DownstreamProtocol.wrap(channel, payload));
        } else if (player.getListeningPluginChannels().contains(PREFIX + channel)) {
            player.sendPluginMessage(plugin, PREFIX + channel, payload);
        }
    }

    private void sendAll(String channel, byte[] payload) {
        for (Player player : players.values()) send(player, channel, payload);
    }

    private void sendMeta(String channelId, byte[] payload) {
        Channel channel = Channel.Companion.getChannels().get(channelId);
        if (channel == null || channel instanceof PrivateChannel) return;
        for (Player player : players.values()) {
            player.getScheduler().run(plugin, task -> {
                if (!player.isOnline() || bridgeClients.contains(player.getUniqueId())
                    || (!player.getListeningPluginChannels().contains(E33DownstreamProtocol.CHANNEL)
                        && !player.getListeningPluginChannels().contains(PREFIX + "chat_meta"))) return;
                String permission = channel.getSettings().getListenPermission();
                if (!permission.isEmpty() && !player.hasPermission(permission)) return;
                if (!channel.getListeners().contains(player.getName())) return;
                sendNow(player, "chat_meta", payload);
            }, null);
        }
    }

    private void sendHistory(Player player) {
        CompletableFuture.runAsync(() -> {
            List<E33Protocol.HistoryEntry> entries = new ArrayList<>();
            try {
                SingleRedisConnection redis = redis();
                if (redis != null) {
                    for (String item : redis.lrange(HISTORY_KEY, 0, 49))
                        entries.addAll(E33Protocol.history(Base64.getDecoder().decode(item)));
                    java.util.Collections.reverse(entries);
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("E33 history read failed: " + ex.getMessage());
            }
            if (entries.isEmpty()) synchronized (localHistory) { entries.addAll(localHistory); }
            byte[] payload = E33Protocol.history(entries);
            while (payload.length > 30_000 && !entries.isEmpty()) {
                entries.remove(0);
                payload = E33Protocol.history(entries);
            }
            send(player, "chat_history", payload);
        });
    }

    @Override public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        try {
            switch (channel) {
                case PREFIX + "bridge_hello_v1" -> {
                    var input = E33Protocol.input(data);
                    if (E33Protocol.varInt(input) != 1 || input.available() != 0) return;
                    bridgeClients.add(player.getUniqueId());
                    send(player, "bridge_ready_v1", new byte[] {1});
                }
                case PREFIX + "quote_sync" -> quotes.put(player.getUniqueId(), E33Protocol.quote(data));
                case PREFIX + "server_config_save" -> {
                    E33Protocol.Settings next = E33Protocol.clientSave(data);
                    player.getScheduler().run(plugin, task -> {
                        if (!player.hasPermission("trchat.admin")) return;
                        if (!validSettings(next)) { player.sendMessage("§cE33 模板无效或过长"); return; }
                        applySettings(next);
                    }, null);
                }
                case PREFIX + "media_request" -> mediaRequest(player, E33Protocol.mediaRequest(data));
                case PREFIX + "emoji_catalog" -> player.getScheduler().run(plugin,
                    task -> sendCraftEmojiCatalog(player, true), null);
                case PREFIX + "bubble_action" -> {
                    var input = E33Protocol.input(data);
                    int action = input.readUnsignedByte();
                    String bubbleId = E33Protocol.string(input);
                    if (input.available() != 0 || bubbleId.length() > 128) return;
                    player.getScheduler().run(plugin, task -> {
                        if (action == 1) equipNameplateBubble(player, bubbleId);
                        if (action == 0 || action == 1) sendNameplateBubbleCatalog(player);
                    }, null);
                }
                default -> { }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Rejected E33 payload from " + player.getName() + ": " + ex.getMessage());
        }
    }

    private void saveShared(E33Protocol.Settings next) {
        try {
            SingleRedisConnection redis = redis();
            if (redis != null) {
                String encoded = Base64.getEncoder().encodeToString(E33Protocol.screen(next));
                redis.set(SETTINGS_KEY, encoded);
                publish("E33Settings", nodeId, encoded);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("E33 settings saved locally only: " + ex.getMessage());
        }
    }

    private static boolean validSettings(E33Protocol.Settings candidate) {
        if (candidate.chatTemplates().size() > 16 || candidate.whisperTemplates().size() > 16) return false;
        for (String template : candidate.chatTemplates())
            if (template.length() > 512 || !template.contains("{content}")
                || (!template.contains("{display_name}") && !template.contains("{name}"))) return false;
        for (String template : candidate.whisperTemplates())
            if (template.length() > 512 || !template.contains("{content}")
                || (!template.contains("{sender}") && !template.contains("{target}"))) return false;
        return true;
    }

    private void applySettings(E33Protocol.Settings next) {
        settings = next;
        plugin.getConfig().set("e33.settings", Base64.getEncoder().encodeToString(E33Protocol.screen(next)));
        plugin.saveConfig();
        CompletableFuture.runAsync(() -> saveShared(next));
        sendAll("config_sync_v2", E33Protocol.config(next));
        sendAll("media_cap", new byte[] {0});
    }

    private boolean command(CommandSender sender, String[] args) {
        if (!sender.hasPermission("trchat.admin")) { sender.sendMessage("§c没有权限"); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("gui") && sender instanceof Player player) {
            send(player, "server_config_screen", E33Protocol.screen(settings));
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("template")) {
            if (args[1].equalsIgnoreCase("list")) {
                sender.sendMessage("§aChat: " + settings.chatTemplates() + " | Whisper: " + settings.whisperTemplates());
                return true;
            }
            if (args.length >= 4 && args[1].equalsIgnoreCase("set")) {
                boolean chat = args[2].equalsIgnoreCase("chat");
                List<String> changed = new ArrayList<>(chat ? settings.chatTemplates() : settings.whisperTemplates());
                changed.add(String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
                E33Protocol.Settings next = new E33Protocol.Settings(settings.useTpa(), settings.history(), settings.debug(),
                    chat ? changed : settings.chatTemplates(), chat ? settings.whisperTemplates() : changed,
                    settings.media(), settings.autoClean());
                if (!validSettings(next)) { sender.sendMessage("§c模板无效或过长"); return true; }
                applySettings(next);
                sender.sendMessage("§a模板已更新");
                return true;
            }
        }
        sender.sendMessage("§7/e33chat gui | /e33chat template list | /e33chat template set chat|whisper <模板>");
        return true;
    }

    private void mediaRequest(Player player, String mediaId) {
        if (!mediaId.matches("[0-9a-f]{32}")) return;
        CompletableFuture.runAsync(() -> {
            try {
                String encoded = redis() == null ? null : redis().get("e33chat:v1:media:" + mediaId);
                if (encoded == null) {
                    send(player, "media_response", E33Protocol.response(mediaId, 0, 1, new byte[0]));
                    return;
                }
                byte[] bytes = Base64.getDecoder().decode(encoded);
                int total = (bytes.length + CHUNK - 1) / CHUNK;
                for (int i = 0; i < total; i++)
                    send(player, "media_response", E33Protocol.response(mediaId, i, total,
                        Arrays.copyOfRange(bytes, i * CHUNK, Math.min(bytes.length, (i + 1) * CHUNK))));
            } catch (Exception ex) {
                send(player, "media_response", E33Protocol.response(mediaId, 0, 1, new byte[0]));
            }
        });
    }

}
