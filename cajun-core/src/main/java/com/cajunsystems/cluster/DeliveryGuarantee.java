package com.cajunsystems.cluster;

/**
 * Defines the message delivery guarantee levels for remote actor communication.
 */
public enum DeliveryGuarantee {
    /**
     * Messages are delivered exactly once.
     * This is the most reliable but potentially slower option.
     * It uses acknowledgments, retries, and deduplication to ensure messages are delivered exactly once.
     */
    EXACTLY_ONCE,
    
    /**
     * Messages are guaranteed to be delivered at least once, but may be delivered multiple times.
     * This is more reliable than AT_MOST_ONCE but may result in duplicate message processing.
     * It uses acknowledgments and retries but no deduplication.
     */
    AT_LEAST_ONCE,
    
    /**
     * Messages are delivered at most once, but may not be delivered at all.
     * This is the fastest option but provides no delivery guarantees.
     * It uses no acknowledgments, retries, or deduplication.
     */
    AT_MOST_ONCE
}
