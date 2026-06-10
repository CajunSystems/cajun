package com.cajunsystems.backpressure;

import java.time.Instant;

/**
 * Holds information about a message scheduled for retry in the backpressure system.
 * This class was extracted from BackpressureManager to improve modularity.
 *
 * @param <M> The type of message being retried
 */
public class RetryEntry<M> {
    private final M message;
    private final BackpressureSendOptions options;
    private final long timeoutMs;
    private final BackpressureEvent originalEvent;
    private final Instant creationTime;
    
    /**
     * Creates a new retry entry.
     *
     * @param message The message to retry
     * @param options The send options
     * @param timeoutMs The timeout in milliseconds
     * @param originalEvent The backpressure event at the time of creation
     */
    public RetryEntry(M message, BackpressureSendOptions options, long timeoutMs, BackpressureEvent originalEvent) {
        this.message = message;
        this.options = options;
        this.timeoutMs = timeoutMs;
        this.originalEvent = originalEvent;
        this.creationTime = Instant.now();
    }
    
    /**
     * Gets the message to retry.
     *
     * @return The message
     */
    public M getMessage() {
        return message;
    }
    
    /**
     * Gets the send options.
     *
     * @return The send options
     */
    public BackpressureSendOptions getOptions() {
        return options;
    }
    
    /**
     * Gets the timeout in milliseconds.
     *
     * @return The timeout
     */
    public long getTimeoutMs() {
        return timeoutMs;
    }
    
    /**
     * Gets the backpressure event at the time of creation.
     *
     * @return The original event
     */
    public BackpressureEvent getOriginalEvent() {
        return originalEvent;
    }
    
    /**
     * Gets the time when this entry was created.
     *
     * @return The creation time
     */
    public Instant getCreationTime() {
        return creationTime;
    }
}
