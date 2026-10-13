package com.niuqu.chatbubble.render;

import com.mojang.authlib.GameProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.util.Identifier;

/**
 * Player-head skin resolution with a merged UUID + name cache.
 *
 * Extracted from ChatBubbleScreen / ChatSidebar during the 2.3.14 restructure:
 * the two components previously kept separate LRU caches for the same data.
 * The resolution fallback chain (online fresh read -> uuid cache -> name cache ->
 * SkinProvider -> default) is unchanged; only the cache store is shared.
 */
public final class SkinResolver {
    private SkinResolver() {}

    private static final int SKIN_CACHE_CAP = 256;
    private static final long SKIN_RETRY_MS = 5_000L;
    private static final UUID NIL_UUID = new UUID(0, 0);
    private static final Pattern SECTION_CODE = Pattern.compile("§.");
    private static final ExecutorService SKIN_LOOKUP = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "e33chat-skin");
        thread.setDaemon(true);
        return thread;
    });
    private static final java.util.Set<String> SKIN_PENDING = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> SKIN_RETRY = new ConcurrentHashMap<>();

    private static final Map<UUID, Identifier> skinCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Identifier> eldest) {
            return size() > SKIN_CACHE_CAP;
        }
    };

    // Name-keyed skin cache: an offline player seen in chat history keeps the
    // real head when the UUID lookup fails (cracked servers, uuid dropped in
    // old history files). Key is the §-stripped lowercase name.
    private static final Map<String, Identifier> skinNameCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Identifier> eldest) {
            return size() > SKIN_CACHE_CAP;
        }
    };

    private static String skinNameKey(String name) {
        if (name == null) return null;
        String key = SECTION_CODE.matcher(name).replaceAll("").trim().toLowerCase(java.util.Locale.ROOT);
        return key.isEmpty() ? null : key;
    }

    private static void rememberSkin(UUID uuid, String name, Identifier tex) {
        if (tex == null) return;
        synchronized (skinCache) {
            if (uuid != null && !uuid.equals(NIL_UUID)) skinCache.put(uuid, tex);
            String key = skinNameKey(name);
            if (key != null) skinNameCache.put(key, tex);
        }
    }

    public static Identifier getSkin(UUID uuid, String name) {
        MinecraftClient client = MinecraftClient.getInstance();
        // Online players: read PlayerListEntry fresh every frame — caching the first
        // result (default Steve/Alex while the async download is in progress) would
        // freeze the head forever even after the real skin loaded. CSL intercepts the
        // underlying lookup.
        if (client.getNetworkHandler() != null && uuid != null && !uuid.equals(NIL_UUID)) {
            PlayerListEntry info = client.getNetworkHandler().getPlayerListEntry(uuid);
            if (info != null) {
                Identifier tex = info.getSkinTextures().body().texturePath();
                rememberSkin(uuid, name, tex);
                return tex;
            }
        }
        synchronized (skinCache) {
            if (uuid != null && !uuid.equals(NIL_UUID)) {
                Identifier cached = skinCache.get(uuid);
                if (cached != null) return cached;
            }
            String nameKey = skinNameKey(name);
            if (nameKey != null) {
                Identifier cachedByName = skinNameCache.get(nameKey);
                if (cachedByName != null) return cachedByName;
            }
        }
        // Misses used to block the render thread on SkinManager. Queue the lookup
        // and keep the default head until it lands in the cache.
        queueSkin(uuid, name);
        return DefaultSkinHelper.getTexture();
    }

    private static void queueSkin(UUID uuid, String name) {
        if (name == null || name.isEmpty()) return;
        String token = (uuid == null ? "" : uuid.toString()) + "|" + name;
        Long retryAt = SKIN_RETRY.get(token);
        if (retryAt != null && retryAt > System.currentTimeMillis()) return;
        if (!SKIN_PENDING.add(token)) return;
        SKIN_LOOKUP.execute(() -> {
            try {
                MinecraftClient mc = MinecraftClient.getInstance();
                GameProfile profile = new GameProfile(
                    uuid != null && !uuid.equals(NIL_UUID) ? uuid : NIL_UUID, name);
                Identifier tex = mc.getSkinProvider().supplySkinTextures(profile, false).get().body().texturePath();
                rememberSkin(uuid, name, tex);
                SKIN_RETRY.remove(token);
            } catch (Exception ignored) {
                SKIN_RETRY.put(token, System.currentTimeMillis() + SKIN_RETRY_MS);
            } finally {
                SKIN_PENDING.remove(token);
            }
        });
    }
}
