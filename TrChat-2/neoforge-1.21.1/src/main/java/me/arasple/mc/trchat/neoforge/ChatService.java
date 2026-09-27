package me.arasple.mc.trchat.neoforge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import me.arasple.mc.trchat.e33.E33Protocol;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

final class ChatService implements AutoCloseable {
    private static final String HISTORY_KEY = "e33chat:v1:history";
    private static final Pattern TOKENS = Pattern.compile("\\{(server|display|player|message)\\}");
    private static final Pattern LINKS = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private final MinecraftServer server;
    private final Path configPath, statePath;
    private final String node = UUID.randomUUID().toString();
    private final ChatState state;
    private ChatConfig config;
    private RedisBridge redis;
    private final PlayerDirectory directory = new PlayerDirectory();
    private final Map<UUID, Long> lastSent = new HashMap<>();
    private final Map<UUID, String> lastText = new HashMap<>();
    private final Map<String, Long> seen = new HashMap<>();
    private final Map<String, Pending> pending = new HashMap<>();
    private final Map<UUID, E33Protocol.Quote> quotes = new HashMap<>();
    private final Map<UUID, Long> clientRequests = new HashMap<>();
    private final Deque<E33Protocol.HistoryEntry> history = new ArrayDeque<>();
    private int ticks;
    private boolean connected;
    private boolean closed;
    private E33Protocol.Settings settings = new E33Protocol.Settings(false, true, false,
        List.of("{prefix}{display_name}{sep}{content}"),
        List.of("[我 ➦ {target}] {content}", "[{sender} ➥ 我] {content}"), false, true);
    private record Pending(UUID sender, String target, Component echo, E33Protocol.BridgeChat chat, long expires) { }

