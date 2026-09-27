package me.arasple.mc.trchat.neoforge;

import com.mojang.authlib.GameProfile;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatServiceTest {
    @TempDir Path temporary;
    MinecraftServer server;
    ServerPlayer sender, receiver, outsider;
    ChatService chat;
    MockedStatic<ChatPermissions> permissions;
    MockedStatic<E33Network> network;
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @BeforeEach void setup() throws Exception {
        permissions = mockStatic(ChatPermissions.class);
        permissions.when(() -> ChatPermissions.has(any(), any())).thenReturn(true);
        permissions.when(() -> ChatPermissions.has(any(), eq(ChatPermissions.BYPASS))).thenReturn(false);
        permissions.when(() -> ChatPermissions.listen(any(), anyString())).thenReturn(true);
        network = mockStatic(E33Network.class);
        server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        when(server.getWorldPath(any())).thenReturn(temporary.resolve("world"));
        when(server.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        sender = player("Steve"); receiver = player("Alex"); outsider = player("Bob");
        when(players.getPlayers()).thenReturn(List.of(sender, receiver, outsider));
        when(players.getPlayer(any())).thenAnswer(call -> List.of(sender, receiver, outsider).stream().filter(p -> p.getUUID().equals(call.getArgument(0))).findFirst().orElse(null));
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(server).execute(any(Runnable.class));
        Path configPath = temporary.resolve("config/chat.json");
        ChatConfig config = ChatConfig.load(configPath); config.cooldownMillis = 0; config.blockRepeatedMessages = false;
        Files.writeString(configPath, ChatConfig.JSON.toJson(config));
        chat = new ChatService(server, configPath);
    }
    ServerPlayer player(String name) {
        ServerPlayer player = mock(ServerPlayer.class); UUID id = UUID.randomUUID();
        when(player.getUUID()).thenReturn(id);
        when(player.getGameProfile()).thenReturn(new GameProfile(id, name));
        when(player.getDisplayName()).thenReturn(Component.literal(name));
        when(player.getMainHandItem()).thenReturn(ItemStack.EMPTY);
        return player;
    }
    @AfterEach void cleanup() { if (chat != null) chat.close(); if (network != null) network.close(); if (permissions != null) permissions.close(); }
    @Test void publicChatHasOneDeliveryAndIgnoreSuppressesIt() {
        chat.ignore(receiver, "Steve"); clearInvocations(sender, receiver, outsider);
        chat.globalChat(sender, "hello");
        verify(sender, times(1)).sendSystemMessage(argThat(c -> c.getString().contains("hello")));
        verify(outsider, times(1)).sendSystemMessage(argThat(c -> c.getString().contains("hello")));
        verify(receiver, never()).sendSystemMessage(any(Component.class));
    }
    @Test void privateMessageNeverReachesPublicAudienceAndReplyWorks() {
        chat.privateChat(sender, "Alex", "secret-1");
        verify(receiver).sendSystemMessage(argThat(c -> c.getString().contains("secret-1")));
        verify(sender).sendSystemMessage(argThat(c -> c.getString().contains("secret-1")));
        verify(outsider, never()).sendSystemMessage(any(Component.class));
        clearInvocations(sender, receiver);
        chat.reply(receiver, "secret-2");
        verify(sender).sendSystemMessage(argThat(c -> c.getString().contains("secret-2")));
        verify(outsider, never()).sendSystemMessage(any(Component.class));
    }
    @Test void blockedPrivateMessageDoesNotProduceSuccessEchoOrReplyTarget() {
        chat.ignore(receiver, "Steve"); clearInvocations(sender, receiver, outsider);
        chat.privateChat(sender, "Alex", "secret");
        verify(receiver, never()).sendSystemMessage(any(Component.class));
        verify(sender).sendSystemMessage(argThat(c -> c.getString().contains("不接收私聊")));
        verify(sender, never()).sendSystemMessage(argThat(c -> c.getString().contains("secret")));
    }
    @Test void groupChatOnlyReachesJoinedMembersAndStaysOutOfHistory() {
        chat.groupJoin(sender, "team"); chat.groupJoin(receiver, "team"); clearInvocations(sender, receiver, outsider);
        chat.publicChat(sender, "team-secret");
        verify(sender).sendSystemMessage(argThat(c -> c.getString().contains("team-secret")));
        verify(receiver).sendSystemMessage(argThat(c -> c.getString().contains("team-secret")));
        verify(outsider, never()).sendSystemMessage(any(Component.class));
        clearInvocations(outsider); chat.showHistory(outsider);
        verify(outsider, never()).sendSystemMessage(argThat(c -> c.getString().contains("team-secret")));
    }
    @Test void itemPlaceholderCarriesHoverInsteadOfPlainTextOnly() {
        when(sender.getMainHandItem()).thenReturn(new ItemStack(Items.DIAMOND, 2));
        chat.globalChat(sender, "看看 [item]");
        var captured = org.mockito.ArgumentCaptor.forClass(Component.class);
        verify(receiver).sendSystemMessage(captured.capture());
        String json = Component.Serializer.toJson(captured.getValue(), server.registryAccess());
        assertTrue(json.contains("show_item")); assertTrue(json.contains("minecraft:diamond"));
    }
    @Test void deniedChatPermissionStopsDelivery() {
        permissions.when(() -> ChatPermissions.has(sender, ChatPermissions.CHAT)).thenReturn(false);
        chat.globalChat(sender, "denied");
        verify(receiver, never()).sendSystemMessage(any(Component.class));
        verify(sender).sendSystemMessage(argThat(c -> c.getString().contains("没有聊天权限")));
    }
    @Test void bukkitRedisPrivatePacketOnlyReachesExactRecipient() throws Exception {
        chat.remote(WireMessage.decode(WireMessage.encode("paper-node", "ForwardMessage", "SendPrivateRaw",
            "Alex", "RemoteSteve", "{\"text\":\"remote-secret\"}", "remote-secret", "", "", "pm-id")));
        verify(receiver).sendSystemMessage(argThat(c -> c.getString().equals("remote-secret")));
        verify(sender, never()).sendSystemMessage(any(Component.class));
        verify(outsider, never()).sendSystemMessage(any(Component.class));
        clearInvocations(receiver);
        chat.remote(WireMessage.decode(WireMessage.encode("paper-node", "ForwardMessage", "SendPrivateRaw",
            "Alex", "RemoteSteve", "{\"text\":\"remote-secret\"}", "remote-secret", "", "", "pm-id")));
        verify(receiver, never()).sendSystemMessage(any(Component.class));
    }
    @Test void bukkitPublicPacketHonorsPortsIgnoreAndDuplicateEnvelope() throws Exception {
        chat.ignore(receiver, "RemoteSteve"); clearInvocations(sender, receiver, outsider);
        var packet = WireMessage.decode(WireMessage.encode("paper-node", "BroadcastRaw", UUID.randomUUID().toString(),
            "{\"text\":\"remote-public\"}", "", "true", "", "remote-public", "RemoteSteve", "", ""));
        chat.remote(packet); chat.remote(packet);
        verify(sender, times(1)).sendSystemMessage(argThat(c -> c.getString().equals("remote-public")));
        verify(receiver, never()).sendSystemMessage(any(Component.class));
        clearInvocations(sender, receiver, outsider);
        chat.remote(WireMessage.decode(WireMessage.encode("paper-node", "BroadcastRaw", UUID.randomUUID().toString(),
            "{\"text\":\"different-port\"}", "", "true", "25599", "different-port", "RemoteSteve", "", "")));
        verify(sender, never()).sendSystemMessage(any(Component.class));
    }
    @Test void globalMuteFromPaperAlsoStopsPrivateAndGroupMessages() throws Exception {
        chat.groupJoin(sender, "team"); clearInvocations(sender, receiver, outsider);
        chat.remote(WireMessage.decode("{\"data\":[\"GlobalMute\",\"on\"]}"));
        chat.privateChat(sender, "Alex", "private-denied");
        chat.groupChat(sender, "team", "group-denied");
        verify(receiver, never()).sendSystemMessage(any(Component.class));
        verify(sender, times(2)).sendSystemMessage(argThat(c -> c.getString().contains("当前已禁言")));
        clearInvocations(sender, receiver);
        chat.remote(WireMessage.decode("{\"data\":[\"GlobalMute\",\"off\"]}"));
        chat.privateChat(sender, "Alex", "allowed");
        verify(receiver).sendSystemMessage(argThat(c -> c.getString().contains("allowed")));
    }
}
