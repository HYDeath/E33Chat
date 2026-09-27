package me.arasple.mc.trchat.neoforge;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Independent subscription and command connections; never block a Minecraft tick. */
final class RedisBridge implements AutoCloseable {
    private final ChatConfig.Redis config;
    private final Consumer<String> receiver;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(256), runnable -> daemon(runnable, "TrChat-Redis-commands"));
    private volatile RespConnection subscriber;
    private volatile RespConnection commands;
    private volatile boolean running = true;
    private volatile boolean subscribed;
    private final Thread thread;
    private final java.util.Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();

    RedisBridge(ChatConfig.Redis config, Consumer<String> receiver) {
        this.config = config;
        this.receiver = receiver;
        thread = daemon(this::listen, "TrChat-Redis-subscription");
        thread.start();
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread;
    }
    boolean available() { return running && subscribed; }
    void heartbeat() {
        if (!available()) return;
        try { worker.execute(() -> {
            RespConnection connection = subscriber;
            if (connection != null) try { connection.ping(); }
            catch (IOException ex) { closeQuietly(connection); }
        }); } catch (RejectedExecutionException ignored) { }
    }

    CompletableFuture<Object> command(String... args) {
        CompletableFuture<Object> result = new CompletableFuture<>();
        if (!running) { result.completeExceptionally(new IOException("Redis stopped")); return result; }
        inFlight.add(result);
        result.whenComplete((value, error) -> inFlight.remove(result));
        try {
            worker.execute(() -> {
                try {
                    if (!running) throw new IOException("Redis stopped");
                    if (commands == null) commands = new RespConnection(config);
                    result.complete(commands.command(args));
                } catch (Exception ex) {
                    closeQuietly(commands); commands = null;
                    result.completeExceptionally(new IOException("Redis command unavailable"));
                }
                if (!running) { closeQuietly(commands); commands = null; }
            });
        } catch (RejectedExecutionException ex) { result.completeExceptionally(new IOException("Redis queue full/stopped")); }
        return result;
    }

    CompletableFuture<Boolean> publish(String message) {
        if (!available()) return CompletableFuture.completedFuture(false);
        return command("PUBLISH", config.channel, message).handle((value, error) -> error == null);
    }

    private void listen() {
        while (running) {
            try (RespConnection connection = new RespConnection(config)) {
                subscriber = connection;
                connection.subscribe(config.channel);
                subscribed = true;
                TrChatMod.LOGGER.info("TrChat Redis connected");
                while (running) {
                    // A timeout may occur mid-frame. Reconnect rather than resuming a partial RESP frame.
                    Object reply = connection.next();
                    if (reply instanceof List<?> parts && parts.size() == 3 && "message".equals(parts.get(0))) {
                        try { receiver.accept((String) parts.get(2)); }
                        catch (RuntimeException ex) { TrChatMod.LOGGER.warn("Rejected malformed TrChat Redis event"); }
                    }
                }
            } catch (SocketTimeoutException ignored) {
                // Periodic reconnect also checks a half-open subscription.
            } catch (Exception ex) {
                if (running) TrChatMod.LOGGER.warn("TrChat Redis unavailable; local chat remains active");
            } finally { subscribed = false; subscriber = null; }
            if (running) try { Thread.sleep(2000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static void closeQuietly(RespConnection connection) {
        if (connection != null) try { connection.close(); } catch (IOException ignored) { }
    }
    @Override public void close() {
        running = false; subscribed = false;
        closeQuietly(subscriber); thread.interrupt();
        // Connections belong to the worker. Closing it externally unblocks a stuck read.
        closeQuietly(commands);
        worker.shutdownNow();
        for (CompletableFuture<?> result : inFlight) result.completeExceptionally(new IOException("Redis stopped"));
    }
}
