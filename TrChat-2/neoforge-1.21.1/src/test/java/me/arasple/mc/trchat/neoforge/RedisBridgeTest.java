package me.arasple.mc.trchat.neoforge;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RedisBridgeTest {
    /** Real TCP sockets speaking RESP, with no external service or server EULA. */
    static final class RedisStub implements AutoCloseable {
        final ServerSocket listener;
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final Map<Socket, OutputStream> subscribers = new ConcurrentHashMap<>();
        final Map<String, String> values = new ConcurrentHashMap<>();
        final Queue<List<String>> received = new ConcurrentLinkedQueue<>();
        volatile boolean open = true;
        RedisStub(int port) throws IOException {
            listener = new ServerSocket(); listener.setReuseAddress(true); listener.bind(new InetSocketAddress("127.0.0.1", port));
            Thread.ofVirtual().start(() -> {
                while (open) try {
                    Socket socket = listener.accept(); sockets.add(socket);
                    Thread.ofVirtual().start(() -> handle(socket));
                } catch (IOException ignored) { }
            });
        }
        @SuppressWarnings("unchecked") void handle(Socket socket) {
            try (socket) {
                var in = new BufferedInputStream(socket.getInputStream()); var out = new BufferedOutputStream(socket.getOutputStream());
                while (open) {
                    List<String> command = (List<String>) (List<?>) RespConnection.read(in); received.add(command);
                    switch (command.getFirst()) {
                        case "AUTH", "SELECT" -> reply(out, "+OK\r\n");
                        case "SUBSCRIBE" -> {
                            synchronized (out) { out.write("*3\r\n".getBytes()); bulk(out, "subscribe"); bulk(out, command.get(1)); out.write(":1\r\n".getBytes()); out.flush(); }
                            subscribers.put(socket, out);
                        }
                        case "PING" -> reply(out, "+PONG\r\n");
                        case "SET" -> { values.put(command.get(1), command.get(2)); reply(out, "+OK\r\n"); }
                        case "GET" -> { synchronized (out) { bulk(out, values.get(command.get(1))); out.flush(); } }
                        case "PUBLISH" -> {
                            for (OutputStream target : subscribers.values()) synchronized (target) {
                                target.write("*3\r\n".getBytes()); bulk(target, "message"); bulk(target, command.get(1)); bulk(target, command.get(2)); target.flush();
                            }
                            reply(out, ":" + subscribers.size() + "\r\n");
                        }
                        default -> reply(out, "-ERR unknown command\r\n");
                    }
                }
            } catch (IOException ignored) { }
            finally { sockets.remove(socket); subscribers.remove(socket); }
        }
        static void bulk(OutputStream out, String value) throws IOException {
            if (value == null) { out.write("$-1\r\n".getBytes()); return; }
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.write(("$" + bytes.length + "\r\n").getBytes()); out.write(bytes); out.write("\r\n".getBytes());
        }
        static void reply(OutputStream out, String value) throws IOException { synchronized (out) { out.write(value.getBytes()); out.flush(); } }
        public void close() throws IOException { open = false; listener.close(); for (Socket socket : sockets) socket.close(); }
    }
    static ChatConfig.Redis config(int port) {
        var config = new ChatConfig.Redis(); config.enabled = true; config.port = port; config.timeoutMillis = 500;
        config.username = "test-user"; config.password = "test-password"; config.database = 2; return config;
    }
    static void await(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(ready.getAsBoolean(), "Timed out waiting for Redis transport");
    }
    @Test void twoNodesExchangeChatAndPrivateDeliveryAckOverRealSockets() throws Exception {
        try (var stub = new RedisStub(0)) {
            var config = config(stub.listener.getLocalPort());
            var aMessages = new LinkedBlockingQueue<String>(); var bMessages = new LinkedBlockingQueue<String>();
            try (var a = new RedisBridge(config, aMessages::add); var b = new RedisBridge(config, bMessages::add)) {
                await(() -> a.available() && b.available() && stub.subscribers.size() == 2);
                String privateChat = WireMessage.encode("node-a", "ForwardMessage", "SendPrivateRaw", "Alex", "Steve", "{}", "你好", "", "", "pm-1");
                assertTrue(a.publish(privateChat).get(3, TimeUnit.SECONDS));
                assertEquals(privateChat, bMessages.poll(3, TimeUnit.SECONDS));
                assertEquals(privateChat, aMessages.poll(3, TimeUnit.SECONDS)); // Redis echoes are suppressed by the router, not the transport.
                String ack = WireMessage.encode("node-b", "ForwardMessage", "PrivateDelivered", "pm-1");
                assertTrue(b.publish(ack).get(3, TimeUnit.SECONDS));
                assertEquals("PrivateDelivered", WireMessage.decode(aMessages.poll(3, TimeUnit.SECONDS)).data()[1]);
                assertEquals("OK", a.command("SET", "history-key", "中文").get(3, TimeUnit.SECONDS));
                assertEquals("中文", b.command("GET", "history-key").get(3, TimeUnit.SECONDS));
                a.heartbeat(); b.heartbeat();
                await(() -> stub.received.stream().anyMatch(c -> c.getFirst().equals("PING")));
                assertTrue(stub.received.contains(List.of("AUTH", "test-user", "test-password")));
                assertTrue(stub.received.contains(List.of("SELECT", "2")));
            }
        }
    }
    @Test void reconnectsAfterOutageAndNeverReportsStoppedPublishAsSuccess() throws Exception {
        RedisStub first = new RedisStub(0);
        int port = first.listener.getLocalPort();
        var messages = new LinkedBlockingQueue<String>();
        try (var bridge = new RedisBridge(config(port), messages::add)) {
            try { await(bridge::available); } finally { first.close(); }
            await(() -> !bridge.available());
            assertFalse(bridge.publish("offline").get(2, TimeUnit.SECONDS));
            try (var restarted = new RedisStub(port)) {
                await(() -> bridge.available() && restarted.subscribers.size() == 1);
                assertTrue(bridge.publish("recovered").get(3, TimeUnit.SECONDS));
                assertEquals("recovered", messages.poll(3, TimeUnit.SECONDS));
            }
            bridge.close();
            assertFalse(bridge.publish("stopped").get(2, TimeUnit.SECONDS));
        }
    }
}
