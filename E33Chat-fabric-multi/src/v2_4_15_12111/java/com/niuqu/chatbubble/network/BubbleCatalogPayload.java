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
        (value, buf) -> {
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
        buf -> {
            boolean available = buf.readBoolean();
            boolean first = buf.readBoolean();
            boolean last = buf.readBoolean();
            String selected = buf.readString(128);
            int count = buf.readVarInt();
            if (count < 0 || count > 64) throw new IllegalArgumentException("Invalid bubble catalogue page");
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++)
                entries.add(new Entry(buf.readString(128), buf.readString(256),
                    buf.readString(4096), buf.readBoolean(), buf.readString(2048)));
            return new BubbleCatalogPayload(available, first, last, selected, List.copyOf(entries));
        });

    @Override public Id<BubbleCatalogPayload> getId() { return ID; }
}
