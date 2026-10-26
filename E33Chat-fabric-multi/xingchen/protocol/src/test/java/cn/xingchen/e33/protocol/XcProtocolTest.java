package cn.xingchen.e33.protocol;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class XcProtocolTest {
    private XcProtocol.Message message(String channel, String body) {
        return new XcProtocol.Message(UUID.randomUUID(), UUID.randomUUID(), "Player1", channel,
                "{\"text\":\"称号 玩家\"}", body, "{\"text\":\"完整消息\"}", "Player2", 1234);
    }
    @Test void allChannelsRoundTrip() {
        for (String channel : XcProtocol.CHANNELS) {
            var message = message(channel, "{\"text\":\"你好😀\"}");
            assertEquals(message, XcProtocol.decode(XcProtocol.encode(message)));
        }
    }
    @Test void stateRoundTrip() {
        var state = new XcProtocol.State("party", true, false, true, "friend");
        assertEquals(state, XcProtocol.decode(XcProtocol.encode(state)));
    }
    @Test void clearRoundTrip() {
        assertEquals(new XcProtocol.Clear(), XcProtocol.decode(XcProtocol.encode(new XcProtocol.Clear())));
    }
    @Test void helloIsVersionedAndExact() {
        assertTrue(XcProtocol.validHello(XcProtocol.hello()));
        assertFalse(XcProtocol.validHello(null));
        assertFalse(XcProtocol.validHello(new byte[]{1, 0}));
        assertFalse(XcProtocol.validHello(new byte[]{2}));
    }
    @Test void rejectsUnknownChannel() {
        assertThrows(IllegalArgumentException.class, () -> message("admin", "body"));
        assertThrows(IllegalArgumentException.class, () -> new XcProtocol.State("all", false, false, false, ""));
    }
    @Test void rejectsVersionKindAndTrailingBytes() {
        assertThrows(IllegalArgumentException.class, () -> XcProtocol.decode(new byte[]{2, 3}));
        assertThrows(IllegalArgumentException.class, () -> XcProtocol.decode(new byte[]{1, 99}));
        assertThrows(IllegalArgumentException.class, () -> XcProtocol.decode(new byte[]{1, 3, 0}));
    }
    @Test void rejectsEveryTruncation() {
        byte[] bytes = XcProtocol.encode(message("guild", "{\"text\":\"test\"}"));
        for (int i = 0; i < bytes.length; i++) {
            byte[] truncated = Arrays.copyOf(bytes, i);
            assertThrows(IllegalArgumentException.class, () -> XcProtocol.decode(truncated));
        }
    }
    @Test void boundedPacketAndStrings() {
        assertThrows(IllegalArgumentException.class, () -> XcProtocol.decode(new byte[XcProtocol.MAX_PACKET + 1]));
        assertThrows(IllegalArgumentException.class, () -> XcProtocol.encode(message("world", "a".repeat(12289))));
        assertThrows(IllegalArgumentException.class, () -> XcProtocol.encode(message("world", "你".repeat(4097))));
    }
    @Test void rejectsNegativeAndOversizedLengths() {
        assertThrows(IllegalArgumentException.class, () -> XcProtocol.decode(new byte[]{1, 1, -1, -1, -1, -1}));
        assertThrows(IllegalArgumentException.class, () -> XcProtocol.decode(new byte[]{1, 1, 0, 0, 0, 17}));
    }
    @Test void rejectsMalformedUtf8() {
        byte[] bytes = new byte[]{1, 1, 0, 0, 0, 1, (byte) 0xff, 0, 0, 0};
        assertThrows(IllegalArgumentException.class, () -> XcProtocol.decode(bytes));
    }
    @Test void publicRoutesAreExplicit() {
        assertEquals("«w»hello", ChannelRouting.outgoing("hello", "#all", false, ""));
        assertEquals("«w»hello", ChannelRouting.outgoing("hello", "#world", false, ""));
        assertEquals("«g»hello", ChannelRouting.outgoing("hello", "公会", false, ""));
        assertEquals("«t»hello", ChannelRouting.outgoing("hello", "队伍", false, ""));
    }
    @Test void commandsAndPromptsStayPlain() {
        assertEquals("/msg Player2 hello", ChannelRouting.outgoing("/msg Player2 hello", "公会", false, ""));
        assertEquals("cancel", ChannelRouting.outgoing("cancel", "#system", true, ""));
        assertEquals("«s»hello", ChannelRouting.outgoing("hello", "#system", false, ""));
        assertThrows(IllegalArgumentException.class, () -> ChannelRouting.outgoing("hello", "#private", false, ""));
    }
    @Test void channelTabMapping() {
        assertEquals("公会", ChannelRouting.tab("guild"));
        assertEquals("队伍", ChannelRouting.tab("party"));
        assertEquals("#world", ChannelRouting.tab("world"));
        assertEquals("#private", ChannelRouting.tab("pm"));
        assertEquals("«m:Player2»hello", ChannelRouting.outgoing("hello", "#private", false, "Player2"));
        assertThrows(IllegalArgumentException.class, () -> ChannelRouting.outgoing("hello", "#private", false, ""));
    }
}
