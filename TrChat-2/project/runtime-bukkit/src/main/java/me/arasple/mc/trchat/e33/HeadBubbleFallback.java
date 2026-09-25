package me.arasple.mc.trchat.e33;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** One short-lived world-space bubble per nearby speaker when CN cannot own it. */
final class HeadBubbleFallback {
    private static final Map<UUID, Bubble> ACTIVE = new ConcurrentHashMap<>();
    private record Bubble(TextDisplay display, ScheduledTask follow) {}

    private HeadBubbleFallback() {}

    static void show(Plugin plugin, Player speaker, String content) {
        if (content == null || content.isBlank()) return;
        String visible = content.length() > 160 ? content.substring(0, 160) + "…" : content;
        speaker.getScheduler().run(plugin, task -> {
            clear(speaker.getUniqueId(), plugin);
            if (!speaker.isOnline()) return;
            TextDisplay display = speaker.getWorld().spawn(
                speaker.getLocation().add(0, speaker.getHeight() + 0.4, 0), TextDisplay.class, entity -> {
                    entity.text(Component.text(visible));
                    entity.setBillboard(Display.Billboard.CENTER);
                    entity.setShadowed(true);
                    entity.setLineWidth(180);
                    entity.setPersistent(false);
                });
            org.bukkit.World spawnWorld = speaker.getWorld();
            ScheduledTask follow = speaker.getScheduler().runAtFixedRate(plugin, tick -> {
                if (!speaker.isOnline() || !speaker.getWorld().equals(spawnWorld)) {
                    clear(speaker.getUniqueId(), plugin);
                    return;
                }
                display.teleportAsync(speaker.getLocation().add(0, speaker.getHeight() + 0.4, 0));
            }, () -> clear(speaker.getUniqueId(), plugin), 2L, 2L);
            Bubble bubble = new Bubble(display, follow);
            ACTIVE.put(speaker.getUniqueId(), bubble);
            display.getScheduler().runDelayed(plugin, expired -> {
                if (ACTIVE.remove(speaker.getUniqueId(), bubble)) {
                    follow.cancel();
                    display.remove();
                }
            }, null, 80L);
        }, null);
    }

    static void clear(UUID speaker, Plugin plugin) {
        Bubble old = ACTIVE.remove(speaker);
        if (old == null) return;
        old.follow().cancel();
        old.display().getScheduler().run(plugin, task -> old.display().remove(), null);
    }

    static void clearAll(Plugin plugin) {
        for (UUID speaker : ACTIVE.keySet()) clear(speaker, plugin);
    }
}
