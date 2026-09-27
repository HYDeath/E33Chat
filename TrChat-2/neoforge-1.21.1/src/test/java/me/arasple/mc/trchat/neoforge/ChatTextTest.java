package me.arasple.mc.trchat.neoforge;

import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatTextTest {
    @Test void gradientUsesExactColorsAndDoesNotLeakIntoPlayerOrBody() {
        var config = new ChatConfig();
        var prefix = ChatText.parse(config.serverPrefix);
        assertEquals("[模组服]", prefix.getString());
        assertEquals(List.of(0x8AE7B7, 0x89E98C, 0x87EB61, 0x86EC35, 0x84EE0A),
            prefix.toFlatList().stream().map(c -> c.getStyle().getColor().getValue()).toList());
        var message = ChatText.render(config.publicFormat, Map.of("prefix", prefix,
            "display", Component.literal("Steve").withStyle(ChatFormatting.GOLD), "message", Component.literal("hello")));
        assertEquals("[模组服] Steve: hello", message.getString());
        assertNull(message.toFlatList().stream().filter(c -> c.getString().equals("hello")).findFirst().orElseThrow().getStyle().getColor());
        assertEquals(ChatFormatting.GOLD.getColor().intValue(), message.toFlatList().stream().filter(c -> c.getString().equals("Steve")).findFirst().orElseThrow().getStyle().getColor().getValue());
    }
    @Test void hexLegacyResetAndInvalidHex() {
        var text = ChatText.parse("&#12abEF六&l粗&r普通&c红§a绿 &#bad原样");
        assertEquals("六粗普通红绿 &#bad原样", text.getString());
        assertEquals(0x12abef, text.toFlatList().getFirst().getStyle().getColor().getValue());
        assertTrue(text.toFlatList().get(1).getStyle().isBold());
        assertNull(text.toFlatList().get(2).getStyle().getColor());
        assertEquals(0x8AE7B7, ChatText.parse("&x&8&A&E&7&B&7测试").toFlatList().getFirst().getStyle().getColor().getValue());
    }
    @Test void stylesContinueAcrossRichComponentsAndNativeColorsArePreserved() {
        var message = new ChatText.Builder().append("&#123456前").append(Component.literal("链接").withStyle(ChatFormatting.AQUA)).append("后").build();
        assertEquals(0x123456, message.toFlatList().getLast().getStyle().getColor().getValue());
        assertEquals(ChatFormatting.AQUA.getColor().intValue(), message.toFlatList().get(1).getStyle().getColor().getValue());
    }
    @Test void mentionBoundariesAliasesAmbiguityAndUrls() {
        var alex = new PlayerDirectory.Entry("Alex", "小 张", UUID.randomUUID(), "Paper");
        var bob = new PlayerDirectory.Entry("Bob", "Hero", UUID.randomUUID(), "Neo");
        var duplicate = new PlayerDirectory.Entry("Sam", "Hero", UUID.randomUUID(), "Neo");
        var hits = Mentions.find("@alex! @Alexander foo@Alex @Hero @\"小 张\" https://example.com/@Bob @Bob", List.of(alex, bob, duplicate));
        assertEquals(List.of(alex, alex, bob), hits.stream().map(Mentions.Hit::player).toList());
    }
}
