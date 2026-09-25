//#if MC >= 26000
package com.niuqu.chatbubble.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.ArrayList;
import java.util.List;

/** One page of the server's Custom-Nameplates bubble styles and permissions. */
public record BubbleCatalogPayload(boolean available, boolean first, boolean last,
                                   String selected, List<Entry> entries) implements CustomPayload {
    public record Entry(String id, String label, String previewJson, boolean unlocked, String skinSpec) {}

    public static final Id<BubbleCatalogPayload> ID =
        new Id<>(Identifier.of("e33chat", "bubble_catalog"));
    public static final PacketCodec<PacketByteBuf, BubbleCatalogPayload> CODEC = PacketCodec.of(
        (buf, value) -> {
            buf.writeBoolean(value.available);
            buf.writeBoolean(value.first);
            buf.writeBoolean(value.last);
            buf.writeString(value.selected);
            buf.writeVarInt(value.entries.size());
            for (Entry entry : value.entries) {
                buf.writeString(entry.id);
                buf.writeString(entry.label);
                buf.writeString(entry.previewJson);
                buf.writeBoolean(entry.unlocked);
                buf.writeString(entry.skinSpec);
            }
        },
        BubbleCatalogPayload::decode);

    private static BubbleCatalogPayload decode(PacketByteBuf buf) {
        // TrChat e33compat.11 sent entries without skinSpec. Try the current
        // format first, then restart at the same byte for that older server.
        int start = buf.readerIndex();
        try {
            return decodePage(buf, true);
        } catch (RuntimeException incompatible) {
            buf.readerIndex(start);
            return decodePage(buf, false);
        }
    }

    private static BubbleCatalogPayload decodePage(PacketByteBuf buf, boolean withSkinSpec) {
        boolean available = buf.readBoolean();
        boolean first = buf.readBoolean();
        boolean last = buf.readBoolean();
        String selected = buf.readString(128);
        int count = buf.readVarInt();
        if (count < 0 || count > 64) throw new IllegalArgumentException("Invalid bubble catalogue page");
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = buf.readString(128);
            String label = buf.readString(256);
            String preview = buf.readString(4096);
            boolean unlocked = buf.readBoolean();
            String skinSpec = withSkinSpec ? buf.readString(2048) : "";
            entries.add(new Entry(id, label, preview, unlocked, skinSpec));
        }
        if (buf.readableBytes() != 0) throw new IllegalArgumentException("Trailing bubble catalogue data");
        return new BubbleCatalogPayload(available, first, last, selected, List.copyOf(entries));
    }

    @Override public Id<BubbleCatalogPayload> getId() { return ID; }
}
//#endif
