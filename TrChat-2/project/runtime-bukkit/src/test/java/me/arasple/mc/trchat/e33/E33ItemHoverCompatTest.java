package me.arasple.mc.trchat.e33;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class E33ItemHoverCompatTest {
    @Test void protocol775KeepsOlderItemsAndRewrites263Items() {
        assertEquals(777, E33ItemHoverCompat.PROTOCOL_26_3);
        assertTrue(E33ItemHoverCompat.needsLegacyHover("minecraft:abandoned_camp_map"));
        assertTrue(E33ItemHoverCompat.needsLegacyHover("minecraft:buried_treasure_map"));
        assertTrue(E33ItemHoverCompat.needsLegacyHover("minecraft:red_poplar_leaves"));
        assertTrue(E33ItemHoverCompat.needsLegacyHover("minecraft:white_wool_stairs"));
        assertTrue(E33ItemHoverCompat.needsLegacyHover("minecraft:light_blue_concrete_slab"));
        assertTrue(E33ItemHoverCompat.needsLegacyHover("minecraft:orange_cushion"));
        assertTrue(E33ItemHoverCompat.needsLegacyHover("minecraft:straw_bed"));
        assertFalse(E33ItemHoverCompat.needsLegacyHover("minecraft:filled_map"));
        assertFalse(E33ItemHoverCompat.needsLegacyHover("minecraft:bread"));
        assertFalse(E33ItemHoverCompat.needsLegacyHover("minecraft:stone"));
    }

    @Test void campMapHoverBecomesTextBelow263AndStaysItemOn263() {
        Component source = Component.translatable("filled_map.dappled_forest_camp_map")
            .hoverEvent(HoverEvent.showItem(Key.key("minecraft:abandoned_camp_map"), 1))
            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/view-item 453d4327"));
        Component legacy = E33ItemHoverCompat.forProtocol(source, 775);
        assertEquals(HoverEvent.Action.SHOW_TEXT, legacy.hoverEvent().action());
        assertEquals("/view-item 453d4327", legacy.clickEvent().value());
        assertEquals("斑驳森林营地地图",
            PlainTextComponentSerializer.plainText().serialize(legacy));
        assertEquals("斑驳森林营地地图", PlainTextComponentSerializer.plainText().serialize(
            (Component) legacy.hoverEvent().value()));

        Component current = E33ItemHoverCompat.forProtocol(source, 777);
        assertSame(source, current);
        assertEquals(HoverEvent.Action.SHOW_ITEM, current.hoverEvent().action());
    }

    @Test void olderMapTranslationsStayOnTheClient() {
        Component level = Component.translatable("filled_map.level");
        assertSame(level, E33ItemHoverCompat.forProtocol(level, 775));
    }

    @Test void breadHoverSurvivesProtocol775() {
        Component source = Component.text("面包")
            .hoverEvent(HoverEvent.showItem(Key.key("minecraft:bread"), 4))
            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/view-item abc"));
        Component legacy = E33ItemHoverCompat.forProtocol(source, 775);
        assertSame(source, legacy);
        assertEquals(HoverEvent.Action.SHOW_ITEM, legacy.hoverEvent().action());
        assertEquals("/view-item abc", legacy.clickEvent().value());
    }
}