    ChatService(MinecraftServer server, Path configPath) throws IOException {
        this.server = server;
        this.configPath = configPath;
        statePath = server.getWorldPath(LevelResource.ROOT).resolve("trchat/state.json");
        config = ChatConfig.load(configPath);
        state = ChatState.load(statePath);
        if (state.clientSettings != null && !state.clientSettings.isBlank()) {
            try { settings = E33Protocol.settings(Base64.getDecoder().decode(state.clientSettings)); }
            catch (IOException | IllegalArgumentException ex) { TrChatMod.LOGGER.warn("Invalid saved E33 templates; using defaults"); }
        }
        connect();
    }
    private void connect() {
        if (config.redis.enabled) redis = new RedisBridge(config.redis, text -> {
            WireMessage message = WireMessage.decode(text);
            server.execute(() -> {
                if (closed) return;
                try { remote(message); }
                catch (Exception ex) { TrChatMod.LOGGER.warn("Rejected invalid TrChat Redis event"); }
            });
        });
    }
    void reload() throws IOException {
        ChatConfig replacement = ChatConfig.load(configPath);
        if (redis != null) redis.close();
        redis = null; connected = false; config = replacement; connect();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) syncClient(player);
    }
    private CompletableFuture<Boolean> publish(String... data) {
        return redis == null ? CompletableFuture.completedFuture(false) : redis.publish(WireMessage.encode(node, data));
    }
    private void save() {
        state.clientSettings = Base64.getEncoder().encodeToString(E33Protocol.screen(settings));
        try { state.save(statePath); }
        catch (IOException ex) { TrChatMod.LOGGER.error("Could not save TrChat player state", ex); }
    }
    private static void notice(ServerPlayer player, String text) { player.sendSystemMessage(Component.literal(text)); }
    private ServerPlayer local(String account) {
        return server.getPlayerList().getPlayers().stream().filter(p -> PlayerNames.account(p).equalsIgnoreCase(account)).findFirst().orElse(null);
    }
    List<PlayerDirectory.Entry> players() {
        Map<UUID, PlayerDirectory.Entry> merged = new LinkedHashMap<>();
        if (redis != null && redis.available()) for (var player : directory.players(System.currentTimeMillis())) merged.put(player.uuid(), player);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) merged.put(player.getUUID(), new PlayerDirectory.Entry(
            PlayerNames.account(player), PlayerNames.plain(player), player.getUUID(), config.serverName));
        return new ArrayList<>(merged.values());
    }
    List<String> names() { return players().stream().map(PlayerDirectory.Entry::account).distinct().sorted().toList(); }
    List<String> groups() { return config.groups; }
    private PlayerDirectory.Entry resolve(String input) {
        var exact = players().stream().filter(p -> p.account().equalsIgnoreCase(input)).findFirst();
        if (exact.isPresent()) return exact.get();
        var displays = players().stream().filter(p -> p.display().equalsIgnoreCase(input)).toList();
        return displays.size() == 1 ? displays.getFirst() : null;
    }

    private boolean allowed(ServerPlayer player, String text) {
        if (text.isBlank() || text.length() > config.maxMessageLength) {
            notice(player, "§c消息为空或过长，最多 " + config.maxMessageLength + " 个字符。"); return false;
        }
        if (text.codePoints().anyMatch(c -> c < 32 || c == 127 || c == 167)) {
            notice(player, "§c消息包含不可用的控制字符。"); return false;
        }
        boolean bypass = ChatPermissions.has(player, ChatPermissions.BYPASS);
        long now = System.currentTimeMillis();
        if (!bypass && (state.globalMute || state.mutes.getOrDefault(player.getUUID().toString(), 0L) > now)) {
            notice(player, "§c当前已禁言。"); return false;
        }
        if (!bypass && now - lastSent.getOrDefault(player.getUUID(), 0L) < config.cooldownMillis) {
            notice(player, "§c发言过快，请稍后再试。"); return false;
        }
        if (!bypass && config.blockRepeatedMessages && text.equals(lastText.get(player.getUUID()))
            && now - lastSent.getOrDefault(player.getUUID(), 0L) < 30000) {
            notice(player, "§c请勿重复发送相同消息。"); return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (!bypass && config.blockedWords.stream().anyMatch(word -> lower.contains(word.toLowerCase(Locale.ROOT)))) {
            notice(player, "§c消息包含被屏蔽的词语。"); return false;
        }
        lastSent.put(player.getUUID(), now); lastText.put(player.getUUID(), text);
        return true;
    }

    void publicChat(ServerPlayer sender, String text) {
        String group = state.groups.get(sender.getUUID().toString());
        if (group != null && config.groups.contains(group)) { groupChat(sender, group, text); return; }
        globalChat(sender, text);
    }
    void globalChat(ServerPlayer sender, String text) {
        if (!ChatPermissions.has(sender, ChatPermissions.CHAT)) { notice(sender, "§c没有聊天权限。"); return; }
        if (!allowed(sender, text)) return;
        Component body = body(sender, text);
        Component rendered = bounded(format(sender, body));
        E33Protocol.BridgeChat chat = semantic(sender, text, body, rendered, false, "");
        for (ServerPlayer receiver : server.getPlayerList().getPlayers())
            if (ChatPermissions.has(receiver, ChatPermissions.CHAT)) deliver(receiver, rendered, chat, PlayerNames.account(sender));
        server.sendSystemMessage(rendered);
        addHistory(chat);
        if (redis != null) {
            var entry = history.getLast();
            redis.command("EVAL", "redis.call('LPUSH', KEYS[1], ARGV[1]); redis.call('LTRIM', KEYS[1], 0, 49); return 1",
                "1", HISTORY_KEY, Base64.getEncoder().encodeToString(E33Protocol.history(List.of(entry))));
        }
        publish("BroadcastRaw", sender.getUUID().toString(), json(rendered), "trchat.chat", "true", "",
            rendered.getString(), PlayerNames.account(sender), "", encoded(chat)).thenAccept(ok -> {
                if (!ok && config.redis.enabled) server.execute(() -> noticeIfOnline(sender.getUUID(), "§e跨服连接不可用，本条消息仅在本服显示。"));
            });
    }

    private Component body(ServerPlayer sender, String text) {
        MutableComponent result = Component.empty();
        int offset = 0;
        var matcher = Pattern.compile("\\[(?:item|i)\\]").matcher(text);
        while (matcher.find()) {
            result.append(links(text.substring(offset, matcher.start())));
            ItemStack item = sender.getMainHandItem();
            if (!config.itemHover || item.isEmpty()) result.append(Component.literal("[空手]"));
            else {
                MutableComponent shown = Component.literal("[").append(item.getHoverName().copy())
                    .append(" ×" + item.getCount() + "]").withStyle(ChatFormatting.AQUA);
                shown.withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackInfo(item))));
                // Modded item data can be enormous. Fall back to a readable hover before crossing the wire.
                if (json(shown).length() > 6000) shown.withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.literal(item.getHoverName().getString() + "\n" + BuiltInRegistries.ITEM.getKey(item.getItem()) + " ×" + item.getCount()))));
                result.append(shown);
            }
            offset = matcher.end();
        }
        result.append(links(text.substring(offset)));
        return bounded(result);
    }
    private Component bounded(Component value) {
        if (json(value).length() <= 16384) return value;
        String text = value.getString();
        return Component.literal(text.length() > 8192 ? text.substring(0, 8192) : text);
    }
    private static Component links(String text) {
        MutableComponent output = Component.empty(); int offset = 0;
        var matcher = LINKS.matcher(text);
        while (matcher.find()) {
            output.append(text.substring(offset, matcher.start()));
            String url = matcher.group();
            output.append(Component.literal(url).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))));
            offset = matcher.end();
        }
        return output.append(text.substring(offset));
    }
    private Component format(ServerPlayer sender, Component body) {
        MutableComponent result = Component.empty(); int offset = 0;
        var matcher = TOKENS.matcher(config.publicFormat);
        while (matcher.find()) {
            result.append(config.publicFormat.substring(offset, matcher.start()));
            result.append(switch (matcher.group(1)) {
                case "server" -> Component.literal(config.serverName);
                case "display" -> PlayerNames.display(sender);
                case "player" -> Component.literal(PlayerNames.account(sender));
                default -> body;
            });
            offset = matcher.end();
        }
        return result.append(config.publicFormat.substring(offset));
    }
    private String json(Component component) { return Component.Serializer.toJson(component, server.registryAccess()); }
    private Component component(String raw, String fallback) {
        try { Component parsed = Component.Serializer.fromJson(raw, server.registryAccess()); if (parsed != null) return parsed; }
        catch (Exception ignored) { }
        return Component.literal(fallback);
    }
    private E33Protocol.BridgeChat semantic(ServerPlayer sender, String text, Component body, Component rendered, boolean privateMessage, String recipient) {
        E33Protocol.Quote quote = quotes.remove(sender.getUUID());
        List<UUID> mentions = players().stream().filter(p -> text.contains("@" + p.account())).map(PlayerDirectory.Entry::uuid).limit(128).toList();
        Component display = PlayerNames.display(sender);
        return new E33Protocol.BridgeChat(UUID.randomUUID(), sender.getUUID(), PlayerNames.account(sender), display.getString(),
            config.serverName, text, json(body), privateMessage, recipient, quote == null ? "" : quote.sender(), quote == null ? "" : quote.content(),
            mentions, "", json(display), json(rendered));
    }
    private static String encoded(E33Protocol.BridgeChat chat) {
        try { return Base64.getEncoder().encodeToString(E33Protocol.bridgeChat(chat)); }
        catch (IllegalArgumentException ex) { return ""; }
    }
    private static E33Protocol.BridgeChat decoded(String value) {
        if (value.isEmpty()) return null;
        try { return E33Protocol.bridgeChat(Base64.getDecoder().decode(value)); }
        catch (Exception ex) { return null; }
    }
    private boolean deliver(ServerPlayer receiver, Component rendered, E33Protocol.BridgeChat chat, String account) {
        if (!account.isEmpty() && state.ignores(receiver.getUUID(), account)) return false;
        if (chat == null || !E33Network.chat(receiver, chat)) receiver.sendSystemMessage(rendered);
        if (chat != null && chat.mentions().contains(receiver.getUUID())) notice(receiver, "§e" + chat.account() + " 提到了你。");
        return true;
    }

    void privateChat(ServerPlayer sender, String targetName, String text) {
        if (!ChatPermissions.has(sender, ChatPermissions.PRIVATE)) { notice(sender, "§c没有私聊权限。"); return; }
        PlayerDirectory.Entry target = resolve(targetName);
        if (target == null) { notice(sender, "§c玩家不在线，或昵称存在歧义。"); return; }
        if (!allowed(sender, text)) return;
        Component body = body(sender, text);
        ServerPlayer receiver = local(target.account());
        Component targetDisplay = receiver == null ? Component.literal(target.display()) : PlayerNames.display(receiver);
        Component received = bounded(Component.literal("[" + config.serverName + "] [").append(PlayerNames.display(sender)).append(" ➥ 我] ").append(body.copy()));
        Component echo = bounded(Component.literal("[我 ➦ ").append(targetDisplay).append("] ").append(body.copy()));
        E33Protocol.BridgeChat chat = semantic(sender, text, body, received, true, target.account());
        if (receiver != null) {
            if (!ChatPermissions.has(receiver, ChatPermissions.PRIVATE) || !deliver(receiver, received, chat, chat.account())) {
                notice(sender, "§c目标玩家当前不接收私聊。"); return;
            }
            state.replies.put(receiver.getUUID().toString(), chat.account());
            state.replies.put(sender.getUUID().toString(), target.account());
            deliver(sender, echo, echo(chat, echo), ""); save(); return;
        }
        if (redis == null || !redis.available()) { notice(sender, "§c跨服私信发送失败：Redis 未连接。"); return; }
        String id = UUID.randomUUID().toString();
        pending.put(id, new Pending(sender.getUUID(), target.account(), echo, chat, System.currentTimeMillis() + 5000));
        publish("ForwardMessage", "SendPrivateRaw", target.account(), chat.account(), json(received), received.getString(), json(body), encoded(chat), id)
            .thenAccept(ok -> { if (!ok) server.execute(() -> failPrivate(id, "§c跨服私信发送失败，目标玩家未确认收到。")); });
    }
    private E33Protocol.BridgeChat echo(E33Protocol.BridgeChat chat, Component rendered) {
        return new E33Protocol.BridgeChat(chat.messageId(), chat.sender(), chat.account(), chat.displayName(), chat.origin(), chat.body(), chat.bodyJson(),
            true, chat.recipient(), chat.quoteSender(), chat.quoteContent(), chat.mentions(), chat.nameplate(), chat.displayJson(), json(rendered));
    }
    private void acknowledge(String id) {
        Pending sent = pending.remove(id); if (sent == null) return;
        ServerPlayer sender = server.getPlayerList().getPlayer(sent.sender());
        if (sender != null) { deliver(sender, sent.echo(), echo(sent.chat(), sent.echo()), ""); state.replies.put(sent.sender().toString(), sent.target()); save(); }
    }
    private void failPrivate(String id, String reason) {
        Pending sent = pending.remove(id); if (sent != null) noticeIfOnline(sent.sender(), reason);
    }
    void reply(ServerPlayer sender, String text) {
        String target = state.replies.get(sender.getUUID().toString());
        if (target == null) { notice(sender, "§c还没有可回复的玩家。"); return; }
        privateChat(sender, target, text);
    }
    void ignore(ServerPlayer player, String name) {
        PlayerDirectory.Entry target = resolve(name);
        if (target != null) {
            name = target.account();
        } else if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            notice(player, "§c请使用玩家账号名。"); return;
        }
        boolean blocked = state.toggleIgnore(player.getUUID(), name); save();
        notice(player, (blocked ? "§e已屏蔽 " : "§a已取消屏蔽 ") + name + " 的公开聊天、群聊和私聊。");
    }
    void ignoreList(ServerPlayer player) { notice(player, "屏蔽列表：" + String.join(", ", state.ignored.getOrDefault(player.getUUID().toString(), Set.of()))); }

    void groupJoin(ServerPlayer player, String group) {
        if (!ChatPermissions.has(player, ChatPermissions.GROUP) || !config.groups.contains(group)) { notice(player, "§c无权限或群聊不存在。"); return; }
        state.groups.put(player.getUUID().toString(), group); save();
        notice(player, "§a已进入群聊 " + group + "，直接聊天将发送到该群；/trgroup leave 退出。");
    }
    void groupLeave(ServerPlayer player) { state.groups.remove(player.getUUID().toString()); save(); notice(player, "§a已切回公开聊天。"); }
    void groupChat(ServerPlayer sender, String group, String text) {
        if (!config.groups.contains(group) || !group.equals(state.groups.get(sender.getUUID().toString())) || !ChatPermissions.has(sender, ChatPermissions.GROUP)) {
            notice(sender, "§c请先加入该群聊。"); return;
        }
        if (!allowed(sender, text)) return;
        Component rendered = bounded(Component.literal("[群 " + group + "] [" + config.serverName + "] ").append(PlayerNames.display(sender)).append(": ")
            .append(body(sender, text)));
        quotes.remove(sender.getUUID());
        deliverGroup(group, PlayerNames.account(sender), rendered);
        publish("TrNeoGroup", node, UUID.randomUUID().toString(), group, PlayerNames.account(sender), json(rendered), rendered.getString())
            .thenAccept(ok -> { if (!ok && config.redis.enabled) server.execute(() -> noticeIfOnline(sender.getUUID(), "§e群聊跨服连接不可用，仅发送到本服群成员。")); });
    }
    private void deliverGroup(String group, String account, Component rendered) {
        for (ServerPlayer receiver : server.getPlayerList().getPlayers())
            if (group.equals(state.groups.get(receiver.getUUID().toString())) && ChatPermissions.has(receiver, ChatPermissions.GROUP)
                && !state.ignores(receiver.getUUID(), account)) receiver.sendSystemMessage(rendered);
    }

    void remote(WireMessage envelope) throws IOException {
        if (node.equals(envelope.node())) return;
        if (!envelope.id().isBlank() && seen.putIfAbsent(envelope.id(), System.currentTimeMillis()) != null) return;
        String[] data = envelope.data();
        if (data[0].equals("ForwardMessage")) data = Arrays.copyOfRange(data, 1, data.length);
        if (data.length == 0) return;
        switch (data[0]) {
            case "UpdateNames" -> directory.update(data, System.currentTimeMillis());
            case "BroadcastRaw" -> {
                if (data.length < 6) return;
                if (!data[5].isBlank() && !Arrays.asList(data[5].split(";")).contains(Integer.toString(config.directoryId))) return;
                Component rendered = component(data[2], data.length > 6 ? data[6] : "");
                String account = data.length > 7 ? data[7] : "";
                E33Protocol.BridgeChat chat = data.length > 9 ? decoded(data[9]) : null;
                if (chat != null && chat.privateMessage()) return;
                if (account.isBlank() && chat != null) account = chat.account();
                if (chat != null && seen.putIfAbsent(chat.messageId().toString(), System.currentTimeMillis()) != null) return;
                for (ServerPlayer receiver : server.getPlayerList().getPlayers())
                    if (ChatPermissions.listen(receiver, data[3])) deliver(receiver, rendered, chat, account);
                server.sendSystemMessage(rendered);
                if (chat != null && (data[3].isBlank() || data[3].equals("trchat.chat"))) addHistory(chat);
            }
            case "SendPrivateRaw" -> {
                if (data.length < 4) return;
                ServerPlayer receiver = local(data[1]); if (receiver == null) return;
                String deliveryId = data.length > 7 ? data[7] : "";
                if (!deliveryId.isEmpty() && seen.containsKey("pm:" + deliveryId)) {
                    publish("ForwardMessage", "PrivateDelivered", deliveryId); return;
                }
                if (!ChatPermissions.has(receiver, ChatPermissions.PRIVATE) || state.ignores(receiver.getUUID(), data[2])) {
                    publish("ForwardMessage", "TrNeoPrivateRejected", deliveryId); return;
                }
                Component rendered = component(data[3], data.length > 4 ? data[4] : "");
                E33Protocol.BridgeChat chat = data.length > 6 ? decoded(data[6]) : null;
                if (chat != null && (!chat.privateMessage() || !chat.recipient().equalsIgnoreCase(data[1]))) chat = null;
                deliver(receiver, rendered, chat, data[2]);
                if (!deliveryId.isEmpty()) seen.put("pm:" + deliveryId, System.currentTimeMillis());
                state.replies.put(receiver.getUUID().toString(), data[2]); save();
                if (!deliveryId.isEmpty()) publish("ForwardMessage", "PrivateDelivered", deliveryId);
            }
            case "PrivateDelivered" -> { if (data.length > 1) acknowledge(data[1]); }
            case "TrNeoPrivateRejected" -> { if (data.length > 1) failPrivate(data[1], "§c目标玩家当前不接收私聊。"); }
            case "GlobalMute" -> { if (data.length > 1) {
                state.globalMute = data[1].equals("on"); save();
                if (redis != null) redis.command("SET", "trchat:neo:global-mute", Boolean.toString(state.globalMute));
            } }
            case "TrNeoMute" -> { if (data.length >= 3) { UUID.fromString(data[1]); state.mutes.put(data[1], Long.parseLong(data[2])); save(); } }
            case "TrNeoGroup" -> {
                if (data.length >= 7 && !node.equals(data[1]) && config.groups.contains(data[3])) deliverGroup(data[3], data[4], component(data[5], data[6]));
            }
            case "E33Settings" -> {
                if (data.length >= 3) { settings = E33Protocol.settings(Base64.getDecoder().decode(data[2])); save(); }
                for (ServerPlayer player : server.getPlayerList().getPlayers()) syncClient(player);
            }
            case "E33Reply" -> {
                if (data.length >= 5) { ServerPlayer target = local(data[3]); if (target != null) { state.replies.put(target.getUUID().toString(), data[4]); save(); } }
            }
            // E33Meta is a companion to BroadcastRaw, never a second chat delivery.
            default -> { }
        }
    }

    private void addHistory(E33Protocol.BridgeChat chat) {
        if (chat.privateMessage()) return;
        E33Protocol.HistoryEntry entry = new E33Protocol.HistoryEntry(chat.sender(),
            (chat.origin().isBlank() ? "" : "[" + chat.origin() + "] ") + chat.account(), chat.body(), System.currentTimeMillis(), false, chat.quoteContent(), chat.quoteSender());
        history.addLast(entry); while (history.size() > 50) history.removeFirst();
    }
    void showHistory(ServerPlayer player) {
        notice(player, "§7最近公开聊天（最多 50 条）：");
        for (var entry : history) if (!state.ignores(player.getUUID(), historyAccount(entry)))
            player.sendSystemMessage(Component.literal(entry.name() + ": " + entry.content()));
    }
    private static String historyAccount(E33Protocol.HistoryEntry entry) {
        String name = entry.name(); int prefix = name.lastIndexOf("] "); return prefix < 0 ? name : name.substring(prefix + 2);
    }
    void list(CommandSourceStack source) {
        var players = players();
        source.sendSuccess(() -> Component.literal("在线玩家 " + players.size() + "：" + String.join(", ", players.stream()
            .map(p -> p.account() + " [" + p.server() + "]").toList())), false);
    }
    void info(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("TrChat NeoForge 1.21.1 | " + config.serverName + " | Redis "
            + (redis == null ? "未启用" : redis.available() ? "已连接" : "断开") + " | 群聊 " + String.join(", ", config.groups)), false);
    }
    void mute(CommandSourceStack source, String name, int seconds) {
        PlayerDirectory.Entry target = resolve(name);
        if (target == null) { source.sendFailure(Component.literal("玩家不在线或昵称存在歧义。")); return; }
        long until = seconds == 0 ? 0 : System.currentTimeMillis() + seconds * 1000L;
        state.mutes.put(target.uuid().toString(), until); save();
        publish("TrNeoMute", target.uuid().toString(), Long.toString(until));
        if (redis != null) redis.command("SET", "trchat:neo:mute:" + target.uuid(), Long.toString(until));
        source.sendSuccess(() -> Component.literal(seconds == 0 ? "已解除 " + target.account() + " 禁言。" : "已禁言 " + target.account() + " " + seconds + " 秒。"), true);
    }
    void globalMute(CommandSourceStack source, boolean muted) {
        state.globalMute = muted; save(); publish("GlobalMute", muted ? "on" : "off");
        if (redis != null) redis.command("SET", "trchat:neo:global-mute", Boolean.toString(muted));
        source.sendSuccess(() -> Component.literal(muted ? "已开启全服禁言。" : "已关闭全服禁言。"), true);
    }
    void announce(CommandSourceStack source, String text) {
        Component rendered = Component.literal("[公告] " + text).withStyle(ChatFormatting.GOLD);
        server.getPlayerList().broadcastSystemMessage(rendered, false);
        publish("BroadcastRaw", new UUID(0, 0).toString(), json(rendered), "", "true", "", rendered.getString(), "", "", "");
        source.sendSuccess(() -> Component.literal("已发布公告。"), true);
    }
    void clear(CommandSourceStack source) {
        Component blank = Component.literal("\n".repeat(100) + "聊天已由管理员清屏。");
        server.getPlayerList().broadcastSystemMessage(blank, false);
        source.sendSuccess(() -> Component.literal("已清理本服聊天显示。"), true);
    }

    void join(ServerPlayer player) {
        updateDirectory(null);
        if (config.showJoinLeave) systemNotice(PlayerNames.account(player) + " 加入了 " + config.serverName);
        syncClient(player);
        if (redis != null) {
            UUID uuid = player.getUUID();
            redis.command("GET", "trchat:neo:mute:" + uuid).thenAccept(value -> server.execute(() -> {
                if (!closed && value instanceof String until) { try { state.mutes.put(uuid.toString(), Long.parseLong(until)); save(); } catch (NumberFormatException ignored) { } }
            }));
        }
    }
    void quit(ServerPlayer player) {
        if (config.showJoinLeave) systemNotice(PlayerNames.account(player) + " 离开了 " + config.serverName);
        updateDirectory(player.getUUID());
        lastSent.remove(player.getUUID()); lastText.remove(player.getUUID()); quotes.remove(player.getUUID());
        clientRequests.remove(player.getUUID()); E33Network.forget(player.getUUID()); save();
    }
    private void systemNotice(String text) {
        // Minecraft already owns the local join/leave notice; this packet goes to other servers.
        Component rendered = Component.literal(text).withStyle(ChatFormatting.YELLOW);
        publish("BroadcastRaw", new UUID(0, 0).toString(), json(rendered), "", "true", "", rendered.getString(), "", "", "");
    }
    private void updateDirectory(UUID departing) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers().stream().filter(p -> !p.getUUID().equals(departing)).toList();
        publish("UpdateNames", Integer.toString(config.directoryId), String.join(",", players.stream().map(PlayerNames::account).toList()),
            String.join(",", players.stream().map(p -> PlayerNames.plain(p).replace(',', '，')).toList()),
            String.join(",", players.stream().map(p -> p.getUUID().toString()).toList()), config.serverName);
        for (ServerPlayer player : players) publish("E33Alias", node, player.getUUID().toString(), PlayerNames.account(player), PlayerNames.plain(player));
        if (departing != null) publish("E33AliasGone", node, departing.toString());
    }
    void tick() {
        long now = System.currentTimeMillis();
        for (String id : new ArrayList<>(pending.keySet())) if (pending.get(id).expires() < now) failPrivate(id, "§c跨服私信未获目标服务器确认，请检查连接或玩家在线状态。");
        if (++ticks % 200 != 0) return;
        seen.values().removeIf(time -> now - time > 60000);
        if (redis != null) {
            redis.heartbeat();
            boolean available = redis.available();
            if (available && !connected) {
                redis.command("GET", "trchat:neo:global-mute").thenAccept(value -> server.execute(() -> {
                    if (!closed && value instanceof String mute) { state.globalMute = Boolean.parseBoolean(mute); save(); }
                }));
                redis.command("GET", "e33chat:v1:settings").thenAccept(value -> server.execute(() -> {
                    try { if (!closed && value instanceof String encoded) { settings = E33Protocol.settings(Base64.getDecoder().decode(encoded)); } }
                    catch (Exception ignored) { }
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) syncClient(player);
                }));
            }
            connected = available;
        }
        updateDirectory(null);
    }
    private void noticeIfOnline(UUID uuid, String text) { ServerPlayer player = server.getPlayerList().getPlayer(uuid); if (!closed && player != null) notice(player, text); }

    void clientPayload(ServerPlayer player, String channel, byte[] bytes) {
        long now = System.currentTimeMillis();
        if (now - clientRequests.getOrDefault(player.getUUID(), 0L) < 250) return;
        clientRequests.put(player.getUUID(), now);
        try {
            switch (channel) {
                case "bridge_hello_v1" -> syncClient(player);
                case "quote_sync" -> {
                    E33Protocol.Quote quote = E33Protocol.quote(bytes);
                    if (quote.sender().length() <= 1024 && quote.content().length() <= 4096 && quote.target().length() <= 8192) quotes.put(player.getUUID(), quote);
                }
                case "server_config_save" -> {
                    if (!ChatPermissions.has(player, ChatPermissions.ADMIN)) { notice(player, "§c无管理员权限。"); return; }
                    E33Protocol.Settings candidate = E33Protocol.clientSave(bytes);
                    if (candidate.chatTemplates().isEmpty() || candidate.chatTemplates().size() > 16 || candidate.whisperTemplates().size() > 16
                        || candidate.chatTemplates().stream().anyMatch(t -> t.length() > 512) || candidate.whisperTemplates().stream().anyMatch(t -> t.length() > 512)) return;
                    settings = new E33Protocol.Settings(false, candidate.history(), candidate.debug(), candidate.chatTemplates(), candidate.whisperTemplates(), false, candidate.autoClean());
                    save();
                    for (ServerPlayer online : server.getPlayerList().getPlayers()) syncClient(online);
                    if (redis != null) redis.command("SET", "e33chat:v1:settings", Base64.getEncoder().encodeToString(E33Protocol.screen(settings)));
                    publish("E33Settings", node, Base64.getEncoder().encodeToString(E33Protocol.screen(settings)));
                    notice(player, "§aE33 显示模板已保存。");
                }
                default -> { }
            }
        } catch (Exception ex) { notice(player, "§c无效的 E33 请求。"); }
    }
    void syncClient(ServerPlayer player) {
        E33Network.send(player, "config_sync_v2", E33Protocol.config(settings));
        E33Network.send(player, "media_cap", E33Protocol.encode(out -> out.writeBoolean(false)));
        if (settings.history()) {
            var entries = history.stream().filter(e -> !state.ignores(player.getUUID(), historyAccount(e))).toList();
            byte[] bytes = E33Protocol.history(entries);
            if (bytes.length < 32760) E33Network.send(player, "chat_history", bytes);
        }
    }
    void clientGui(ServerPlayer player) {
        if (!ChatPermissions.has(player, ChatPermissions.ADMIN)) return;
        E33Network.send(player, "server_config_screen", E33Protocol.screen(settings));
    }
    @Override public void close() {
        closed = true;
        if (redis != null) redis.close();
        E33Network.clear(); save();
    }
}
