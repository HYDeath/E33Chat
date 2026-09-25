//#if MC >= 12005
package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record BridgeReadyPayload(boolean ready) implements CustomPayload {
    public static final Id<BridgeReadyPayload> ID = new Id<>(Identifier.of("e33chat", "bridge_ready_v1"));
    public static final PacketCodec<PacketByteBuf, BridgeReadyPayload> CODEC = PacketCodec.of(
        //#if MC >= 26000
        (buf, value) -> buf.writeBoolean(value.ready()),
        //#else
        //$$ (value, buf) -> buf.writeBoolean(value.ready()),
        //#endif
        buf -> new BridgeReadyPayload(buf.readBoolean()));

    @Override public Id<BridgeReadyPayload> getId() { return ID; }
}
//#endif
