package com.niuqu.chatbubble.network;

//#if MC >= 12005
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.nio.charset.StandardCharsets;

/** Permission-filtered CraftEngine shortcodes sent by the current server. */
public record CraftEmojiCatalogPayload(String encoded) implements CustomPayload {
    public static final Id<CraftEmojiCatalogPayload> ID =
        new Id<>(Identifier.of("e33chat", "emoji_catalog"));
    public static final PacketCodec<PacketByteBuf, CraftEmojiCatalogPayload> CODEC = PacketCodec.of(
        //#if MC >= 26000
        (buf, value) -> buf.writeBytes(value.encoded.getBytes(StandardCharsets.UTF_8)),
        //#else
        //$$ (value, buf) -> buf.writeBytes(value.encoded.getBytes(StandardCharsets.UTF_8)),
        //#endif
        buf -> {
            int size = buf.readableBytes();
            if (size > 24_000) throw new IllegalArgumentException("CraftEngine emoji catalog too large");
            byte[] bytes = new byte[size];
            buf.readBytes(bytes);
            return new CraftEmojiCatalogPayload(new String(bytes, StandardCharsets.UTF_8));
        });

    @Override public Id<CraftEmojiCatalogPayload> getId() { return ID; }
}
//#endif
