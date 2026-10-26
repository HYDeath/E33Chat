package cn.xingchen.e33.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class PromptMarkerTest {
    @Test
    void removesOnlyKnownChannelMarkers() {
        for (String marker : new String[]{"w", "g", "t", "s", "m:Player2"}) {
            assertEquals("answer", XingchenChatBridge.removeChannelMarker("«" + marker + "»answer"));
        }
        assertNull(XingchenChatBridge.removeChannelMarker("answer"));
        assertNull(XingchenChatBridge.removeChannelMarker("«unknown»answer"));
        assertNull(XingchenChatBridge.removeChannelMarker("«m:»answer"));
    }
}
