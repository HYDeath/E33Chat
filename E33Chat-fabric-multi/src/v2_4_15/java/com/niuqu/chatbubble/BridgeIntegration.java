package com.niuqu.chatbubble;

import com.niuqu.chatbubble.network.*;
import com.niuqu.chatbubble.store.ChatMessageStore;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Compatibility with the existing TrChat and Custom-Nameplates plugin bridge. */
public final class BridgeIntegration {
    private static boolean ready;
    private static int retryTicks;
    private static boolean catalogRequested;
    private static boolean emojiRequested;

    private BridgeIntegration() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(DownstreamPayload.ID, (payload, context) -> {
            try {
                CustomPacketPayload inner = payload.unwrap();
                if (inner instanceof MediaResponsePayload response) {
                    com.niuqu.chatbubble.image.MediaClient.handleResponse(response);
                } else if (inner instanceof MediaUploadAckPayload ack) {
                    com.niuqu.chatbubble.image.MediaClient.handleUploadAck(ack);
                } else {
                    context.client().execute(() -> handle(inner));
                }
            } catch (RuntimeException ex) {
                com.mojang.logging.LogUtils.getLogger().warn("[e33chat] Rejected malformed bridge payload", ex);
            }
        });
    }

    public static void onJoin() {
        ready = false;
        retryTicks = 0;
        catalogRequested = false;
        emojiRequested = false;
        ChatLinks.clearOutgoing();
        NameplateBubbleScreen.clearCatalog();
        com.niuqu.chatbubble.ui.ChatEmojiPanel.setCraftEmojis("");
        sendHello();
    }

    public static void onDisconnect() {
        ready = false;
        retryTicks = 0;
        catalogRequested = false;
        emojiRequested = false;
        ChatLinks.clearOutgoing();
        NameplateBubbleScreen.clearCatalog();
        com.niuqu.chatbubble.ui.ChatEmojiPanel.setCraftEmojis("");
    }

    public static void tick() {
        if (!ready && ++retryTicks >= 40) {
            retryTicks = 0;
            sendHello();
        }
        if (!catalogRequested && ClientPlayNetworking.canSend(BubbleActionPayload.ID)) {
            catalogRequested = true;
            ClientPlayNetworking.send(new BubbleActionPayload(0, ""));
        }
        if (!emojiRequested && ClientPlayNetworking.canSend(CraftEmojiCatalogPayload.ID)) {
            emojiRequested = true;
            ClientPlayNetworking.send(new CraftEmojiCatalogPayload(""));
        }
    }

    private static void sendHello() {
        if (ClientPlayNetworking.canSend(BridgeHelloPayload.ID))
            ClientPlayNetworking.send(new BridgeHelloPayload(1));
    }

    private static void handle(CustomPacketPayload inner) {
        if (inner instanceof BridgeReadyPayload payload) {
            ready = payload.ready();
        } else if (inner instanceof BridgeChatV2Payload payload) {
            ready = true;
            ChatMessageStore.addBridgeMessage(payload);
        } else if (inner instanceof BridgeChatPayload payload) {
            ready = true;
            ChatMessageStore.addBridgeMessage(payload);
        } else if (inner instanceof BubbleCatalogPayload payload) {
            NameplateBubbleScreen.acceptCatalog(payload);
        } else if (inner instanceof CraftEmojiCatalogPayload payload) {
            com.niuqu.chatbubble.ui.ChatEmojiPanel.setCraftEmojis(payload.encoded());
        } else if (inner instanceof ChatMetaPayload payload) {
            ChatMessageStore.applyChatMeta(payload.senderUUID(), payload.senderName(), payload.messageHash(),
                payload.quoteSender(), payload.quoteContent(), payload.mentionTargets());
        } else if (inner instanceof HistoryPayload payload) {
            ChatMessageStore.addHistoryMessages(payload.entries());
        } else if (inner instanceof ServerConfigScreenPayload payload) {
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            client.setScreen(new com.niuqu.chatbubble.config.ServerConfigScreen(
                client.currentScreen, payload.useTpa(), payload.historyEnabled(),
                payload.templateDebug(), payload.mediaEnabled(), payload.mediaAutoClean(),
                payload.easyBotCompat(), payload.groupsEnabled(),
                payload.chatTemplates(), payload.whisperTemplates()));
        } else if (inner instanceof ConfigSyncPayload payload) {
            ConfigSyncPayload.handle(payload);
        } else if (inner instanceof ConfigSyncV2Payload payload) {
            ConfigSyncV2Payload.handle(payload);
        } else if (inner instanceof MediaCapPayload payload) {
            MediaCapPayload.handle(payload);
        }
    }
}
