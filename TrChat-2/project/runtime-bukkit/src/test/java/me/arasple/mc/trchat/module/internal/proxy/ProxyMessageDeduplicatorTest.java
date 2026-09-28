package me.arasple.mc.trchat.module.internal.proxy;

import me.arasple.mc.trchat.e33.E33Protocol;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ProxyMessageDeduplicatorTest {
    private static final UUID SENDER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static String bridge(UUID id, boolean privateMessage) {
        return Base64.getEncoder().encodeToString(E33Protocol.bridgeChat(new E33Protocol.BridgeChat(
            id, SENDER, "Steve", "Steve", "lobby", "我是谁", "{\"text\":\"我是谁\"}",
            privateMessage, privateMessage ? "Alex" : "", "", "", List.of(), "", "", "")));
    }

    private static String[] broadcast(String bridge, String id) {
        return new String[] {"BroadcastRaw", SENDER.toString(), "{\"text\":\"我是谁\"}", "", "true",
            "", "我是谁", "Steve", "", bridge, id};
    }

    private static String[] privateChat(String target, String bridge, String id) {
        return new String[] {"ForwardMessage", "SendPrivateRaw", target, "Steve",
            "{\"text\":\"我是谁\"}", "我是谁", "", bridge, id};
    }

    @Test void repeatedRedisCallbacksProduceOneOrdinaryClientDelivery() {
        var gate = new ProxyMessageDeduplicator();
        // Old publishers have no trailing ID, but do carry E33's semantic ID.
        String[] packet = Arrays.copyOf(broadcast(bridge(UUID.randomUUID(), false), ""), 10);
        List<String> vanillaChat = new ArrayList<>();
        for (int subscription = 0; subscription < 12; subscription++) {
            if (gate.accept(packet)) vanillaChat.add(packet[6]);
        }
        assertEquals(List.of("我是谁"), vanillaChat);
    }

    @Test void consecutiveIdenticalTextFromTheSameSenderRemainsTwoMessages() {
        var gate = new ProxyMessageDeduplicator();
        assertTrue(gate.accept(broadcast(bridge(UUID.randomUUID(), false), "")));
        assertTrue(gate.accept(broadcast(bridge(UUID.randomUUID(), false), "")));
    }

    @Test void semanticIdentitySurvivesRewrappingAndForwarding() {
        var gate = new ProxyMessageDeduplicator();
        String encoded = bridge(UUID.randomUUID(), false);
        assertTrue(gate.accept(broadcast(encoded, UUID.randomUUID().toString())));
        String[] forwarded = new String[12];
        forwarded[0] = "ForwardMessage";
        System.arraycopy(broadcast(encoded, UUID.randomUUID().toString()), 0, forwarded, 1, 11);
        assertFalse(gate.accept(forwarded));
    }

    @Test void ordinaryOnlyBroadcastsHaveIndependentDeliveryIds() {
        var gate = new ProxyMessageDeduplicator();
        String[] first = broadcast("", UUID.randomUUID().toString());
        assertTrue(gate.accept(first));
        assertFalse(gate.accept(first.clone()));
        assertTrue(gate.accept(broadcast("", UUID.randomUUID().toString())));
    }

    @Test void malformedSemanticPayloadKeepsOrdinaryFallbackAndItsDeliveryId() {
        var gate = new ProxyMessageDeduplicator();
        String[] packet = broadcast("not-base64", UUID.randomUUID().toString());
        assertTrue(gate.accept(packet));
        assertFalse(gate.accept(packet));
    }

    @Test void legacyPacketsWithoutIdentityAreNotDeduplicatedByText() {
        var gate = new ProxyMessageDeduplicator();
        String[] packet = Arrays.copyOf(broadcast("", ""), 10);
        assertTrue(gate.accept(packet));
        assertTrue(gate.accept(packet));
        assertTrue(gate.accept(new String[] {"ForwardMessage"}));
        assertTrue(gate.accept(new String[0]));
    }

    @Test void privateDeliveryIdsAreScopedToTheRecipient() {
        var gate = new ProxyMessageDeduplicator();
        String id = UUID.randomUUID().toString();
        assertTrue(gate.accept(privateChat("Alex", "", id)));
        assertFalse(gate.accept(privateChat("alex", "", id)));
        assertTrue(gate.accept(privateChat("Alice", "", id)));
        assertTrue(gate.accept(privateChat("Alex", "", UUID.randomUUID().toString())));
    }

    @Test void legacyPrivatePacketsCanUseTheirSemanticIdentity() {
        var gate = new ProxyMessageDeduplicator();
        String[] packet = Arrays.copyOf(privateChat("Alex", bridge(UUID.randomUUID(), true), ""), 8);
        assertTrue(gate.accept(packet));
        assertFalse(gate.accept(packet));
    }

    @Test void metadataAndDirectoryPacketsCannotConsumePublicChatIdentity() {
        var gate = new ProxyMessageDeduplicator();
        UUID id = UUID.randomUUID();
        assertTrue(gate.accept(new String[] {"E33Meta", "node", id.toString(), "global", "meta"}));
        assertTrue(gate.accept(new String[] {"UpdateNames", "25565", "Steve", "Steve", SENDER.toString()}));
        assertTrue(gate.accept(broadcast(bridge(id, false), "")));
    }

    @Test void everyBackendReceivesTheMessageOnce() {
        String[] packet = broadcast(bridge(UUID.randomUUID(), false), "");
        for (int backend = 0; backend < 4; backend++) {
            var gate = new ProxyMessageDeduplicator();
            assertTrue(gate.accept(packet));
            assertFalse(gate.accept(packet));
        }
    }

    @Test void concurrentSubscribersAcceptAnEnvelopeExactlyOnce() throws Exception {
        var gate = new ProxyMessageDeduplicator();
        var ready = new CountDownLatch(16);
        var start = new CountDownLatch(1);
        var accepted = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(16)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++) tasks.add(executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                if (gate.accept("redis:one-send")) accepted.incrementAndGet();
                return null;
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, accepted.get());
    }

    @Test void identitiesExpireWithoutDuplicateTrafficExtendingTheirLifetime() {
        var clock = new AtomicLong(1000);
        var gate = new ProxyMessageDeduplicator(10, 60_000, clock::get);
        assertTrue(gate.accept("first"));
        clock.addAndGet(59_999);
        assertFalse(gate.accept("first"));
        clock.incrementAndGet();
        assertTrue(gate.accept("first"));
    }

    @Test void identityCacheHasABoundedSize() {
        var gate = new ProxyMessageDeduplicator(2, 60_000, () -> 0);
        assertTrue(gate.accept("first"));
        assertTrue(gate.accept("second"));
        assertTrue(gate.accept("third"));
        assertFalse(gate.accept("second"));
        assertFalse(gate.accept("third"));
        assertTrue(gate.accept("first"));
    }

    @Test void absentLegacyEnvelopeIdsRemainCompatible() {
        var gate = new ProxyMessageDeduplicator();
        assertTrue(gate.accept((String) null));
        assertTrue(gate.accept(""));
        assertTrue(gate.accept("neo:message"));
        assertFalse(gate.accept("neo:message"));
    }
}
