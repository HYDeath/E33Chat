//#if MC >= 12005
package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Exact counterpart of the plugin's E33Protocol.bridgeChat wire format. */
public record BridgeChatPayload(UUID messageId, UUID sender, String account, String displayName,
                                String origin, String body, String bodyJson, boolean privateMessage,
                                String recipient, String quoteSender, String quoteContent,
                                List<UUID> mentions, String nameplate) implements CustomPayload {
    public static final Id<BridgeChatPayload> ID = new Id<>(Identifier.of("e33chat", "bridge_chat_v1"));
    public static final PacketCodec<PacketByteBuf, BridgeChatPayload> CODEC = PacketCodec.of(
        //#if MC >= 26000
        (buf, value) -> write(buf, value),
        //#else
        //$$ (value, buf) -> write(buf, value),
        //#endif
        BridgeChatPayload::read);

    private static void write(PacketByteBuf buf, BridgeChatPayload value) {
        buf.writeVarInt(1);
        buf.writeString(value.messageId.toString());
        buf.writeString(value.sender.toString());
        buf.writeString(value.account);
        buf.writeString(value.displayName);
        buf.writeString(value.origin);
        buf.writeString(value.body);
        buf.writeString(value.bodyJson);
        buf.writeBoolean(value.privateMessage);
        buf.writeString(value.recipient);
        buf.writeString(value.quoteSender);
        buf.writeString(value.quoteContent);
        if (value.mentions.size() > 128) throw new IllegalArgumentException("Too many bridge mentions");
        buf.writeVarInt(value.mentions.size());
        for (UUID id : value.mentions) buf.writeString(id.toString());
        buf.writeString(value.nameplate);
    }

    private static BridgeChatPayload read(PacketByteBuf buf) {
        if (buf.readVarInt() != 1) throw new IllegalArgumentException("Unsupported bridge protocol");
        UUID id = UUID.fromString(buf.readString(36));
        UUID sender = UUID.fromString(buf.readString(36));
        String account = buf.readString(64);
        String display = buf.readString(1024);
        String origin = buf.readString(128);
        String body = buf.readString(8192);
        String json = buf.readString(16384);
        boolean privateMessage = buf.readBoolean();
        String recipient = buf.readString(64);
        String quoteSender = buf.readString(1024);
        String quoteContent = buf.readString(4096);
        int count = buf.readVarInt();
        if (count < 0 || count > 128) throw new IllegalArgumentException("Invalid mention count");
        List<UUID> mentions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) mentions.add(UUID.fromString(buf.readString(36)));
        String nameplate = buf.readString(8192);
        if (buf.readableBytes() != 0) throw new IllegalArgumentException("Trailing bridge chat data");
        return new BridgeChatPayload(id, sender, account, display, origin, body, json,
            privateMessage, recipient, quoteSender, quoteContent, mentions, nameplate);
    }

    @Override public Id<BridgeChatPayload> getId() { return ID; }
}
//#endif
