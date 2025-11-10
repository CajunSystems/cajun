package com.cajunsystems.dispatcher;

/**
 * Defines how a dispatcher mailbox handles message overflow when the queue is full.
 * 
 * <ul>
 *   <li>{@link #BLOCK} - Sender blocks until space is available (default, backpressure-friendly)</li>
 *   <li>{@link #DROP} - New message is dropped when queue is full (non-blocking, lossy)</li>
 * </ul>
 */
public enum OverflowStrategy {
    /**
     * Block the sender until space is available in the mailbox.
     * Uses spin-wait with adaptive sleep for efficiency.
     * This provides natural backpressure to message producers.
     * Default and recommended for most use cases.
     */
    BLOCK,
    
    /**
     * Drop the message if the mailbox is full.
     * The enqueue operation returns immediately with false.
     * Use this for scenarios where message loss is acceptable
     * and you want to avoid blocking the sender.
     */
    DROP
}
