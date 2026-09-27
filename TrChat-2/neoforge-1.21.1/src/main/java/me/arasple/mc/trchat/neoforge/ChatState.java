package me.arasple.mc.trchat.neoforge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** All mutations occur on the Minecraft server thread. */
final class ChatState {
    public boolean globalMute;
    public String clientSettings = "";
    public Map<String, Long> mutes = new HashMap<>();
    public Map<String, Set<String>> ignored = new HashMap<>();
    public Map<String, String> groups = new HashMap<>();
    public Map<String, String> replies = new HashMap<>();

    static ChatState load(Path path) throws IOException {
        if (!Files.exists(path)) return new ChatState();
        try {
            ChatState state = ChatConfig.JSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), ChatState.class);
            if (state == null || state.mutes == null || state.ignored == null || state.groups == null || state.replies == null)
                throw new IOException("Invalid TrChat state");
            return state;
        } catch (RuntimeException ex) { throw new IOException("Invalid TrChat state JSON", ex); }
    }
    void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, ChatConfig.JSON.toJson(this), StandardCharsets.UTF_8);
        try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
    }
    boolean ignores(UUID receiver, String sender) {
        return ignored.getOrDefault(receiver.toString(), Set.of()).contains(sender.toLowerCase(Locale.ROOT));
    }
    boolean toggleIgnore(UUID receiver, String sender) {
        Set<String> values = ignored.computeIfAbsent(receiver.toString(), ignored -> new HashSet<>());
        String key = sender.toLowerCase(Locale.ROOT);
        if (values.remove(key)) return false;
        values.add(key); return true;
    }
}
