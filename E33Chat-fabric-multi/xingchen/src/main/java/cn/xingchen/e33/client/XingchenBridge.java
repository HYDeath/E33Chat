package cn.xingchen.e33.client;

import cn.xingchen.e33.protocol.ChannelRouting;
import cn.xingchen.e33.protocol.XcProtocol;
import com.niuqu.chatbubble.chat.GroupChannelState;
import com.niuqu.chatbubble.store.ChatMessageStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class XingchenBridge implements ClientModInitializer {
    private static boolean ready, prompt;
    private static int ticks;
    private static String serverActive;
    private static String recipient = "";
    public static boolean ready() { return ready; }
    public static boolean prompt() { return prompt; }
    public static String recipient() { return recipient; }
    public static String outgoing(String text) {
        return ChannelRouting.outgoing(text, GroupChannelState.active(), prompt, recipient);
    }
    public static int overhead() { return prompt ? 0 : 3; }
    public record Hello() implements CustomPacketPayload {
        public static final Type<Hello> ID = new Type<>(Identifier.parse(XcProtocol.HELLO));
        public static final StreamCodec<FriendlyByteBuf, Hello> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeBytes(XcProtocol.hello()),
                buf -> {
                    if (buf.readableBytes() != 1 || buf.readUnsignedByte() != XcProtocol.VERSION)
                        throw new IllegalArgumentException("Invalid hello");
                    return new Hello();
                });
        @Override public Type<Hello> type() { return ID; }
    }
    public record Downstream(XcProtocol.Packet packet) implements CustomPacketPayload {
        public static final Type<Downstream> ID = new Type<>(Identifier.parse(XcProtocol.DOWNSTREAM));
        public static final StreamCodec<FriendlyByteBuf, Downstream> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeBytes(XcProtocol.encode(value.packet)), buf -> {
                    if (buf.readableBytes() > XcProtocol.MAX_PACKET) throw new IllegalArgumentException("Packet too large");
                    byte[] bytes = new byte[buf.readableBytes()]; buf.readBytes(bytes);
                    return new Downstream(XcProtocol.decode(bytes));
                });
        @Override public Type<Downstream> type() { return ID; }
    }
    @Override public void onInitializeClient() {
        PayloadTypeRegistry.serverboundPlay().register(Hello.ID, Hello.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Downstream.ID, Downstream.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Downstream.ID, (payload, context) ->
                context.client().execute(() -> accept(payload.packet)));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null && !ready && ++ticks >= 40) {
                ticks = 0;
                if (ClientPlayNetworking.canSend(Hello.ID)) ClientPlayNetworking.send(new Hello());
            }
        });
    }
    private static void reset() {
        ready = false; prompt = false; ticks = 0; serverActive = null; recipient = ""; GroupChannelState.reset();
    }
    private static void accept(XcProtocol.Packet packet) {
        switch (packet) {
            case XcProtocol.State state -> {
                ready = true; prompt = state.prompt(); recipient = state.recipient();
                GroupChannelState.enabled = true;
                var channels = new java.util.ArrayList<String>(2);
                if (state.guild()) channels.add("公会");
                if (state.party()) channels.add("队伍");
                GroupChannelState.applyDirectory(channels,
                        java.util.Collections.nCopies(channels.size(), 0), channels);
                String visibleActive = state.active();
                if ("guild".equals(visibleActive) && !state.guild()
                        || "party".equals(visibleActive) && !state.party()
                        || "pm".equals(visibleActive) && recipient.isBlank()) {
                    visibleActive = "world";
                }
                if (!visibleActive.equals(serverActive)) {
                    GroupChannelState.setActive(ChannelRouting.tab(visibleActive));
                    serverActive = visibleActive;
                }
            }
            case XcProtocol.Message message -> { if (ready) ChatMessageStore.addXingchenMessage(message); }
            case XcProtocol.Clear ignored -> {
                ChatMessageStore.clearXingchenView();
                var mc = Minecraft.getInstance();
                if (mc.gui != null && mc.gui.hud != null) mc.gui.hud.getChat().clearMessages(false);
            }
        }
    }
}
