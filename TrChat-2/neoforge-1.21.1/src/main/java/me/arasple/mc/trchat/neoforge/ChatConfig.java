package me.arasple.mc.trchat.neoforge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Server-wide configuration. Never log this object: it contains Redis credentials. */
public final class ChatConfig {
    static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    public String serverName = "模组服";
    public int directoryId = 25565;
    public String publicFormat = "[{server}] {display}: {message}";
    public int maxMessageLength = 256;
    public int cooldownMillis = 1000;
    public boolean blockRepeatedMessages = true;
    public boolean showJoinLeave = true;
    public boolean itemHover = true;
    public List<String> blockedWords = List.of();
    public List<String> groups = List.of("team", "trade");
    public Redis redis = new Redis();

    public static final class Redis {
        public boolean enabled = false;
        public String host = "127.0.0.1";
        public int port = 6379;
        public String username = "";
        public String password = "";
        public int database = 0;
        public boolean ssl = false;
        public String channel = "trchat-message";
        public int timeoutMillis = 3000;
    }

    public static ChatConfig load(Path path) throws IOException {
        ChatConfig config;
        if (!Files.exists(path)) {
            config = new ChatConfig();
            Files.createDirectories(path.getParent());
            Files.writeString(path, JSON.toJson(config), StandardCharsets.UTF_8);
        } else {
            try { config = JSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), ChatConfig.class); }
            catch (RuntimeException ex) { throw new IOException("Invalid TrChat JSON config", ex); }
        }
        try { validate(config); }
        catch (RuntimeException ex) { throw new IOException("Invalid TrChat config: " + ex.getMessage(), ex); }
        return config;
    }

    static void validate(ChatConfig c) {
        if (c == null || c.redis == null || c.groups == null || c.blockedWords == null)
            throw new IllegalArgumentException("Required config field is null");
        if (c.serverName == null || c.serverName.length() > 128 || c.publicFormat == null || c.publicFormat.length() > 512)
            throw new IllegalArgumentException("Invalid serverName/publicFormat");
        if (c.directoryId < 1 || c.maxMessageLength < 1 || c.maxMessageLength > 1024 || c.cooldownMillis < 0)
            throw new IllegalArgumentException("Invalid directoryId/message limit/cooldown");
        if (c.groups.size() > 64 || c.groups.stream().anyMatch(g -> g == null || !g.matches("[a-z0-9_-]{1,32}")))
            throw new IllegalArgumentException("Group names must match [a-z0-9_-]{1,32}");
        if (c.blockedWords.stream().anyMatch(w -> w == null || w.isBlank()))
            throw new IllegalArgumentException("Blocked words cannot be empty");
        Redis r = c.redis;
        if (r.host == null || r.host.isBlank() || r.username == null || r.password == null || r.channel == null
            || r.channel.isBlank() || r.port < 1 || r.port > 65535 || r.database < 0
            || r.timeoutMillis < 250 || r.timeoutMillis > 30000)
            throw new IllegalArgumentException("Invalid Redis connection settings");
    }
}
