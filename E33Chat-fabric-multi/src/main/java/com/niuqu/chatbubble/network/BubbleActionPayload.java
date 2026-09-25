//#if MC >= 26000
package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 26.x client request (0) or equip (1) for a Custom-Nameplates bubble. */
public record BubbleActionPayload(int action, String bubbleId) implements CustomPayload {
    public static final Id<BubbleActionPayload> ID =
        new Id<>(Identifier.of("e33chat", "bubble_action"));
    public static final PacketCodec<PacketByteBuf, BubbleActionPayload> CODEC = PacketCodec.of(
        (buf, value) -> {
            buf.writeByte(value.action);
            buf.writeString(value.bubbleId);
        },
        buf -> new BubbleActionPayload(buf.readUnsignedByte(), buf.readString(128)));

    @Override public Id<BubbleActionPayload> getId() { return ID; }
}
//#endif
