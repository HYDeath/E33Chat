package cn.xingchen.e33.protocol;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/** Bounded Bukkit/Fabric wire contract. No client-supplied recipients exist. */
public final class XcProtocol {
    public static final int VERSION = 1;
    public static final int MAX_PACKET = 30_000;
    public static final String HELLO = "e33chat:xc_hello";
    public static final String DOWNSTREAM = "e33chat:xc_downstream";
    public static final Set<String> CHANNELS = Set.of("world", "guild", "party", "pm");
    public sealed interface Packet permits State, Message, Clear {}
    public record State(String active, boolean guild, boolean party, boolean prompt, String recipient) implements Packet {
        public State { channel(active); recipient = recipient == null ? "" : recipient; }
    }
    public record Message(UUID id, UUID sender, String account, String channel, String displayJson,
                          String bodyJson, String renderedJson, String recipient, long time) implements Packet {
        public Message { XcProtocol.channel(channel); }
    }
    public record Clear() implements Packet {}
    private XcProtocol() {}
    public static byte[] hello() { return new byte[]{VERSION}; }
    public static boolean validHello(byte[] bytes) {
        return bytes != null && bytes.length == 1 && bytes[0] == VERSION;
    }
    private static void channel(String id) {
        if (!CHANNELS.contains(id)) throw new IllegalArgumentException("Unknown channel");
    }
    public static byte[] encode(Packet packet) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            switch (packet) {
                case State s -> {
                    out.writeByte(1); string(out, s.active, 16);
                    out.writeBoolean(s.guild); out.writeBoolean(s.party); out.writeBoolean(s.prompt);
                    string(out, s.recipient, 64);
                }
                case Message m -> {
                    out.writeByte(2); uuid(out, m.id); uuid(out, m.sender);
                    string(out, m.account, 64); string(out, m.channel, 16);
                    string(out, m.displayJson, 4096); string(out, m.bodyJson, 12288);
                    string(out, m.renderedJson, 12288); string(out, m.recipient, 64); out.writeLong(m.time);
                }
                case Clear ignored -> out.writeByte(3);
            }
            out.flush();
            if (bytes.size() > MAX_PACKET) throw new IllegalArgumentException("Packet too large");
            return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException("Cannot encode packet", e); }
    }
    public static Packet decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_PACKET) throw new IllegalArgumentException("Packet too large");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readUnsignedByte() != VERSION) throw new IllegalArgumentException("Unsupported protocol");
            Packet packet = switch (in.readUnsignedByte()) {
                case 1 -> new State(string(in, 16), in.readBoolean(), in.readBoolean(), in.readBoolean(), string(in, 64));
                case 2 -> new Message(uuid(in), uuid(in), string(in, 64), string(in, 16),
                        string(in, 4096), string(in, 12288), string(in, 12288), string(in, 64), in.readLong());
                case 3 -> new Clear();
                default -> throw new IllegalArgumentException("Unknown packet kind");
            };
            if (in.available() != 0) throw new IllegalArgumentException("Trailing packet data");
            return packet;
        } catch (IOException e) { throw new IllegalArgumentException("Malformed packet", e); }
    }
    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
    }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void string(DataOutputStream out, String value, int max) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > max) throw new IllegalArgumentException("String too long");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String string(DataInputStream in, int max) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > max || size > in.available()) throw new IllegalArgumentException("Invalid string length");
        byte[] bytes = in.readNBytes(size);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new IllegalArgumentException("Invalid UTF-8", e); }
    }
}
