package me.arasple.mc.trchat.e33;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class E33LinkTargetsTest {
    private static final String VIDEO =
        "https://www.bilibili.com/video/BV1kuht6fEX9/?trackid=web_pegasus_0.router-web-pegasus-2479516-sm4rx.1790247404221.1002"
        + "&spm_id_from=333.1007.tianma.1-1-1.click&vd_source=7cdc40afedaa85ae0ddb8c0b943246af";

    @Test void keepsTheActualVideoTargetWhenTrChatDisplaysOnlyALinkLabel() {
        List<String> links = E33LinkTargets.fromMessage(VIDEO);
        assertEquals(List.of(VIDEO), links);
        assertEquals("看这个" + VIDEO + "吧",
            E33LinkTargets.restorePlaceholders("看这个[链接]吧", links));
    }

    @Test void acceptsMarkdownEscapesWithoutLosingTheQuery() {
        String escaped = VIDEO.replace("_", "\\_").replace("&", "\\&");
        assertEquals(List.of(VIDEO), E33LinkTargets.fromMessage(escaped));
    }

    @Test void leavesOtherMessagesAndFilteredTextUntouched() {
        assertEquals("普通聊天", E33LinkTargets.restorePlaceholders("普通聊天", List.of(VIDEO)));
        assertEquals("[链接]", E33LinkTargets.restorePlaceholders("[链接]", List.of()));
        assertTrue(E33LinkTargets.fromMessage("不包含网址").isEmpty());
    }

    @Test void acceptsOnlyTargetsMatchingTheActualChatMessage() {
        assertEquals(List.of(VIDEO), E33LinkTargets.fromMatchedMessage(VIDEO, "[链接]"));
        assertEquals(List.of(VIDEO), E33LinkTargets.fromMatchedMessage("看这个" + VIDEO,
            "看这个[链接]"));
        assertTrue(E33LinkTargets.fromMatchedMessage(VIDEO, "别人的[链接]").isEmpty());
    }

}
