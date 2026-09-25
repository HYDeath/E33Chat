package me.arasple.mc.trchat.e33;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class E33ProtocolTest {
    @Test void senderTemplateKeepsSourceServerTitleAndAccount() {
        assertEquals("[创造服] 称号 HuYa_Death",
            E33SenderFormat.format("[{server_name}] {title}{player_id}",
                "创造服", "称号 ", "HuYa_Death", "聊天昵称"));
        assertEquals("聊天昵称", E33SenderFormat.format("{display_name}",
            "创造服", "称号 ", "HuYa_Death", "聊天昵称"));
    }

    @Test void downstreamEnvelopeUsesOneChannelWithoutChangingInnerWireBytes() {
        byte[] raw = E33Protocol.ack(7L, "media", "");
        byte[] envelope = E33DownstreamProtocol.wrap("media_upload_ack", raw);
        assertEquals(10, envelope[0] & 0xff);
        assertArrayEquals(raw, java.util.Arrays.copyOfRange(envelope, 1, envelope.length));
        assertEquals(4, E33DownstreamProtocol.wrap("bridge_chat_v2", new byte[0])[0] & 0xff);
        assertEquals(13, E33DownstreamProtocol.wrap("bubble_catalog", new byte[0])[0] & 0xff);
        assertThrows(IllegalArgumentException.class,
            () -> E33DownstreamProtocol.wrap("unknown", new byte[0]));
        assertThrows(IllegalArgumentException.class,
            () -> E33DownstreamProtocol.wrap("chat_meta", new byte[32_761]));
    }

    @Test void configUsesMinecraftUtfAndBigEndianTemplateCounts() {
        E33Protocol.Settings settings = new E33Protocol.Settings(true, false, true,
            List.of("x"), List.of(), true, false);
        assertEquals("010000000101780000000001", HexFormat.of().formatHex(E33Protocol.config(settings)));
        assertEquals(settings, assertDoesNotThrow(() -> E33Protocol.settings(E33Protocol.screen(settings))));
        assertEquals(settings, assertDoesNotThrow(() -> E33Protocol.clientSave(E33Protocol.screen(settings))));
        byte[] modernSave = E33Protocol.encode(out -> {
            out.writeBoolean(true);  // useTpa
            out.writeBoolean(false); // history
            out.writeBoolean(true);  // debug
            out.writeBoolean(true);  // media
            out.writeBoolean(false); // autoClean
            out.writeBoolean(true);  // easyBot
            out.writeBoolean(false); // groups
            out.writeInt(1);
            E33Protocol.string(out, "x");
            out.writeInt(0);
        });
        assertEquals(settings, assertDoesNotThrow(() -> E33Protocol.clientSave(modernSave)));
    }

    @Test void varIntMatchesMinecraftEncoding() throws IOException {
        byte[] bytes = E33Protocol.encode(out -> {
            E33Protocol.varInt(out, 0);
            E33Protocol.varInt(out, 127);
            E33Protocol.varInt(out, 128);
            E33Protocol.varInt(out, 16_384);
        });
        assertEquals("007f8001808001", HexFormat.of().formatHex(bytes));
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        assertEquals(0, E33Protocol.varInt(in));
        assertEquals(127, E33Protocol.varInt(in));
        assertEquals(128, E33Protocol.varInt(in));
        assertEquals(16_384, E33Protocol.varInt(in));
    }

    @Test void metaAndHistoryHaveBoundedCollections() throws IOException {
        UUID sender = UUID.fromString("00000000-0000-0000-0000-000000000001");
        byte[] meta = E33Protocol.meta(sender, "Steve", "123", "", "", List.of("阿明"));
        assertEquals(1, meta[meta.length - "阿明".getBytes(java.nio.charset.StandardCharsets.UTF_8).length - 2]);
        E33Protocol.HistoryEntry entry = new E33Protocol.HistoryEntry(sender, "[lobby] 阿明",
            "你好", 123L, false, "", "");
        assertEquals(List.of(entry), E33Protocol.history(E33Protocol.history(List.of(entry))));
        assertThrows(IllegalArgumentException.class,
            () -> E33Protocol.history(java.util.Collections.nCopies(51, entry)));
        assertThrows(IOException.class, () -> E33Protocol.history(new byte[] {51}));
    }

    @Test void rejectsTruncatedAndOversizePayloads() {
        assertThrows(IOException.class, () -> E33Protocol.quote(new byte[] {2, 'x'}));
        byte[] invalidUpload = E33Protocol.encode(out -> {
            out.writeLong(1L);
            out.writeInt(0);
            out.writeInt(1);
            out.writeInt(8 * 1024 * 1024 + 1);
            E33Protocol.string(out, "image/png");
            E33Protocol.byteArray(out, new byte[] {1});
        });
        assertThrows(IOException.class, () -> E33Protocol.upload(invalidUpload));
    }

    @Test void quoteAndMediaChannelsMatchGoldenBytes() throws IOException {
        assertEquals(new E33Protocol.Quote("A", "B", "C"),
            E33Protocol.quote(HexFormat.of().parseHex("014101420143")));
        assertEquals("0000000000000001017800",
            HexFormat.of().formatHex(E33Protocol.ack(1L, "x", "")));
        assertEquals("0178000000000000000102ff00",
            HexFormat.of().formatHex(E33Protocol.response("x", 0, 1, new byte[] {(byte) 0xff, 0})));
        assertEquals("x", E33Protocol.mediaRequest(HexFormat.of().parseHex("0178")));
        byte[] upload = E33Protocol.encode(out -> {
            out.writeLong(1L);
            out.writeInt(0);
            out.writeInt(1);
            out.writeInt(1);
            E33Protocol.string(out, "image/png");
            E33Protocol.byteArray(out, new byte[] {42});
        });
        E33Protocol.Upload parsed = E33Protocol.upload(upload);
        assertEquals(1L, parsed.id());
        assertEquals(1, parsed.bytes());
        assertArrayEquals(new byte[] {42}, parsed.data());
    }

    @Test void bridgeChatV1RoundTripsInClientFieldOrder() throws IOException {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID sender = UUID.fromString("00000000-0000-0000-0000-000000000002");
        E33Protocol.BridgeChat chat = new E33Protocol.BridgeChat(id, sender, "Steve", "中文昵称",
            "lobby", "你好 😀", "{\"text\":\"你好 😀\"}", true, "Alex", "Alice", "原文",
            List.of(id), "", "", "");
        byte[] bytes = E33Protocol.bridgeChatV1(chat);
        assertEquals(chat, E33Protocol.bridgeChat(bytes));
        DataInputStream in = E33Protocol.input(bytes);
        assertEquals(1, E33Protocol.varInt(in));
        assertEquals(id.toString(), E33Protocol.string(in));
        assertEquals(sender.toString(), E33Protocol.string(in));
        assertEquals("Steve", E33Protocol.string(in));
        assertEquals("中文昵称", E33Protocol.string(in));
        assertEquals("lobby", E33Protocol.string(in));
        assertEquals("你好 😀", E33Protocol.string(in));
        assertEquals("{\"text\":\"你好 😀\"}", E33Protocol.string(in));
        assertTrue(in.readBoolean());
        assertEquals("Alex", E33Protocol.string(in));
        assertEquals("Alice", E33Protocol.string(in));
        assertEquals("原文", E33Protocol.string(in));
        assertEquals(1, E33Protocol.varInt(in));
        assertEquals(id.toString(), E33Protocol.string(in));
        assertEquals("", E33Protocol.string(in));
        assertEquals(0, in.available());
        assertEquals("012430303030303030302d303030302d303030302d303030302d303030303030303030303031",
            HexFormat.of().formatHex(bytes, 0, 38));
    }

    @Test void bridgeRejectsMalformedAndOversizePayload() {
        UUID id = new UUID(0, 0);
        E33Protocol.BridgeChat chat = new E33Protocol.BridgeChat(id, id, "a", "b", "c", "d",
            "{}", false, "", "", "", List.of(), "", "", "");
        byte[] good = E33Protocol.bridgeChat(chat);
        assertThrows(IOException.class, () -> E33Protocol.bridgeChat(java.util.Arrays.copyOf(good, good.length - 1)));
        good[0] = 3;
        assertThrows(IOException.class, () -> E33Protocol.bridgeChat(good));
        assertThrows(IllegalArgumentException.class, () -> E33Protocol.bridgeChat(
            new E33Protocol.BridgeChat(id, id, "a", "b", "c", "x".repeat(8193),
                "{}", false, "", "", "", List.of(), "", "", "")));
    }
}
