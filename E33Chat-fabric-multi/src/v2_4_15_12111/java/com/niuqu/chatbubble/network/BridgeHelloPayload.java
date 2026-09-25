//#if MC >= 12005
package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Explicit client opt-in, so legacy E33 clients keep the old delivery path. */
public record BridgeHelloPayload(int version) implements CustomPayload {
    public static final Id<BridgeHelloPayload> ID = new Id<>(Identifier.of("e33chat", "bridge_hello_v1"));
    public static final PacketCodec<PacketByteBuf, BridgeHelloPayload> CODEC = PacketCodec.of(
        //#if MC >= 26000
        (buf, value) -> buf.writeVarInt(value.version()),
        //#else
        //$$ (value, buf) -> buf.writeVarInt(value.version()),
        //#endif
        buf -> new BridgeHelloPayload(buf.readVarInt()));

    @Override public Id<BridgeHelloPayload> getId() { return ID; }
}
//#endif
