package me.arasple.mc.trchat.e33;

/** Wire-compatible with the Fabric client's e33chat:downstream_v3 payload. */
public final class E33DownstreamProtocol {
    public static final String CHANNEL = "e33chat:downstream_v3";
    private static final int MAX_DATA_BYTES = 32_760;

    private E33DownstreamProtocol() {}

    public static byte[] wrap(String channel, byte[] payload) {
        int kind = switch (channel) {
            case "chat_meta" -> 1;
            case "bridge_ready_v1" -> 2;
            case "bridge_chat_v1" -> 3;
            case "bridge_chat_v2" -> 4;
            case "emoji_catalog" -> 5;
            case "chat_history" -> 6;
            case "config_sync" -> 7;
            case "config_sync_v2" -> 8;
            case "server_config_screen" -> 9;
            case "media_upload_ack" -> 10;
            case "media_response" -> 11;
            case "media_cap" -> 12;
            case "bubble_catalog" -> 13;
            default -> throw new IllegalArgumentException("Unknown E33 channel: " + channel);
        };
        if (payload == null || payload.length > MAX_DATA_BYTES)
            throw new IllegalArgumentException("Invalid E33 payload length");
        byte[] framed = new byte[payload.length + 1];
        framed[0] = (byte) kind;
        System.arraycopy(payload, 0, framed, 1, payload.length);
        return framed;
    }
}
