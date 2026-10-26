package cn.xingchen.e33.server;

import cn.xingchen.chat.api.ChatDeliveryEvent;
import cn.xingchen.chat.api.ChatClearEvent;
import cn.xingchen.core.Xingchen;
import cn.xingchen.e33.protocol.XcProtocol;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The adapter never receives chat bodies or recipient lists from the client. */
public final class XingchenChatBridge extends JavaPlugin implements Listener, PluginMessageListener {
    private final Set<UUID> clients = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, XcProtocol.State> lastStates = new ConcurrentHashMap<>();
    private static final GsonComponentSerializer JSON = GsonComponentSerializer.gson();
    @Override public void onEnable() {
        getServer().getMessenger().registerIncomingPluginChannel(this, XcProtocol.HELLO, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, XcProtocol.DOWNSTREAM);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Xingchen E33 channel bridge enabled; XC-Chat remains authoritative.");
    }
    @Override public void onDisable() { clients.clear(); lastStates.clear(); }
    @Override public void onPluginMessageReceived(String channel, Player player, byte[] bytes) {
        if (!XcProtocol.HELLO.equals(channel) || !XcProtocol.validHello(bytes)) return;
        if (!clients.add(player.getUniqueId())) return;
        player.getScheduler().runAtFixedRate(this, task -> {
            if (!clients.contains(player.getUniqueId()) || !player.isOnline()) { task.cancel(); return; }
            updateState(player);
        }, () -> { clients.remove(player.getUniqueId()); lastStates.remove(player.getUniqueId()); }, 1L, 20L);
    }
    private void updateState(Player player) {
        var services = Xingchen.api().services();
        var guild = services.guilds();
        var party = services.parties();
        var plugin = getServer().getPluginManager().getPlugin("XC-Chat");
        String active = plugin instanceof cn.xingchen.chat.api.ChatChannelProvider provider
                ? provider.activeChannel(player.getUniqueId()) : "world";
        String recipient = plugin instanceof cn.xingchen.chat.api.ChatChannelProvider provider
                ? provider.whisperTarget(player.getUniqueId()) : "";
        var state = new XcProtocol.State(active, guild != null && !guild.members(player.getUniqueId()).isEmpty(),
                party != null && !party.members(player.getUniqueId()).isEmpty(),
                Xingchen.api().chat().pending(player.getUniqueId()), recipient);
        if (!state.equals(lastStates.get(player.getUniqueId())) && send(player, state))
            lastStates.put(player.getUniqueId(), state);
    }
    // Prompt state can change since the last tick. Remove metadata before XC-Core reads it.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPrompt(AsyncChatEvent event) {
        if (!clients.contains(event.getPlayer().getUniqueId()) || !Xingchen.api().chat().pending(event.getPlayer().getUniqueId())) return;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        String cleaned = removeChannelMarker(text);
        if (cleaned != null) event.message(Component.text(cleaned));
    }
    static String removeChannelMarker(String text) {
        if (text == null || !text.startsWith("«")) return null;
        int end = text.indexOf('»');
        if (end <= 1) return null;
        String marker = text.substring(1, end);
        if (Set.of("w", "g", "t", "s").contains(marker) || marker.startsWith("m:") && marker.length() > 2)
            return text.substring(end + 1);
        return null;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDelivery(ChatDeliveryEvent event) {
        Player player = event.recipient();
        if (!lastStates.containsKey(player.getUniqueId())) return;
        var packet = new XcProtocol.Message(event.id(), event.sender(), event.account(), event.channel(),
                JSON.serialize(event.display()), JSON.serialize(event.body()), JSON.serialize(event.rendered()),
                event.target() == null ? "" : event.target(), System.currentTimeMillis());
        // Oversized/failed delivery falls back to XC-Chat's original Adventure line.
        if (send(player, packet)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClear(ChatClearEvent event) {
        if (clients.contains(event.recipient().getUniqueId()) && send(event.recipient(), new XcProtocol.Clear()))
            event.setCancelled(true);
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        clients.remove(event.getPlayer().getUniqueId()); lastStates.remove(event.getPlayer().getUniqueId());
    }
    private boolean send(Player player, XcProtocol.Packet packet) {
        try {
            player.sendPluginMessage(this, XcProtocol.DOWNSTREAM, XcProtocol.encode(packet));
            return true;
        } catch (RuntimeException ex) { return false; }
    }
}
