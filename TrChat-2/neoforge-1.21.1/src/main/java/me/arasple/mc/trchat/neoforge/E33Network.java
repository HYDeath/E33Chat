package me.arasple.mc.trchat.neoforge;

import java.util.*;
import me.arasple.mc.trchat.e33.E33DownstreamProtocol;
import me.arasple.mc.trchat.e33.E33Protocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Optional wire-compatible E33 bridge; ordinary modpack clients need no companion. */
final class E33Network {
    static volatile ChatService service;
    private static final Map<String, CustomPacketPayload.Type<Raw>> TYPES = new HashMap<>();
    private static final Set<UUID> ready = new HashSet<>();
    record Raw(CustomPacketPayload.Type<Raw> type, byte[] bytes) implements CustomPacketPayload { }

    static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        for (String channel : List.of("downstream_v3", "bridge_ready_v1", "bridge_chat_v1", "bridge_chat_v2",
            "chat_history", "config_sync_v2", "server_config_screen", "media_cap")) {
            var type = type(channel);
            registrar.playToClient(type, codec(type), (payload, context) -> { });
        }
        for (String channel : List.of("bridge_hello_v1", "quote_sync", "server_config_save")) {
            var type = type(channel);
            registrar.playToServer(type, codec(type), (payload, context) -> {
                ChatService active = service;
                if (active != null && context.player() instanceof ServerPlayer player)
                    active.clientPayload(player, channel, payload.bytes());
            });
        }
        var emoji = type("emoji_catalog");
        registrar.playBidirectional(emoji, codec(emoji), (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) send(player, "emoji_catalog", new byte[0]);
        });
    }
    private static CustomPacketPayload.Type<Raw> type(String channel) {
        return TYPES.computeIfAbsent(channel, name -> new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("e33chat", name)));
    }
    private static StreamCodec<RegistryFriendlyByteBuf, Raw> codec(CustomPacketPayload.Type<Raw> type) {
        return new StreamCodec<>() {
            public Raw decode(RegistryFriendlyByteBuf buffer) {
                if (buffer.readableBytes() > 32760) throw new IllegalArgumentException("E33 payload too large");
                byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); return new Raw(type, bytes);
            }
            public void encode(RegistryFriendlyByteBuf buffer, Raw payload) { buffer.writeBytes(payload.bytes()); }
        };
    }
    static boolean supports(ServerPlayer player, String channel) {
        return player.connection.hasChannel(type(channel));
    }
    static void send(ServerPlayer player, String channel, byte[] bytes) {
        if (bytes.length > 32760) return;
        if (supports(player, "downstream_v3")) {
            PacketDistributor.sendToPlayer(player, new Raw(type("downstream_v3"), E33DownstreamProtocol.wrap(channel, bytes)));
        } else if (supports(player, channel)) PacketDistributor.sendToPlayer(player, new Raw(type(channel), bytes));
    }
    static boolean chat(ServerPlayer player, E33Protocol.BridgeChat chat) {
        if (!supports(player, "downstream_v3") && !supports(player, "bridge_chat_v2") && !supports(player, "bridge_chat_v1")) return false;
        if (ready.add(player.getUUID())) send(player, "bridge_ready_v1", new byte[]{1});
        try {
            if (supports(player, "downstream_v3") || supports(player, "bridge_chat_v2")) send(player, "bridge_chat_v2", E33Protocol.bridgeChat(chat));
            else send(player, "bridge_chat_v1", E33Protocol.bridgeChatV1(chat));
            return true;
        } catch (IllegalArgumentException ex) { return false; }
    }
    static void forget(UUID uuid) { ready.remove(uuid); }
    static void clear() { ready.clear(); }
}
