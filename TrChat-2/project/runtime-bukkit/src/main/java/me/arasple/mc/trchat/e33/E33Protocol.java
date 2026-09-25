package me.arasple.mc.trchat.e33;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Minecraft custom-payload wire formats used by the Fabric E33Chat bridge. */
public final class E33Protocol {
    private E33Protocol() {}

    public record Quote(String sender, String content, String target) {}
    public record Settings(boolean useTpa, boolean history, boolean debug,
                           List<String> chatTemplates, List<String> whisperTemplates,
                           boolean media, boolean autoClean) {}
    public record Upload(long id, int index, int chunks, int bytes, String type, byte[] data) {}
    public record HistoryEntry(UUID sender, String name, String content, long time,
                               boolean system, String replyContent, String replySender) {}
    public record BridgeChat(UUID messageId, UUID sender, String account, String displayName,
                              String origin, String body, String bodyJson, boolean privateMessage,
                              String recipient, String quoteSender, String quoteContent,
                              List<UUID> mentions, String nameplate, String displayJson,
                              String renderedJson) {}

    /** Opt-in v1 channel; the whole Bukkit plugin message stays below 32 KiB. */
    public static byte[] bridgeChat(BridgeChat chat) { return bridgeChat(chat, 2); }

    public static byte[] bridgeChatV1(BridgeChat chat) { return bridgeChat(chat, 1); }

    private static byte[] bridgeChat(BridgeChat chat, int version) {
        bounded(chat.account(), 64);
        bounded(chat.displayName(), 1024);
        bounded(chat.origin(), 128);
        bounded(chat.body(), 8192);
        bounded(chat.bodyJson(), 16384);
        bounded(chat.recipient(), 64);
        bounded(chat.quoteSender(), 1024);
        bounded(chat.quoteContent(), 4096);
        bounded(chat.nameplate(), 8192);
        bounded(chat.displayJson(), 8192);
        bounded(chat.renderedJson(), 16384);
        byte[] bytes = encode(out -> {
            varInt(out, version);
            string(out, chat.messageId().toString());
            string(out, chat.sender().toString());
            string(out, chat.account());
            string(out, chat.displayName());
            string(out, chat.origin());
            string(out, chat.body());
            string(out, chat.bodyJson());
            out.writeBoolean(chat.privateMessage());
            string(out, chat.recipient());
            string(out, chat.quoteSender());
            string(out, chat.quoteContent());
            if (chat.mentions().size() > 128) throw new IOException("Too many mentions");
            varInt(out, chat.mentions().size());
            for (UUID mention : chat.mentions()) string(out, mention.toString());
            string(out, chat.nameplate());
            if (version >= 2) {
                string(out, chat.displayJson());
                string(out, chat.renderedJson());
            }
        });
        if (bytes.length > 30_000) throw new IllegalArgumentException("E33 bridge chat exceeds 30 KiB");
        return bytes;
    }

    private static void bounded(String value, int maxChars) {
        if (value == null || value.length() > maxChars)
            throw new IllegalArgumentException("E33 bridge field exceeds " + maxChars + " characters");
    }

