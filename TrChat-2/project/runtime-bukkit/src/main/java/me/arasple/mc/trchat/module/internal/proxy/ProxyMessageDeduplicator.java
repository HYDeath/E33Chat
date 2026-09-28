package me.arasple.mc.trchat.module.internal.proxy;

import me.arasple.mc.trchat.e33.E33Protocol;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Suppress repeated deliveries by identity, never by chat text or sender UUID. */
public final class ProxyMessageDeduplicator {
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();
    private final int capacity;
    private final long retentionMillis;
    private final LongSupplier clock;

    public ProxyMessageDeduplicator() {
        this(16_384, 60_000, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    ProxyMessageDeduplicator(int capacity, long retentionMillis, LongSupplier clock) {
        this.capacity = capacity;
        this.retentionMillis = retentionMillis;
        this.clock = clock;
    }

    public synchronized boolean accept(String id) {
        if (id == null || id.isBlank()) return true;
        long now = clock.getAsLong();
        var entries = seen.entrySet().iterator();
        while (entries.hasNext()) {
            if (now - entries.next().getValue() < retentionMillis) break;
            entries.remove();
        }
        if (seen.containsKey(id)) return false;
        while (seen.size() >= capacity) seen.remove(seen.keySet().iterator().next());
        seen.put(id, now);
        return true;
    }

    public boolean accept(String[] data) {
        int offset = 0;
        while (offset < data.length && "ForwardMessage".equals(data[offset])) offset++;
        if (offset == data.length) return true;
        if ("BroadcastRaw".equals(data[offset])) {
            String id = bridgeId(field(data, offset + 9));
            if (id.isEmpty()) id = field(data, offset + 10);
            return accept(id.isEmpty() ? null : "public:" + id);
        }
        if ("SendPrivateRaw".equals(data[offset])) {
            String id = field(data, offset + 7);
            if (id.isEmpty()) id = bridgeId(field(data, offset + 6));
            return accept(id.isEmpty() ? null : "private:"
                + field(data, offset + 1).toLowerCase(Locale.ROOT) + ":" + id);
        }
        return true;
    }

    private static String field(String[] data, int index) {
        return index < data.length && data[index] != null ? data[index] : "";
    }

    private static String bridgeId(String encoded) {
        if (encoded.isEmpty()) return "";
        try {
            return E33Protocol.bridgeChat(Base64.getDecoder().decode(encoded)).messageId().toString();
        } catch (Exception ignored) {
            // Invalid semantic payloads still use the ordinary component fallback.
            return "";
        }
    }
}
