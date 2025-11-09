package com.cajunsystems.config;

/**
 * Configuration for unbounded mailbox using ConcurrentLinkedQueue.
 *
 * <p>This configuration creates an unbounded, lock-free, non-blocking queue based on
 * linked nodes. It's optimized for scenarios where the mailbox should never block
 * producers and can grow indefinitely (up to available memory).
 *
 * <p>ConcurrentLinkedQueue provides:
 * <ul>
 *   <li>True unbounded capacity (limited only by available memory)</li>
 *   <li>Lock-free operations using CAS (Compare-And-Swap)</li>
 *   <li>Non-blocking producers and consumers</li>
 *   <li>Wait-free enqueue and dequeue operations</li>
 *   <li>No pre-allocation of memory</li>
 * </ul>
 *
 * <p><strong>Best for:</strong>
 * <ul>
 *   <li>Scenarios where producers should never block</li>
 *   <li>Variable or unpredictable message rates</li>
 *   <li>When you want to avoid capacity tuning</li>
 *   <li>Systems with bursty traffic patterns</li>
 *   <li>Event logging or audit systems</li>
 * </ul>
 *
 * <p><strong>Trade-offs:</strong>
 * <ul>
 *   <li>No backpressure - can lead to out-of-memory if consumer is slower than producers</li>
 *   <li>Higher memory overhead per element (linked nodes)</li>
 *   <li>Slightly lower throughput than bounded queues in some scenarios</li>
 *   <li>No blocking operations (polling only)</li>
 * </ul>
 *
 * <p><strong>Example usage:</strong>
 * <pre>{@code
 * UnboundedMailboxConfig config = new UnboundedMailboxConfig();
 *
 * Pid actor = system.actorOf(MyHandler.class)
 *     .withMailboxConfig(config)
 *     .spawn();
 * }</pre>
 *
 * <p><strong>Warning:</strong> Use this configuration carefully. Since the queue is unbounded,
 * if your actor processes messages slower than they arrive, the queue will grow indefinitely
 * and may eventually cause an OutOfMemoryError. Consider using backpressure strategies or
 * monitoring queue size in production systems.
 */
public class UnboundedMailboxConfig extends MailboxConfig {

    /**
     * Creates a new UnboundedMailboxConfig.
     *
     * <p>The queue will be truly unbounded, with capacity only limited by available memory.
     */
    public UnboundedMailboxConfig() {
        super();
        // Set max capacity to Integer.MAX_VALUE to indicate unbounded
        setMaxCapacity(Integer.MAX_VALUE);
        // Initial capacity is not relevant for ConcurrentLinkedQueue
        setInitialCapacity(0);
    }

    /**
     * Checks if this mailbox is resizable.
     *
     * @return false, as ConcurrentLinkedQueue is already unbounded and doesn't need resizing
     */
    @Override
    public boolean isResizable() {
        return false;
    }

    /**
     * Sets the initial capacity. This method has no effect for unbounded mailboxes.
     *
     * @param initialCapacity Ignored for unbounded queues
     * @return This UnboundedMailboxConfig instance
     */
    @Override
    public MailboxConfig setInitialCapacity(int initialCapacity) {
        // Ignore, as ConcurrentLinkedQueue doesn't have a concept of initial capacity
        return this;
    }

    /**
     * Sets the maximum capacity. This method has no effect for unbounded mailboxes.
     *
     * @param maxCapacity Ignored for unbounded queues
     * @return This UnboundedMailboxConfig instance
     */
    @Override
    public MailboxConfig setMaxCapacity(int maxCapacity) {
        // Keep max capacity at Integer.MAX_VALUE
        super.setMaxCapacity(Integer.MAX_VALUE);
        return this;
    }
}
