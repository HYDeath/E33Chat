package me.arasple.mc.trchat.neoforge;

import com.mojang.authlib.GameProfile;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.ChatFormatting;
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
    @Test void noNicknameAndBlankDisplayUseLoginNameWithoutExternalVariables() {
        when(sender.getDisplayName()).thenReturn(Component.literal("  "));
        when(receiver.getDisplayName()).thenReturn(null);
        assertEquals("Steve", PlayerNames.plain(sender));
        assertEquals("Alex", PlayerNames.plain(receiver));
        assertEquals("Steve", chat.players().stream().filter(p -> p.uuid().equals(sender.getUUID())).findFirst().orElseThrow().display());
        chat.globalChat(sender, "你好");
        verify(receiver).sendSystemMessage(argThat(c -> c.getString().equals("[模组服] Steve: 你好")));
        clearInvocations(sender, receiver);
        chat.privateChat(sender, "Alex", "私信");
        verify(receiver).sendSystemMessage(argThat(c -> c.getString().equals("[模组服] [Steve ➥ 我] 私信")));
        verify(sender).sendSystemMessage(argThat(c -> c.getString().equals("[我 ➦ Alex] 私信")));
    }
    private static boolean hasStyledName(Component component, String name, ChatFormatting color) {
        return component.toFlatList().stream().anyMatch(part -> part.getString().equals(name)
            && net.minecraft.network.chat.TextColor.fromLegacyFormat(color).equals(part.getStyle().getColor()));
    }
    @Test void nicknameStyleSurvivesPublicPrivateAndGroupDelivery() {
        Component nativeName = Component.literal("[勇者] 阿明").withStyle(ChatFormatting.GOLD);
        when(sender.getDisplayName()).thenReturn(nativeName);
        when(receiver.getDisplayName()).thenReturn(Component.literal("小张").withStyle(ChatFormatting.GREEN));
        chat.globalChat(sender, "公开消息");
        verify(receiver).sendSystemMessage(argThat(c -> hasStyledName(c, "[勇者] 阿明", ChatFormatting.GOLD)));
        clearInvocations(sender, receiver);
        chat.privateChat(sender, "小张", "私聊消息");
        verify(receiver).sendSystemMessage(argThat(c -> hasStyledName(c, "[勇者] 阿明", ChatFormatting.GOLD)));
        verify(sender).sendSystemMessage(argThat(c -> hasStyledName(c, "小张", ChatFormatting.GREEN)));
        clearInvocations(sender, receiver);
        chat.groupJoin(sender, "team"); chat.groupJoin(receiver, "team"); clearInvocations(sender, receiver);
        chat.groupChat(sender, "team", "群聊消息");
        verify(receiver).sendSystemMessage(argThat(c -> hasStyledName(c, "[勇者] 阿明", ChatFormatting.GOLD)));
        assertEquals("[勇者] 阿明", nativeName.getString());
        assertEquals(ChatFormatting.GOLD.getColor().intValue(), nativeName.getStyle().getColor().getValue());
    }
    @Test void asciiNicknameIgnoreStillBlocksTheCorrectAccount() {
        when(sender.getDisplayName()).thenReturn(Component.literal("Hero"));
        chat.ignore(receiver, "Hero"); clearInvocations(sender, receiver);
        chat.globalChat(sender, "已屏蔽的账号消息");
        verify(receiver, never()).sendSystemMessage(any(Component.class));
        chat.ignore(receiver, "Steve"); clearInvocations(sender, receiver);
        chat.globalChat(sender, "恢复显示");
        verify(receiver).sendSystemMessage(argThat(c -> c.getString().contains("Hero: 恢复显示")));
    }
    @Test void semanticBridgeSeparatesNicknameFromStableAccountIdentity() {
        when(sender.getDisplayName()).thenReturn(Component.literal("阿明").withStyle(ChatFormatting.AQUA));
        chat.globalChat(sender, "跨服昵称消息");
        network.verify(() -> E33Network.chat(eq(receiver), argThat(message -> message.account().equals("Steve")
            && message.displayName().equals("阿明") && message.origin().equals("模组服")
            && message.displayJson().contains("aqua"))));
    }
    @Test void paperMentionNotifiesOnlyRecipientAndIgnoreSuppressesNotification() throws Exception {
        var packet = WireMessage.decode(WireMessage.encode("paper-node", "BroadcastRaw", UUID.randomUUID().toString(),
            "{\"text\":\"hello @Alex\"}", "", "true", "", "hello @Alex", "RemoteSteve", "Alex", ""));
        chat.remote(packet);
        verify(receiver).sendSystemMessage(argThat(c -> c.getString().contains("提到了你")), eq(true));
        verify(sender, never()).sendSystemMessage(any(Component.class), eq(true));
        chat.ignore(receiver, "RemoteSteve"); clearInvocations(receiver);
        chat.remote(WireMessage.decode(WireMessage.encode("paper-node", packet.data())));
        verify(receiver, never()).sendSystemMessage(any(Component.class), anyBoolean());
    }
    @Test void mentionHighlightUsesCanonicalPrivateTargetAndPreservesUrlQuery() {
        when(receiver.getDisplayName()).thenReturn(Component.literal("小张"));
        chat.globalChat(sender, "@小张 @AlexExtra https://example.com/?a=1&b=2");
        var captured = org.mockito.ArgumentCaptor.forClass(Component.class);
        verify(receiver).sendSystemMessage(captured.capture());
        String json = Component.Serializer.toJson(captured.getValue(), server.registryAccess());
        assertTrue(json.contains("/msg Alex "));
        assertTrue(json.contains("?a=1&b=2"));
        network.verify(() -> E33Network.chat(eq(receiver), argThat(c -> c.mentions().equals(List.of(receiver.getUUID())))));
    }
    @Test void groupMentionsNotifyOnlyJoinedUnblockedMembers() {
        chat.groupJoin(sender, "team"); chat.groupJoin(receiver, "team"); clearInvocations(sender, receiver, outsider);
        chat.groupChat(sender, "team", "@Alex @Bob");
        verify(receiver).sendSystemMessage(any(Component.class), eq(true));
        verify(outsider, never()).sendSystemMessage(any(Component.class), anyBoolean());
        chat.ignore(receiver, "Steve"); clearInvocations(receiver);
        chat.groupChat(sender, "team", "@Alex");
        verify(receiver, never()).sendSystemMessage(any(Component.class), anyBoolean());
    }
    @Test void e33RecipientOwnsMentionNotificationWithoutDuplicateNativeDelivery() {
        network.when(() -> E33Network.chat(eq(receiver), any())).thenReturn(true);
        chat.globalChat(sender, "@Alex");
        verify(receiver, never()).sendSystemMessage(any(Component.class));
        verify(receiver, never()).sendSystemMessage(any(Component.class), anyBoolean());
    }
    @Test void twoServiceNodesDeliverPrivateReplyMentionsAndRejectionsOverTcp() throws Exception {
        chat.close();
        try (var stub = new RedisBridgeTest.RedisStub(0)) {
            var tasksA = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
            var tasksB = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
            doAnswer(c -> { tasksA.add(c.getArgument(0)); return null; }).when(server).execute(any(Runnable.class));
            when(server.getPlayerList().getPlayers()).thenReturn(List.of(sender));
            MinecraftServer serverB = mock(MinecraftServer.class);
            PlayerList listB = mock(PlayerList.class);
            when(serverB.getPlayerList()).thenReturn(listB);
            when(listB.getPlayers()).thenReturn(List.of(receiver, outsider));
            when(listB.getPlayer(any())).thenAnswer(c -> List.of(receiver, outsider).stream().filter(p -> p.getUUID().equals(c.getArgument(0))).findFirst().orElse(null));
            when(serverB.getWorldPath(any())).thenReturn(temporary.resolve("world-b"));
            var registries = server.registryAccess();
            when(serverB.registryAccess()).thenReturn(registries);
            doAnswer(c -> { tasksB.add(c.getArgument(0)); return null; }).when(serverB).execute(any(Runnable.class));
            var config = new ChatConfig(); config.redis = RedisBridgeTest.config(stub.listener.getLocalPort());
            config.cooldownMillis = 0; config.blockRepeatedMessages = false; config.directoryId = 25571;
            Path a = temporary.resolve("config/a.json"), b = temporary.resolve("config/b.json");
            Files.writeString(a, ChatConfig.JSON.toJson(config));
            config.directoryId = 25572; Files.writeString(b, ChatConfig.JSON.toJson(config));
            chat = new ChatService(server, a);
            try (var second = new ChatService(serverB, b)) {
                stub.values.put("e33chat:v1:reply:" + sender.getUUID(), "Alex");
                Runnable pump = () -> {
                    Runnable task; while ((task = tasksA.poll()) != null) task.run();
                    while ((task = tasksB.poll()) != null) task.run(); chat.tick(); second.tick();
                };
                RedisBridgeTest.await(() -> { pump.run(); return chat.names().contains("Alex") && second.names().contains("Steve"); });
                RedisBridgeTest.await(() -> { pump.run(); try { return Files.readString(temporary.resolve("world/trchat/state.json")).contains("Alex"); }
                    catch (java.io.IOException e) { return false; } });
                chat.join(sender); second.join(receiver); chat.quit(sender); second.quit(receiver);
                chat.join(sender); second.join(receiver);
                clearInvocations(sender, receiver, outsider);
                chat.reply(sender, "tcp-secret");
                verify(sender, never()).sendSystemMessage(argThat(c -> c.getString().contains("tcp-secret")));
                RedisBridgeTest.await(() -> { pump.run(); return mockingDetails(sender).getInvocations().stream()
                    .anyMatch(i -> i.getMethod().getName().equals("sendSystemMessage") && ((Component)i.getArgument(0)).getString().contains("tcp-secret")); });
                verify(receiver).sendSystemMessage(argThat(c -> c.getString().contains("tcp-secret") && c.toFlatList().stream()
                    .anyMatch(p -> p.getStyle().getColor() != null && p.getStyle().getColor().getValue() == 0x8AE7B7)));
                verify(sender).sendSystemMessage(argThat(c -> c.getString().contains("tcp-secret")));
                verify(outsider, never()).sendSystemMessage(any(Component.class));
                clearInvocations(sender, receiver);
                second.reply(receiver, "tcp-reply");
                RedisBridgeTest.await(() -> { pump.run(); return stub.values.containsKey("e33chat:v1:reply:" + receiver.getUUID()); });
                // The old reply key already exists; wait for delivery rather than accepting the stale key.
                RedisBridgeTest.await(() -> { pump.run(); return mockingDetails(sender).getInvocations().stream()
                    .anyMatch(i -> i.getMethod().getName().equals("sendSystemMessage") && ((Component)i.getArgument(0)).getString().contains("tcp-reply")); });
                verify(sender).sendSystemMessage(argThat(c -> c.getString().contains("tcp-reply")));
                second.ignore(receiver, "Steve"); clearInvocations(sender, receiver);
                chat.privateChat(sender, "Alex", "blocked-tcp");
                RedisBridgeTest.await(() -> { pump.run(); return mockingDetails(sender).getInvocations().stream()
                    .anyMatch(i -> i.getMethod().getName().equals("sendSystemMessage") && ((Component)i.getArgument(0)).getString().contains("不接收私聊")); });
                verify(receiver, never()).sendSystemMessage(any(Component.class));
                verify(sender, never()).sendSystemMessage(argThat(c -> c.getString().contains("blocked-tcp")));
                second.ignore(receiver, "Steve"); clearInvocations(sender, receiver, outsider);
                chat.globalChat(sender, "跨服 @aLeX!");
                RedisBridgeTest.await(() -> { pump.run(); return mockingDetails(receiver).getInvocations().stream()
                    .anyMatch(i -> i.getMethod().getName().equals("sendSystemMessage") && i.getArguments().length == 2 && Boolean.TRUE.equals(i.getArgument(1))); });
                verify(receiver).sendSystemMessage(argThat(c -> c.getString().contains("提到了你")), eq(true));
                verify(outsider, never()).sendSystemMessage(any(Component.class), eq(true));
                assertTrue(stub.received.stream().filter(c -> c.getFirst().equals("PUBLISH")).map(c -> WireMessage.decode(c.get(2)).data())
                    .anyMatch(d -> d[0].equals("BroadcastRaw") && d.length > 8 && d[8].equals("Alex")));
                assertFalse(stub.received.stream().filter(c -> c.getFirst().equals("PUBLISH"))
                    .map(c -> WireMessage.decode(c.get(2))).anyMatch(m -> Arrays.stream(m.data()).anyMatch(s -> s.contains("加入了") || s.contains("离开了"))));
            }
        }
    }
}
