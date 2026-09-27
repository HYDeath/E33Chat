package me.arasple.mc.trchat.neoforge;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(TrChatMod.ID)
public final class TrChatMod {
    public static final String ID = "trchat";
    public static final Logger LOGGER = LogUtils.getLogger();
    private ChatService service;

    public TrChatMod(IEventBus bus) {
        bus.addListener(E33Network::register);
        NeoForge.EVENT_BUS.addListener(this::start);
        NeoForge.EVENT_BUS.addListener(this::stop);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::chat);
        NeoForge.EVENT_BUS.addListener(this::join);
        NeoForge.EVENT_BUS.addListener(this::quit);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::commands);
        NeoForge.EVENT_BUS.addListener(ChatPermissions::register);
    }
    private void start(ServerStartedEvent event) {
        try {
            service = new ChatService(event.getServer(), FMLPaths.CONFIGDIR.get().resolve("trchat-neoforge.json"));
            E33Network.service = service;
            LOGGER.info("TrChat NeoForge ready");
        } catch (Exception ex) {
            LOGGER.error("TrChat failed to load configuration/state; vanilla chat remains active", ex);
        }
    }
    private void stop(ServerStoppingEvent event) {
        E33Network.service = null;
        if (service != null) { service.close(); service = null; }
    }
    private void chat(ServerChatEvent event) {
        if (service == null) return;
        event.setCanceled(true);
        service.publicChat(event.getPlayer(), event.getRawText());
    }
    private void join(PlayerEvent.PlayerLoggedInEvent event) {
        if (service != null && event.getEntity() instanceof ServerPlayer player) service.join(player);
    }
    private void quit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (service != null && event.getEntity() instanceof ServerPlayer player) service.quit(player);
    }
    private void tick(ServerTickEvent.Post event) { if (service != null) service.tick(); }
    private void commands(RegisterCommandsEvent event) {
        ChatCommands.register(event.getDispatcher(), () -> service);
    }
}
