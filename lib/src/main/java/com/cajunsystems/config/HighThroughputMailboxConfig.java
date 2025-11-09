package com.cajunsystems.config;

/**
 * Configuration for high-throughput mailbox using JCTools MpscArrayQueue.
 *
 * <p>This configuration creates a Multiple Producer Single Consumer (MPSC) bounded queue
 * optimized for high-throughput scenarios with multiple senders and a single consumer (actor).
 *
 * <p>MpscArrayQueue is a lock-free, bounded, multiple-producer single-consumer queue that provides:
 * <ul>
 *   <li>Better throughput than LinkedBlockingQueue in multi-producer scenarios</li>
 *   <li>Lower latency due to reduced lock contention</li>
 *   <li>Better CPU cache locality with array-based storage</li>
 *   <li>Wait-free producers with single CAS operation</li>
 * </ul>
 *
 * <p><strong>Best for:</strong>
 * <ul>
 *   <li>High message throughput scenarios (millions of messages/second)</li>
 *   <li>Multiple actors sending messages to a single actor</li>
 *   <li>Low-latency requirements</li>
 *   <li>CPU-bound message processing</li>
 * </ul>
 *
 * <p><strong>Trade-offs:</strong>
 * <ul>
 *   <li>Bounded capacity (must be power of 2)</li>
 *   <li>Fixed size (no dynamic resizing)</li>
 *   <li>More memory usage due to pre-allocation</li>
 *   <li>Requires JCTools dependency</li>
 * </ul>
 *
 * <p><strong>Example usage:</strong>
 * <pre>{@code
 * HighThroughputMailboxConfig config = new HighThroughputMailboxConfig()
 *     .setCapacity(16384); // Must be power of 2
 *
 * Pid actor = system.actorOf(MyHandler.class)
 *     .withMailboxConfig(config)
 *     .spawn();
 * }</pre>
 */
public class HighThroughputMailboxConfig extends MailboxConfig {

    /**
     * Default capacity for high-throughput queues (16K, a power of 2).
     */
    public static final int DEFAULT_HIGH_THROUGHPUT_CAPACITY = 16384;

    private int capacity;

    /**
     * Creates a new HighThroughputMailboxConfig with default capacity (16384).
     */
    public HighThroughputMailboxConfig() {
        super();
        this.capacity = DEFAULT_HIGH_THROUGHPUT_CAPACITY;
        // Set the initial and max capacity to match the MPSC queue capacity
        setInitialCapacity(capacity);
        setMaxCapacity(capacity);
    }

    /**
     * Creates a new HighThroughputMailboxConfig with the specified capacity.
     *
     * @param capacity The capacity (must be a power of 2)
     * @throws IllegalArgumentException if capacity is not a power of 2 or is less than 2
     */
    public HighThroughputMailboxConfig(int capacity) {
        super();
        setCapacity(capacity);
    }

    /**
     * Sets the capacity for the high-throughput queue.
     *
     * <p><strong>Important:</strong> The capacity must be a power of 2 (e.g., 1024, 2048, 4096, 8192, 16384, 32768).
     * This is a requirement of the underlying MpscArrayQueue implementation for optimal performance.
     *
     * @param capacity The capacity (must be a power of 2 and at least 2)
     * @return This HighThroughputMailboxConfig instance
     * @throws IllegalArgumentException if capacity is not a power of 2 or is less than 2
     */
    public HighThroughputMailboxConfig setCapacity(int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("Capacity must be at least 2");
        }
        if (!isPowerOfTwo(capacity)) {
            throw new IllegalArgumentException(
                "Capacity must be a power of 2 for MpscArrayQueue (e.g., 1024, 2048, 4096, 8192, 16384, 32768). " +
                "Provided capacity: " + capacity
            );
        }
        this.capacity = capacity;
        setInitialCapacity(capacity);
        setMaxCapacity(capacity);
        return this;
    }

    /**
     * Gets the capacity for the high-throughput queue.
     *
     * @return The capacity
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Checks if this mailbox is resizable.
     *
     * @return false, as MpscArrayQueue has a fixed capacity
     */
    @Override
    public boolean isResizable() {
        return false;
    }

    /**
     * Checks if a number is a power of 2.
     *
     * @param n The number to check
     * @return true if n is a power of 2, false otherwise
     */
    private static boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }
}