    public static BridgeChat bridgeChat(byte[] bytes) throws IOException {
        if (bytes.length > 30_000) throw new IOException("E33 bridge chat exceeds 30 KiB");
        DataInputStream in = input(bytes);
        int version = varInt(in);
        if (version != 1 && version != 2) throw new IOException("Unsupported E33 bridge version");
        UUID id = UUID.fromString(string(in));
        UUID sender = UUID.fromString(string(in));
        String account = string(in), display = string(in), origin = string(in);
        String body = string(in), json = string(in);
        boolean privateMessage = in.readBoolean();
        String recipient = string(in), quoteSender = string(in), quoteContent = string(in);
        int count = varInt(in);
        if (count < 0 || count > 128) throw new IOException("Invalid mention count");
        List<UUID> mentions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) mentions.add(UUID.fromString(string(in)));
        String nameplate = string(in);
        String displayJson = version >= 2 ? string(in) : "";
        String renderedJson = version >= 2 ? string(in) : "";
        if (in.available() != 0) throw new IOException("Trailing bridge chat data");
        return new BridgeChat(id, sender, account, display, origin, body, json,
            privateMessage, recipient, quoteSender, quoteContent, mentions, nameplate,
            displayJson, renderedJson);
    }

    public interface Writer { void write(DataOutputStream out) throws IOException; }

    public static byte[] encode(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            writer.write(new DataOutputStream(bytes));
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid E33 payload", ex);
        }
    }

    public static DataInputStream input(byte[] bytes) {
        if (bytes.length > 1_048_576) throw new IllegalArgumentException("E33 payload too large");
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }

    public static void varInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7f) != 0) {
            out.writeByte((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    public static int varInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int part = in.readUnsignedByte();
            if (shift == 28 && (part & 0xf0) != 0) throw new IOException("VarInt overflow");
            value |= (part & 0x7f) << shift;
            if ((part & 0x80) == 0) return value;
        }
        throw new IOException("VarInt too long");
    }

    public static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 131_068) throw new IOException("String too long");
        varInt(out, bytes.length);
        out.write(bytes);
    }

    public static String string(DataInputStream in) throws IOException {
        int length = varInt(in);
        if (length < 0 || length > 131_068 || length > in.available())
            throw new IOException("Invalid UTF-8 length");
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void strings(DataOutputStream out, List<String> values) throws IOException {
        if (values.size() > 128) throw new IOException("Too many strings");
        out.writeInt(values.size());
        for (String value : values) string(out, value);
    }

    private static List<String> strings(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 128) throw new IOException("Invalid string count");
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(string(in));
        return values;
    }

    public static Quote quote(byte[] bytes) throws IOException {
        DataInputStream in = input(bytes);
        Quote value = new Quote(string(in), string(in), string(in));
        if (in.available() != 0) throw new IOException("Trailing quote data");
        return value;
    }

    public static Settings settings(byte[] bytes) throws IOException {
        DataInputStream in = input(bytes);
        Settings value = new Settings(in.readBoolean(), in.readBoolean(), in.readBoolean(),
            strings(in), strings(in), in.readBoolean(), in.readBoolean());
        if (in.available() != 0) throw new IOException("Trailing settings data");
        return value;
    }

    /** Client saves use the 2.4.10 DTO layout; stored settings and older
     * clients retain the original layout above. TrChat has no E33 group
     * service, so its group flag is deliberately not persisted. */
    public static Settings clientSave(byte[] bytes) throws IOException {
        try {
            DataInputStream in = input(bytes);
            boolean useTpa = wireBoolean(in);
            boolean history = wireBoolean(in);
            boolean debug = wireBoolean(in);
            boolean media = wireBoolean(in);
            boolean autoClean = wireBoolean(in);
            wireBoolean(in); // EasyBot compatibility is client-side.
            wireBoolean(in); // E33 groups require a Fabric mod server.
            List<String> chat = strings(in);
            List<String> whisper = strings(in);
            if (in.available() != 0) throw new IOException("Trailing settings data");
            return new Settings(useTpa, history, debug, chat, whisper, media, autoClean);
        } catch (IOException | IllegalArgumentException ex) {
            return settings(bytes);
        }
    }

    private static boolean wireBoolean(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        if (value > 1) throw new IOException("Invalid boolean value");
        return value == 1;
    }

    public static byte[] config(Settings settings) {
        return encode(out -> {
            out.writeBoolean(settings.useTpa());
            strings(out, settings.chatTemplates());
            strings(out, settings.whisperTemplates());
            out.writeBoolean(settings.debug());
        });
    }

    public static byte[] screen(Settings settings) {
        return encode(out -> {
            out.writeBoolean(settings.useTpa());
            out.writeBoolean(settings.history());
            out.writeBoolean(settings.debug());
            strings(out, settings.chatTemplates());
            strings(out, settings.whisperTemplates());
            out.writeBoolean(settings.media());
            out.writeBoolean(settings.autoClean());
        });
    }

    public static byte[] meta(UUID sender, String name, String hash,
                              String quoteSender, String quoteContent, List<String> mentions) {
        return encode(out -> {
            string(out, sender.toString());
            string(out, name);
            string(out, hash);
            string(out, quoteSender);
            string(out, quoteContent);
            if (mentions.size() > 128) throw new IOException("Too many mentions");
            varInt(out, mentions.size());
            for (String mention : mentions) string(out, mention);
        });
    }

    public static byte[] history(List<HistoryEntry> entries) {
        return encode(out -> {
            if (entries.size() > 50) throw new IOException("Too many history entries");
            varInt(out, entries.size());
            for (HistoryEntry entry : entries) {
                string(out, entry.sender().toString());
                string(out, entry.name());
                string(out, entry.content());
                out.writeLong(entry.time());
                out.writeBoolean(entry.system());
                string(out, entry.replyContent());
                string(out, entry.replySender());
            }
        });
    }

    public static List<HistoryEntry> history(byte[] bytes) throws IOException {
        DataInputStream in = input(bytes);
        int count = varInt(in);
        if (count < 0 || count > 50) throw new IOException("Invalid history count");
        List<HistoryEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new HistoryEntry(UUID.fromString(string(in)), string(in), string(in),
                in.readLong(), in.readBoolean(), string(in), string(in)));
        }
        if (in.available() != 0) throw new IOException("Trailing history data");
        return entries;
    }

    public static Upload upload(byte[] bytes) throws IOException {
        DataInputStream in = input(bytes);
        long id = in.readLong();
        int index = in.readInt();
        int chunks = in.readInt();
        int total = in.readInt();
        String type = string(in);
        byte[] data = byteArray(in, 32_768);
        if (in.available() != 0 || total < 0 || total > 8 * 1024 * 1024
            || type.length() > 64 || data.length > 30_000
            || chunks < 1 || chunks > 512 || index < 0 || index >= chunks)
            throw new IOException("Invalid media upload");
        return new Upload(id, index, chunks, total, type, data);
    }

    public static String mediaRequest(byte[] bytes) throws IOException {
        DataInputStream in = input(bytes);
        String id = string(in);
        if (in.available() != 0 || id.length() > 128) throw new IOException("Invalid media ID");
        return id;
    }

    public static byte[] ack(long uploadId, String id, String error) {
        return encode(out -> { out.writeLong(uploadId); string(out, id); string(out, error); });
    }

    public static byte[] response(String id, int index, int chunks, byte[] data) {
        return encode(out -> {
            string(out, id);
            out.writeInt(index);
            out.writeInt(chunks);
            byteArray(out, data);
        });
    }

    public static void byteArray(DataOutputStream out, byte[] bytes) throws IOException {
        varInt(out, bytes.length);
        out.write(bytes);
    }

    public static byte[] byteArray(DataInputStream in, int max) throws IOException {
        int length = varInt(in);
        if (length < 0 || length > max || length > in.available()) throw new IOException("Invalid byte array");
        return in.readNBytes(length);
    }
}
