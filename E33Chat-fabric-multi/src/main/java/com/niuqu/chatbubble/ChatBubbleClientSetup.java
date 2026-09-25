package com.niuqu.chatbubble;

import com.niuqu.chatbubble.config.ChatBubbleConfig;
import com.niuqu.chatbubble.config.ConfigManager;
import com.niuqu.chatbubble.image.ImageLoader;
import com.niuqu.chatbubble.network.ChatMetaPayload;
//#if MC >= 12005
import com.niuqu.chatbubble.network.BridgeChatPayload;
import com.niuqu.chatbubble.network.BridgeChatV2Payload;
import com.niuqu.chatbubble.network.BridgeHelloPayload;
import com.niuqu.chatbubble.network.BridgeReadyPayload;
import com.niuqu.chatbubble.network.CraftEmojiCatalogPayload;
//#if MC >= 26000
import com.niuqu.chatbubble.network.DownstreamPayload;
import com.niuqu.chatbubble.network.BubbleCatalogPayload;
import com.niuqu.chatbubble.network.MediaResponsePayload;
import com.niuqu.chatbubble.network.MediaUploadAckPayload;
import net.minecraft.network.packet.CustomPayload;
//#endif
//#endif
import com.niuqu.chatbubble.network.ConfigSyncPayload;
import com.niuqu.chatbubble.network.ConfigSyncV2Payload;
import com.niuqu.chatbubble.network.HistoryPayload;
import com.niuqu.chatbubble.network.MediaCapPayload;
import com.niuqu.chatbubble.network.ServerConfigScreenPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
//#if MC < 26000
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
//#else
//$$ import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
//#endif
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;

public class ChatBubbleClientSetup implements ClientModInitializer {
    private static ChatBubbleConfig config = ChatBubbleConfig.defaults();
    private static Path configPath;
    private static boolean leftWasDown;
    private static boolean wasInWorld;
    private static volatile boolean bridgeReady;
    private static int bridgeHelloTicks;
    private static boolean bridgeFirstChatLogged;
    private static boolean bridgeHelloSent;
    //#if MC >= 26000
    private static boolean bubbleCatalogRequested;
    //#endif

    public static boolean bridgeReady() { return bridgeReady; }

    public static ChatBubbleConfig config() { return config; }

    public static void saveConfig(ChatBubbleConfig newConfig) {
        config = newConfig;
        E33Log.info("[e33chat] Saving config | soundPublic=" + newConfig.soundPublic() + " | soundSystem=" + newConfig.soundSystem());
        ConfigManager.save(configPath, config);
    }

