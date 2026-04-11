package com.cajunsystems.cluster;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory MessagingSystem that routes messages between in-JVM ClusterActorSystem instances.
 * Use connectTo() to wire two systems together (bidirectional).
 */
class InMemoryMessagingSystem implements MessagingSystem {

    private final String systemId;
    private final ConcurrentHashMap<String, InMemoryMessagingSystem> connectedSystems = new ConcurrentHashMap<>();
    private volatile MessageHandler messageHandler;
    private volatile boolean running = false;

    InMemoryMessagingSystem(String systemId) {
        this.systemId = systemId;
    }

    void connectTo(InMemoryMessagingSystem other) {
        this.connectedSystems.put(other.systemId, other);
        other.connectedSystems.put(this.systemId, this);
    }

    @Override
    public <Message> CompletableFuture<Void> sendMessage(String targetSystemId, String actorId, Message message) {
        return CompletableFuture.runAsync(() -> {
            InMemoryMessagingSystem target = connectedSystems.get(targetSystemId);
            if (target != null && target.messageHandler != null && target.running) {
                target.messageHandler.onMessage(actorId, message);
            }
            // Silent drop if target not available — normal for disconnected/stopped nodes
        });
    }

    @Override
    public CompletableFuture<Void> registerMessageHandler(MessageHandler handler) {
        this.messageHandler = handler;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> this.running = true);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> this.running = false);
    }
}
