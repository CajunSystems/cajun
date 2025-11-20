package com.cajunsystems.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tracks message delivery status for exactly-once and at-least-once delivery guarantees.
 * Handles message deduplication, acknowledgments, and retries.
 */
public class MessageTracker {
    private static final Logger logger = LoggerFactory.getLogger(MessageTracker.class);
    private static final Duration DEFAULT_MESSAGE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_CLEANUP_INTERVAL = Duration.ofMinutes(5);
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(5);
    
    // Maps message IDs to their delivery status
    private final Map<String, MessageStatus> outgoingMessages = new ConcurrentHashMap<>();
    
    // Maps message IDs to timestamps for deduplication
    private final Map<String, Long> processedMessageIds = new ConcurrentHashMap<>();
    
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Duration messageTimeout;
    private final int maxRetries;
    private final Duration retryDelay;
    
    /**
     * Creates a new MessageTracker with default settings.
     */
    public MessageTracker() {
        this(DEFAULT_MESSAGE_TIMEOUT, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_DELAY);
    }
    
    /**
     * Creates a new MessageTracker with custom settings.
     *
     * @param messageTimeout How long to track messages before considering them failed
     * @param maxRetries Maximum number of retry attempts for failed messages
     * @param retryDelay Delay between retry attempts
     */
    public MessageTracker(Duration messageTimeout, int maxRetries, Duration retryDelay) {
        this.messageTimeout = messageTimeout;
        this.maxRetries = maxRetries;
        this.retryDelay = retryDelay;
        
        // Schedule periodic cleanup of old message records
        scheduler.scheduleAtFixedRate(
            this::cleanupOldMessages, 
            DEFAULT_CLEANUP_INTERVAL.toMillis(), 
            DEFAULT_CLEANUP_INTERVAL.toMillis(), 
            TimeUnit.MILLISECONDS
        );
    }
    
    /**
     * Tracks a new outgoing message.
     *
     * @param messageId The ID of the message
     * @param targetSystemId The ID of the target system
     * @param actorId The ID of the target actor
     * @param message The message content
     * @param retryHandler A handler to call when the message needs to be retried
     * @return The message ID
     */
    public String trackOutgoingMessage(String messageId, String targetSystemId, String actorId, 
                                      Object message, RetryHandler retryHandler) {
        MessageStatus status = new MessageStatus(
            messageId, targetSystemId, actorId, message, 
            System.currentTimeMillis(), 0, retryHandler
        );
        outgoingMessages.put(messageId, status);
        return messageId;
    }
    
    /**
     * Generates a new unique message ID.
     *
     * @return A unique message ID
     */
    public String generateMessageId() {
        return UUID.randomUUID().toString();
    }
    
    /**
     * Marks a message as acknowledged (successfully delivered).
     *
     * @param messageId The ID of the message
     */
    public void acknowledgeMessage(String messageId) {
        outgoingMessages.remove(messageId);
    }
    
    /**
     * Checks if a message has already been processed (for deduplication).
     *
     * @param messageId The ID of the message
     * @return true if the message has already been processed, false otherwise
     */
    public boolean isMessageProcessed(String messageId) {
        return processedMessageIds.containsKey(messageId);
    }
    
    /**
     * Marks a message as processed to prevent duplicate processing.
     *
     * @param messageId The ID of the message
     */
    public void markMessageProcessed(String messageId) {
        processedMessageIds.put(messageId, System.currentTimeMillis());
    }
    
    /**
     * Cleans up old message records to prevent memory leaks.
     */
    private void cleanupOldMessages() {
        long now = System.currentTimeMillis();
        long timeoutMillis = messageTimeout.toMillis();
        
        // Clean up old processed message IDs
        processedMessageIds.entrySet().removeIf(entry -> 
            (now - entry.getValue()) > timeoutMillis
        );
        
        // Check for timed-out outgoing messages
        outgoingMessages.forEach((id, status) -> {
            if ((now - status.timestamp) > timeoutMillis) {
                if (status.retryCount < maxRetries) {
                    // Schedule a retry
                    status.retryCount++;
                    status.timestamp = now;
                    
                    scheduler.schedule(() -> {
                        status.retryHandler.retry(status.messageId, status.targetSystemId, 
                                                status.actorId, status.message);
                        logger.debug("Retrying message {} to {}/{} (attempt {})", 
                                    status.messageId, status.targetSystemId, 
                                    status.actorId, status.retryCount);
                    }, retryDelay.toMillis(), TimeUnit.MILLISECONDS);
                } else {
                    // Max retries exceeded, give up
                    logger.warn("Message {} to {}/{} failed after {} retries", 
                               status.messageId, status.targetSystemId, 
                               status.actorId, maxRetries);
                    outgoingMessages.remove(id);
                }
            }
        });
    }
    
    /**
     * Shuts down the message tracker.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }
    
    /**
     * Handler for retrying message delivery.
     */
    public interface RetryHandler {
        /**
         * Retries sending a message.
         *
         * @param messageId The ID of the message
         * @param targetSystemId The ID of the target system
         * @param actorId The ID of the target actor
         * @param message The message content
         */
        void retry(String messageId, String targetSystemId, String actorId, Object message);
    }
    
    /**
     * Represents the status of an outgoing message.
     */
    private static class MessageStatus {
        final String messageId;
        final String targetSystemId;
        final String actorId;
        final Object message;
        long timestamp;
        int retryCount;
        final RetryHandler retryHandler;
        
        MessageStatus(String messageId, String targetSystemId, String actorId, 
                     Object message, long timestamp, int retryCount, 
                     RetryHandler retryHandler) {
            this.messageId = messageId;
            this.targetSystemId = targetSystemId;
            this.actorId = actorId;
            this.message = message;
            this.timestamp = timestamp;
            this.retryCount = retryCount;
            this.retryHandler = retryHandler;
        }
    }
}