    @Override
    public void onInitializeClient() {
        configPath = MinecraftClient.getInstance().runDirectory.toPath().resolve("config/e33chat-client.json");
        // v2.3.x renamed the file from e33chat.json to e33chat-client.json (aligns with
        // Forge/Neo); migrate an existing old file so users keep their settings
        Path legacyPath = MinecraftClient.getInstance().runDirectory.toPath().resolve("config/e33chat.json");
        if (!Files.exists(configPath) && Files.exists(legacyPath)) {
            config = ConfigManager.load(legacyPath);
            ConfigManager.save(configPath, config);
            E33Log.info("[e33chat] Migrated config from config/e33chat.json to config/e33chat-client.json");
        } else {
            config = ConfigManager.load(configPath);
        }

        //#if MC >= 12005
        //#if MC >= 26000
        // One advertised receiver replaces twelve separate E33 channels.
        ClientPlayNetworking.registerGlobalReceiver(DownstreamPayload.ID, (payload, context) -> {
            final CustomPayload inner;
            try {
                inner = payload.unwrap();
            } catch (RuntimeException ex) {
                E33Log.warn("[e33chat] Rejected malformed downstream payload", ex);
                return;
            }
            if (inner instanceof MediaUploadAckPayload ack) {
                com.niuqu.chatbubble.image.MediaClient.handleUploadAck(ack);
            } else if (inner instanceof MediaResponsePayload response) {
                com.niuqu.chatbubble.image.MediaClient.handleResponse(response);
            } else {
                context.client().execute(() -> handleDownstream(inner));
            }
        });
        //#else
        ClientPlayNetworking.registerGlobalReceiver(ChatMetaPayload.ID, (payload, context) -> {
            context.client().execute(() -> ChatMessageStore.applyChatMeta(
                payload.senderUUID(), payload.senderName(), payload.messageHash(),
                payload.quoteSender(), payload.quoteContent(), payload.mentionTargets()));
        });
        ClientPlayNetworking.registerGlobalReceiver(BridgeReadyPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                if (payload.ready() && !bridgeReady)
                    E33Log.info("[e33chat] TrChat semantic bridge ready");
                bridgeReady = payload.ready();
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(BridgeChatPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                bridgeReady = true;
                if (!bridgeFirstChatLogged) {
                    bridgeFirstChatLogged = true;
                    E33Log.info("[e33chat] First semantic chat packet received");
                }
                ChatMessageStore.addBridgeMessage(payload);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(BridgeChatV2Payload.ID, (payload, context) -> {
            context.client().execute(() -> {
                bridgeReady = true;
                ChatMessageStore.addBridgeMessage(payload);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(CraftEmojiCatalogPayload.ID, (payload, context) -> {
            context.client().execute(() -> ChatEmojiPanel.setCraftEmojis(payload.encoded()));
        });
        ClientPlayNetworking.registerGlobalReceiver(HistoryPayload.ID, (payload, context) -> {
            context.client().execute(() -> ChatMessageStore.addHistoryMessages(payload.entries()));
        });
        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> ConfigSyncPayload.handle(payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncV2Payload.ID, (payload, context) -> {
            context.client().execute(() -> ConfigSyncV2Payload.handle(payload));
        });
        // Server-config GUI: opened on the client only (server never loads the Screen)
        ClientPlayNetworking.registerGlobalReceiver(ServerConfigScreenPayload.ID, (payload, context) -> {
            context.client().execute(() -> MinecraftClient.getInstance().setScreen(new ServerConfigScreen(
                MinecraftClient.getInstance().currentScreen,
                payload.useTpa(), payload.historyEnabled(), payload.templateDebug(),
                payload.chatTemplates(), payload.whisperTemplates(), payload.mediaEnabled(), payload.mediaAutoClean())));
        });

        com.niuqu.chatbubble.image.MediaClient.registerReceivers();
        ClientPlayNetworking.registerGlobalReceiver(MediaCapPayload.ID, (payload, context) -> {
            context.client().execute(() -> MediaCapPayload.handle(payload));
        });
        //#endif
        //#endif

        // On disconnect: immediately set the volatile flag from the network thread.
        // This is thread-safe (volatile write) and ensures blurPanel() and all
        // render/tick paths see it on the very next frame — BEFORE mc.world becomes
        // null. We also disable ImageLoader entirely so no new downloads or texture
        // uploads start during the disconnect transition. ImageLoader is re-enabled
        // when the next world is entered (see tick handler below).
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            bridgeReady = false;
            ChatLinks.clearOutgoing();
            ChatMessageStore.clearPendingHudLines();
            ChatEmojiPanel.setCraftEmojis("");
            bridgeHelloTicks = 0;
            bridgeFirstChatLogged = false;
            bridgeHelloSent = false;
            //#if MC >= 26000
            bubbleCatalogRequested = false;
            client.execute(NameplateBubbleScreen::clearCatalog);
            //#endif
            BlurRenderer.disconnecting = true;
            ImageLoader.setEnabled(false);
        });
        //#if MC >= 12005
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            bridgeReady = false;
            ChatLinks.clearOutgoing();
            ChatMessageStore.clearPendingHudLines();
            bridgeHelloTicks = 0;
            bridgeFirstChatLogged = false;
            bridgeHelloSent = false;
            //#if MC >= 26000
            bubbleCatalogRequested = false;
            NameplateBubbleScreen.clearCatalog();
            //#endif
            //#if MC >= 26000
            var receivers = ClientPlayNetworking.getGlobalReceivers();
            long e33Receivers = receivers.stream()
                .filter(id -> "e33chat".equals(id.getNamespace())).count();
            E33Log.info("[e33chat] Advertised client channels: total=" + receivers.size()
                + ", e33chat=" + e33Receivers);
            //#endif
            boolean channelAvailable = ClientPlayNetworking.canSend(BridgeHelloPayload.ID);
            E33Log.info("[e33chat] TrChat bridge channel at join=" + channelAvailable);
            if (channelAvailable) {
                ClientPlayNetworking.send(new BridgeHelloPayload(1));
                bridgeHelloSent = true;
            }
        });
        //#endif

        //#if MC >= 26000
        //$$ HudElementRegistry.addLast(Identifier.of("e33chat", "overlay"), (g, tick) -> {
        //$$     if (config.enabled() && !BlurRenderer.isDisconnecting())
        //$$         ChatBubbleHudOverlay.render(g);
        //$$ });
        //#else
        //#if MC >= 12000
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (!config.enabled()) return;
            if (BlurRenderer.isDisconnecting()) return;
            ChatBubbleHudOverlay.render(drawContext);
        });
        //#else
        //$$ HudRenderCallback.EVENT.register((matrices, tickDelta) -> {
        //$$     if (!config.enabled()) return;
        //$$     ChatBubbleHudOverlay.render(new DrawContext(matrices));
        //$$ });
        //#endif
        //#endif

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // World state transitions must be checked even during disconnect,
            // otherwise the "entered new world" branch never runs and the
            // disconnecting flag + ImageLoader disabled state would stick forever.
            boolean inWorld = client.world != null && client.player != null;
            //#if MC >= 26000
            //$$ if (inWorld && config.enabled())
            //$$     com.niuqu.chatbubble.chat.notification.MentionNotificationBanner.INSTANCE.tick();
            //#endif
            if (wasInWorld && !inWorld) {
                BlurRenderer.disconnecting = true;
                ImageLoader.setEnabled(false);
                if (client.currentScreen instanceof ChatBubbleScreen) {
                    client.setScreen(null);
                }
            }
            if (!wasInWorld && inWorld) {
                BlurRenderer.disconnecting = false;
                ImageLoader.setEnabled(true);
            }
            wasInWorld = inWorld;
            if (inWorld && client.currentScreen == null && !BlurRenderer.isDisconnecting())
                ChatMessageStore.flushPendingHudLines();

            //#if MC >= 12005
            // Bukkit may advertise its plugin channels after the Fabric JOIN event.
            // Retry until the server acknowledges, but never flood an unmodded server.
            if (inWorld && !bridgeReady && ++bridgeHelloTicks >= 40) {
                bridgeHelloTicks = 0;
                if (ClientPlayNetworking.canSend(BridgeHelloPayload.ID)) {
                    if (!bridgeHelloSent)
                        E33Log.info("[e33chat] TrChat bridge channel advertised after join");
                    ClientPlayNetworking.send(new BridgeHelloPayload(1));
                    bridgeHelloSent = true;
                }
            }
            //#if MC >= 26000
            // Load the equipped resource-pack frame even when the catalogue GUI
            // has not been opened during this session.
            if (inWorld && !bubbleCatalogRequested
                && ClientPlayNetworking.canSend(com.niuqu.chatbubble.network.BubbleActionPayload.ID)) {
                ClientPlayNetworking.send(new com.niuqu.chatbubble.network.BubbleActionPayload(0, ""));
                bubbleCatalogRequested = true;
            }
            //#endif
            //#endif

            // Short-circuit ALL remaining e33chat tick logic the moment disconnect
            // begins. The BlurRenderer.disconnecting flag is set from the network
            // thread the instant DISCONNECT fires — it is visible on the render
            // thread on the very next tick. We skip ImageLoader, history saves,
            // everything — any work that could interact with the tearing-down
            // world or GL state is deferred until the next world.
            if (BlurRenderer.isDisconnecting()) return;

            ImageLoader.tick();

            // 纹理全部走 drawTexture(Identifier) 懒加载（getTexture 自动 new ResourceTexture），F3+T 重载后自动重读资源包新 PNG
            if (!config.enabled()) return;

            String key;
            if (client.world == null || client.player == null) {
                key = null;
            } else if (client.getServer() != null) {
                key = "SP:" + client.getServer().getSaveProperties().getLevelName();
            } else if (client.getCurrentServerEntry() != null) {
                key = "MP:" + client.getCurrentServerEntry().name;
            } else {
                key = "world";
            }
            ChatMessageStore.setCurrentWorld(key);
            ChatMessageStore.maybeAutoSave();

            if (client.currentScreen == null) {
                //#if MC >= 260300
                //$$ boolean leftDown = client.mouse.isLeftPressed();
                //#else
                boolean leftDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                    client.getWindow().getHandle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_1) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                //#endif
                if (leftDown && !leftWasDown) {
                    double mx = client.mouse.getX() * (double)client.getWindow().getScaledWidth() / (double)client.getWindow().getWidth();
                    double my = client.mouse.getY() * (double)client.getWindow().getScaledHeight() / (double)client.getWindow().getHeight();
                    if (ChatBubbleHudOverlay.isMouseOverIcon(mx, my)) {
                        client.setScreen(new ChatBubbleScreen(""));
                    }
                }
                leftWasDown = leftDown;
            } else {
                leftWasDown = false;
            }
        });

        //#if MC < 26000
        //#if MC >= 12000
        ScreenEvents.BEFORE_INIT.register((client, screen, width, height) ->
            ScreenEvents.afterRender(screen).register((scr, g, mouseX, mouseY, delta) -> {
                if (config.enabled() && !BlurRenderer.isDisconnecting())
                    ChatBubbleHudOverlay.renderBannerForScreen(g);
            })
        );
        //#else
        //$$ ScreenEvents.BEFORE_INIT.register((client, screen, width, height) ->
        //$$     ScreenEvents.afterRender(screen).register((scr, matrices, mouseX, mouseY, delta) -> {
        //$$         if (config.enabled()) ChatBubbleHudOverlay.renderBannerForScreen(new DrawContext(matrices));
        //$$     })
        //$$ );
        //#endif
        //#endif

        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
            new SimpleSynchronousResourceReloadListener() {
                @Override
                public Identifier getFabricId() {
                    //#if MC >= 12000
                    return Identifier.of(ChatBubbleMod.MOD_ID, "shader_reload");
                    //#else
                    //$$ return new Identifier(ChatBubbleMod.MOD_ID, "shader_reload");
                    //#endif
                }
                @Override
                //#if MC >= 26000
                //$$ public void onResourceManagerReload(ResourceManager manager) {
                //#else
                public void reload(ResourceManager manager) {
                //#endif
                    RoundRectRenderer.resetShader();
                }
            }
        );
    }

    //#if MC >= 26000
    private static void handleDownstream(CustomPayload inner) {
        if (inner instanceof ChatMetaPayload meta) {
            ChatMessageStore.applyChatMeta(meta.senderUUID(), meta.senderName(), meta.messageHash(),
                meta.quoteSender(), meta.quoteContent(), meta.mentionTargets());
        } else if (inner instanceof BridgeReadyPayload ready) {
            if (ready.ready() && !bridgeReady)
                E33Log.info("[e33chat] TrChat semantic bridge ready");
            bridgeReady = ready.ready();
        } else if (inner instanceof BridgeChatPayload chat) {
            bridgeReady = true;
            if (!bridgeFirstChatLogged) {
                bridgeFirstChatLogged = true;
                E33Log.info("[e33chat] First semantic chat packet received");
            }
            ChatMessageStore.addBridgeMessage(chat);
        } else if (inner instanceof BridgeChatV2Payload chat) {
            bridgeReady = true;
            if (!bridgeFirstChatLogged) {
                bridgeFirstChatLogged = true;
                E33Log.info("[e33chat] First semantic chat v2 packet received");
            }
            ChatMessageStore.addBridgeMessage(chat);
        } else if (inner instanceof CraftEmojiCatalogPayload catalog) {
            ChatEmojiPanel.setCraftEmojis(catalog.encoded());
        } else if (inner instanceof BubbleCatalogPayload catalog) {
            NameplateBubbleScreen.acceptCatalog(catalog);
        } else if (inner instanceof HistoryPayload history) {
            ChatMessageStore.addHistoryMessages(history.entries());
        } else if (inner instanceof ConfigSyncPayload config) {
            ConfigSyncPayload.handle(config);
        } else if (inner instanceof ConfigSyncV2Payload config) {
            ConfigSyncV2Payload.handle(config);
        } else if (inner instanceof ServerConfigScreenPayload screen) {
            MinecraftClient.getInstance().setScreen(new ServerConfigScreen(
                MinecraftClient.getInstance().currentScreen,
                screen.useTpa(), screen.historyEnabled(), screen.templateDebug(),
                screen.chatTemplates(), screen.whisperTemplates(),
                screen.mediaEnabled(), screen.mediaAutoClean()));
        } else if (inner instanceof MediaCapPayload cap) {
            MediaCapPayload.handle(cap);
        }
    }
    //#endif
}
