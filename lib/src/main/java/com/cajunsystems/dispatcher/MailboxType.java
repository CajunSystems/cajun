package com.cajunsystems.dispatcher;

/**
 * Defines the type of mailbox implementation to use for an actor.
 * 
 * <ul>
 *   <li>{@link #BLOCKING} - Traditional thread-per-actor with blocking queue (backward compatible)</li>
 *   <li>{@link #DISPATCHER_CBQ} - Dispatcher-based with ConcurrentLinkedQueue (non-blocking)</li>
 *   <li>{@link #DISPATCHER_MPSC} - Dispatcher-based with lock-free MpscArrayQueue (highest throughput)</li>
 * </ul>
 */
public enum MailboxType {
    /**
     * Traditional thread-per-actor model using MailboxProcessor.
     * Uses LinkedBlockingQueue with dedicated thread per actor.
     * This is the default mode for backward compatibility.
     */
    BLOCKING,
    
    /**
     * Dispatcher-based execution with ConcurrentLinkedQueue.
     * Actors share a virtual thread pool and are scheduled on-demand.
     * Non-blocking, unbounded queue with better scalability than LinkedBlockingQueue.
     * Good balance between performance and simplicity.
     */
    DISPATCHER_CBQ,
    
    /**
     * Dispatcher-based execution with JCTools MpscArrayQueue.
     * Lock-free, high-throughput mailbox for maximum performance.
     * Queue capacity must be a power of 2.
     * Best choice for high-throughput scenarios.
     */
    DISPATCHER_MPSC
}
