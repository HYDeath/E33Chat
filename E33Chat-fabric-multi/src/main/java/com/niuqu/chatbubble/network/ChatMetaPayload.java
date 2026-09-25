package com.niuqu.chatbubble.network;

import com.niuqu.chatbubble.ChatMessageStore;
import net.minecraft.network.PacketByteBuf;
//#if MC >= 12005
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
//#endif
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record ChatMetaPayload(UUID senderUUID, String senderName, String messageHash,
                               String quoteSender, String quoteContent, List<String> mentionTargets)
        //#if MC >= 12005
        implements CustomPayload {
        //#else
        //$$ {
        //#endif
    //#if MC >= 12005
    public static final CustomPayload.Id<ChatMetaPayload> ID =
        new CustomPayload.Id<>(
            //#if MC >= 12000
            Identifier.of("e33chat", "chat_meta")
            //#else
            //$$ new Identifier("e33chat", "chat_meta")
            //#endif
        );

    public static final PacketCodec<PacketByteBuf, ChatMetaPayload> CODEC = PacketCodec.of(
        //#if MC >= 26000
        (buf, value) -> {
        //#else
        //$$ (value, buf) -> {
        //#endif
            buf.writeString(value.senderUUID.toString());
            buf.writeString(value.senderName);
            buf.writeString(value.messageHash);
            buf.writeString(value.quoteSender);
            buf.writeString(value.quoteContent);
            if (value.mentionTargets.size() > 128) throw new IllegalArgumentException("Too many mention targets");
            buf.writeVarInt(value.mentionTargets.size());
            for (String target : value.mentionTargets) buf.writeString(target);
        },
        buf -> new ChatMetaPayload(
            UUID.fromString(buf.readString()),
            buf.readString(),
            buf.readString(),
            buf.readString(),
            buf.readString(),
            readTargets(buf)
        )
    );

    private static List<String> readTargets(PacketByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > 128) throw new IllegalArgumentException("Invalid mention count: " + count);
        List<String> targets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) targets.add(buf.readString());
        return targets;
    }

    @Override
    public Id<ChatMetaPayload> getId() { return ID; }
    //#else
    //$$ public static final Identifier ID = new Identifier("e33chat", "chat_meta");
    //#endif
}
