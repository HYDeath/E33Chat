package me.arasple.mc.trchat.neoforge;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import me.arasple.mc.trchat.e33.E33DownstreamProtocol;
import me.arasple.mc.trchat.e33.E33Protocol;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    @Test void acceptsExistingTaboolibEnvelopeWithoutNeoFields() {
        var packet = WireMessage.decode("{\"data\":[\"ForwardMessage\",\"SendPrivateRaw\",\"Alex\",\"Steve\",\"{\\\"text\\\":\\\"你好\\\"}\",\"你好\",\"\",\"\",\"delivery-id\"]}");
        assertEquals("", packet.node());
        assertEquals("Alex", packet.data()[2]);
        assertEquals("delivery-id", packet.data()[8]);
    }
    @Test void acceptsBukkitNullNeoIdAndSingleElementData() {
        var chat = WireMessage.decode("{\"data\":[\"BroadcastRaw\",\"uuid\",\"{\\\"text\\\":\\\"hi\\\"}\",\"\",\"true\",\"\",\"hi\",\"Steve\",\"\",\"\"],\"messageId\":\"mid-1\",\"neoId\":null}");
        assertEquals("", chat.node());
        assertEquals("mid-1", chat.id());
        assertEquals("BroadcastRaw", chat.data()[0]);
        assertEquals("Steve", chat.data()[7]);
        var directory = WireMessage.decode("{\"data\":\"TrNeoDirectoryRequest\",\"messageId\":\"mid-2\",\"neoId\":null,\"neoNode\":null}");
        assertArrayEquals(new String[] {"TrNeoDirectoryRequest"}, directory.data());
        assertEquals("mid-2", directory.id());
        assertEquals("", directory.node());
        assertEquals("hello\u000cworld", WireMessage.decode("{\"data\":[\"hello\u000cworld\"],\"messageId\":\"mid-3\",\"neoId\":null}").data()[0]);
    }
    @Test void outboundDataRetainsBukkitIndexesAndUnicode() {
        String[] fields = {"BroadcastRaw", UUID.randomUUID().toString(), "{\"text\":\"[item] 中文\"}", "trchat.chat", "true", "", "中文", "Steve", "", ""};
        String raw = WireMessage.encode("node-1", fields);
        assertEquals("node-1", WireMessage.decode(raw).node());
        assertArrayEquals(fields, WireMessage.decode(raw).data());
        var data = JsonParser.parseString(raw).getAsJsonObject().getAsJsonArray("data");
        assertEquals("trchat.chat", data.get(3).getAsString());
        assertFalse(WireMessage.decode(raw).id().isBlank());
    }
    @Test void malformedWireDataIsRejected() {
        for (String invalid : List.of("{}", "{\"data\":[]}", "{\"data\":[null]}", "{\"data\":[42]}", "[]"))
            assertThrows(RuntimeException.class, () -> WireMessage.decode(invalid));
        assertThrows(IllegalArgumentException.class, () -> WireMessage.decode(" ".repeat(262145)));
    }
    @Test void respCommandsMeasureUtf8Bytes() throws Exception {
        var output = new ByteArrayOutputStream();
        RespConnection.write(output, "PUBLISH", "频道", "你好");
        assertEquals("*3\r\n$7\r\nPUBLISH\r\n$6\r\n频道\r\n$6\r\n你好\r\n", output.toString(StandardCharsets.UTF_8));
    }
    private static Object read(String text) throws IOException { return RespConnection.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))); }
    @Test void respReadsSubscriptionMessagesNilAndIntegers() throws Exception {
        assertEquals(List.of("message", "频道", "你好"), read("*3\r\n$7\r\nmessage\r\n$6\r\n频道\r\n$6\r\n你好\r\n"));
        assertNull(read("$-1\r\n"));
        assertEquals(12L, read(":12\r\n"));
        assertEquals("OK", read("+OK\r\n"));
    }
    @Test void respRejectsTruncatedOversizeAndDeepFrames() {
        for (String frame : List.of("$8\r\nhi\r\n", "$1048577\r\n", "*1025\r\n", "*1\r\n".repeat(10) + "+OK\r\n", "+bad\nx", ":garbage\r\n", "$-2\r\n"))
            assertThrows(IOException.class, () -> read(frame));
        IOException error = assertThrows(IOException.class, () -> read("-ERR secret\r\n"));
        assertFalse(error.getMessage().contains("secret"));
    }
    @Test void semanticChatPreservesModItemHoverAndPrivateRecipient() throws Exception {
        UUID id = UUID.randomUUID(), sender = UUID.randomUUID();
        String item = "{\"text\":\"模组物品\",\"hoverEvent\":{\"action\":\"show_item\",\"contents\":{\"id\":\"example:gear\",\"count\":2}}}";
        var chat = new E33Protocol.BridgeChat(id, sender, "Steve", "阿明", "模组服", "[item]", item, true, "Alex", "", "", List.of(), "", "", "");
        assertEquals(chat, E33Protocol.bridgeChat(E33Protocol.bridgeChat(chat)));
        byte[] packet = E33DownstreamProtocol.wrap("bridge_chat_v2", E33Protocol.bridgeChat(chat));
        assertEquals(4, packet[0]);
        assertEquals(chat, E33Protocol.bridgeChat(Arrays.copyOfRange(packet, 1, packet.length)));
    }
    @Test void oldAndNewUuidDirectoriesExpireWhenBackendDisappears() {
        var directory = new PlayerDirectory();
        UUID id = UUID.randomUUID();
        directory.update(new String[]{"UpdateNames", "25565", "Steve", "#", id.toString().replace("-", "")}, 1000);
        assertEquals(id, directory.players(2000).getFirst().uuid());
        directory.update(new String[]{"UpdateNames", "25566", "Alex", "阿明", UUID.randomUUID().toString(), "模组服"}, 2000);
        assertEquals(2, directory.players(3000).size());
        assertEquals("模组服", directory.players(31001).getFirst().server());
        assertTrue(directory.players(32001).isEmpty());
    }
    @Test void emptyDirectoryClearsPlayersAndMismatchedFieldsFail() {
        var directory = new PlayerDirectory();
        directory.update(new String[]{"UpdateNames", "1", "Alex", "#", UUID.randomUUID().toString()}, 0);
        directory.update(new String[]{"UpdateNames", "1", "", "", ""}, 1);
        assertTrue(directory.players(2).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> directory.update(new String[]{"UpdateNames", "1", "Alex,Steve", "#", UUID.randomUUID().toString()}, 3));
    }
}
