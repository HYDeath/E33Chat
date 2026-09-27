package me.arasple.mc.trchat.neoforge;

import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StateTest {
    @TempDir Path temporary;
    @Test void ignoreMuteAndGroupStateSurviveRestartWithoutPartialFile() throws Exception {
        Path file = temporary.resolve("world/trchat/state.json");
        var state = ChatState.load(file);
        UUID player = UUID.randomUUID();
        assertTrue(state.toggleIgnore(player, "Steve"));
        state.mutes.put(player.toString(), 12345L);
        state.groups.put(player.toString(), "team");
        state.replies.put(player.toString(), "Alex");
        state.save(file);
        var loaded = ChatState.load(file);
        assertTrue(loaded.ignores(player, "STEVE"));
        assertFalse(loaded.ignores(UUID.randomUUID(), "Steve"));
        assertEquals("team", loaded.groups.get(player.toString()));
        assertEquals(12345L, loaded.mutes.get(player.toString()));
        assertEquals("Alex", loaded.replies.get(player.toString()));
        assertFalse(Files.exists(file.resolveSibling("state.json.tmp")));
        assertFalse(loaded.toggleIgnore(player, "steve"));
        assertFalse(loaded.ignores(player, "Steve"));
    }
    @Test void invalidExistingConfigAndStateAreNeverOverwritten() throws Exception {
        Path file = temporary.resolve("config.json"); Files.writeString(file, "broken-json");
        assertThrows(java.io.IOException.class, () -> ChatConfig.load(file));
        assertThrows(java.io.IOException.class, () -> ChatState.load(file));
        assertEquals("broken-json", Files.readString(file));
    }
    @Test void configDefaultsAreSafeAndInvalidLimitsFail() throws Exception {
        Path file = temporary.resolve("config/trchat-neoforge.json");
        var config = ChatConfig.load(file);
        assertFalse(config.redis.enabled);
        assertEquals("trchat-message", config.redis.channel);
        assertEquals(config.serverName, ChatConfig.load(file).serverName);
        config.groups = java.util.List.of("unsafe space");
        assertThrows(IllegalArgumentException.class, () -> ChatConfig.validate(config));
        config.groups = java.util.List.of("team"); config.maxMessageLength = 999999;
        assertThrows(IllegalArgumentException.class, () -> ChatConfig.validate(config));
    }
}
