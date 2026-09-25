//#if MC >= 26000
package com.niuqu.chatbubble.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One advertised S2C channel for all E33 messages. The first byte identifies
 * the existing payload codec; the remaining bytes keep its original wire format.
 */
public record DownstreamPayload(int kind, byte[] data) implements CustomPayload {
    public static final Id<DownstreamPayload> ID =
        new Id<>(Identifier.of("e33chat", "downstream_v3"));
    // Fabric servers can send longer history snapshots than Bukkit plugin messages.
    // The TrChat bridge separately caps its plugin message body below 32 KiB.
    private static final int MAX_DATA_BYTES = 1_048_576;
    public static final PacketCodec<PacketByteBuf, DownstreamPayload> CODEC = PacketCodec.of(
        (buf, value) -> {
            if (value.kind < 1 || value.kind > 13 || value.data.length > MAX_DATA_BYTES)
                throw new IllegalArgumentException("Invalid E33 downstream payload");
            buf.writeByte(value.kind);
            buf.writeBytes(value.data);
        },
        buf -> {
            int kind = buf.readUnsignedByte();
            int size = buf.readableBytes();
            if (kind < 1 || kind > 13 || size > MAX_DATA_BYTES)
                throw new IllegalArgumentException("Invalid E33 downstream payload");
            byte[] data = new byte[size];
            buf.readBytes(data);
            return new DownstreamPayload(kind, data);
        });

    public static DownstreamPayload wrap(CustomPayload payload) {
        int kind;
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            if (payload instanceof ChatMetaPayload value) {
                kind = 1; ChatMetaPayload.CODEC.encode(buf, value);
            } else if (payload instanceof BridgeReadyPayload value) {
                kind = 2; BridgeReadyPayload.CODEC.encode(buf, value);
            } else if (payload instanceof BridgeChatPayload value) {
                kind = 3; BridgeChatPayload.CODEC.encode(buf, value);
            } else if (payload instanceof BridgeChatV2Payload value) {
                kind = 4; BridgeChatV2Payload.CODEC.encode(buf, value);
            } else if (payload instanceof CraftEmojiCatalogPayload value) {
                kind = 5; CraftEmojiCatalogPayload.CODEC.encode(buf, value);
            } else if (payload instanceof HistoryPayload value) {
                kind = 6; HistoryPayload.CODEC.encode(buf, value);
            } else if (payload instanceof ConfigSyncPayload value) {
                kind = 7; ConfigSyncPayload.CODEC.encode(buf, value);
            } else if (payload instanceof ConfigSyncV2Payload value) {
                kind = 8; ConfigSyncV2Payload.CODEC.encode(buf, value);
            } else if (payload instanceof ServerConfigScreenPayload value) {
                kind = 9; ServerConfigScreenPayload.CODEC.encode(buf, value);
            } else if (payload instanceof MediaUploadAckPayload value) {
                kind = 10; MediaUploadAckPayload.CODEC.encode(buf, value);
            } else if (payload instanceof MediaResponsePayload value) {
                kind = 11; MediaResponsePayload.CODEC.encode(buf, value);
            } else if (payload instanceof MediaCapPayload value) {
                kind = 12; MediaCapPayload.CODEC.encode(buf, value);
            } else if (payload instanceof BubbleCatalogPayload value) {
                kind = 13; BubbleCatalogPayload.CODEC.encode(buf, value);
            } else {
                throw new IllegalArgumentException("Unsupported E33 downstream payload: " + payload.getId());
            }
            byte[] data = new byte[buf.readableBytes()];
            if (data.length > MAX_DATA_BYTES) throw new IllegalArgumentException("E33 payload too large");
            buf.readBytes(data);
            return new DownstreamPayload(kind, data);
        } finally {
            buf.release();
        }
    }

    public CustomPayload unwrap() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(data));
        try {
            CustomPayload payload = switch (kind) {
                case 1 -> ChatMetaPayload.CODEC.decode(buf);
                case 2 -> BridgeReadyPayload.CODEC.decode(buf);
                case 3 -> BridgeChatPayload.CODEC.decode(buf);
                case 4 -> BridgeChatV2Payload.CODEC.decode(buf);
                case 5 -> CraftEmojiCatalogPayload.CODEC.decode(buf);
                case 6 -> decodeHistory(buf);
                case 7 -> ConfigSyncPayload.CODEC.decode(buf);
                case 8 -> ConfigSyncV2Payload.CODEC.decode(buf);
                case 9 -> decodeServerConfigScreen(buf);
                case 10 -> MediaUploadAckPayload.CODEC.decode(buf);
                case 11 -> MediaResponsePayload.CODEC.decode(buf);
                case 12 -> MediaCapPayload.CODEC.decode(buf);
                case 13 -> BubbleCatalogPayload.CODEC.decode(buf);
                default -> throw new IllegalArgumentException("Unknown E33 downstream kind: " + kind);
            };
            if (buf.readableBytes() != 0) throw new IllegalArgumentException("Trailing E33 downstream data");
            return payload;
        } finally {
            buf.release();
        }
    }

    /** TrChat's existing downstream channel still carries the pre-2.4.10
     * formats. Accept those while also accepting the current Fabric codecs. */
    private static HistoryPayload decodeHistory(PacketByteBuf buf) {
        int start = buf.readerIndex();
        try {
            HistoryPayload current = HistoryPayload.CODEC.decode(buf);
            if (buf.readableBytes() == 0) return current;
        } catch (RuntimeException ignored) {
            // A legacy row has no group field. Reset before decoding it.
        }
        buf.readerIndex(start);
        int count = buf.readVarInt();
        if (count < 0 || count > 50) throw new IllegalArgumentException("Invalid bridge history count");
        List<HistoryPayload.HistoryEntry> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new HistoryPayload.HistoryEntry(
                UUID.fromString(buf.readString(36)), buf.readString(), buf.readString(),
                buf.readLong(), buf.readBoolean(), emptyToNull(buf.readString()),
                emptyToNull(buf.readString()), null));
        }
        return new HistoryPayload(rows);
    }

    private static ServerConfigScreenPayload decodeServerConfigScreen(PacketByteBuf buf) {
        int start = buf.readerIndex();
        try {
            ServerConfigScreenPayload current = ServerConfigScreenPayload.CODEC.decode(buf);
            if (buf.readableBytes() == 0) return current;
        } catch (RuntimeException ignored) {
            // Legacy TrChat put the media flags after the template lists.
        }
        buf.readerIndex(start);
        boolean useTpa = buf.readBoolean();
        boolean history = buf.readBoolean();
        boolean debug = buf.readBoolean();
        List<String> chat = readLegacyTemplates(buf);
        List<String> whisper = readLegacyTemplates(buf);
        boolean media = buf.readBoolean();
        boolean autoClean = buf.readBoolean();
        return new ServerConfigScreenPayload(useTpa, history, debug,
            media, autoClean, true, false, chat, whisper);
    }

    private static List<String> readLegacyTemplates(PacketByteBuf buf) {
        int count = buf.readInt();
        if (count < 0 || count > 128) throw new IllegalArgumentException("Invalid bridge template count");
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(buf.readString());
        return values;
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    @Override public Id<DownstreamPayload> getId() { return ID; }
}
//#endif
