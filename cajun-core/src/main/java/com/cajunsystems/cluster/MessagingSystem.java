package com.cajunsystems.cluster;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for a messaging system used for communication between actor systems in a cluster.
 * This abstraction allows for different implementations (e.g., direct TCP, message queues, etc.).
 */
public interface MessagingSystem {
    
    /**
     * Sends a message to a remote actor system.
     *
     * @param targetSystemId The ID of the target actor system
     * @param actorId The ID of the target actor
     * @param message The message to send
     * @param <Message> The type of the message
     * @return A CompletableFuture that completes when the message is sent
     */
    <Message> CompletableFuture<Void> sendMessage(String targetSystemId, String actorId, Message message);
    
    /**
     * Registers a message handler for incoming messages.
     *
     * @param handler The handler to process incoming messages
     * @return A CompletableFuture that completes when the handler is registered
     */
    CompletableFuture<Void> registerMessageHandler(MessageHandler handler);
    
    /**
     * Starts the messaging system.
     *
     * @return A CompletableFuture that completes when the system is started
     */
    CompletableFuture<Void> start();
    
    /**
     * Stops the messaging system.
     *
     * @return A CompletableFuture that completes when the system is stopped
     */
    CompletableFuture<Void> stop();
    
    /**
     * Interface for handling incoming messages.
     */
    interface MessageHandler {
        /**
         * Called when a message is received for an actor.
         *
         * @param actorId The ID of the target actor
         * @param message The received message
         * @param <Message> The type of the message
         */
        <Message> void onMessage(String actorId, Message message);
    }
}
