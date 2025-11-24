package com.cajunsystems.mailbox;

import org.jctools.queues.MpscUnboundedArrayQueue;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * High-performance mailbox implementation using JCTools MPSC (Multi-Producer Single-Consumer) queue.
 *
 * This implementation provides:
 * - Lock-free message enqueuing (offer operations)
 * - Minimal allocation overhead
 * - Excellent performance for high-throughput scenarios
 *
 * Recommended for:
 * - High-throughput actors with many senders
 * - Low-latency requirements
 * - CPU-bound workloads
 *
 * Trade-offs:
 * - Uses more memory than LinkedBlockingQueue (array-based with chunking)
 * - Blocking operations (poll with timeout, take) use a lock for waiting
 * - Unbounded by default (bounded variant available with MpscArrayQueue)
 *
 * @param <T> The type of messages
 */
public class MpscMailbox<T> implements Mailbox<T> {

    private final MpscUnboundedArrayQueue<T> queue;
    private final ReentrantLock lock;
    private final Condition notEmpty;
    private final int initialCapacity;
    private volatile boolean hasWaitingConsumers = false;

    // Memory pressure monitoring (optional)
    private volatile boolean memoryPressureEnabled = false;
    private volatile int memoryPressureThreshold = 10000; // Default: reject after 10K messages
    private volatile long totalMessagesOffered = 0;
    private volatile long totalMessagesRejected = 0;

    /**
     * Creates an MPSC mailbox with default initial capacity (128).
     */
    public MpscMailbox() {
        this(128);
    }

    /**
     * Creates an MPSC mailbox with the specified initial chunk size.
     *
     * Note: This is unbounded - the initial capacity is just the chunk size.
     * The queue will grow automatically.
     *
     * @param initialCapacity the initial chunk size (must be power of 2)
     */
    public MpscMailbox(int initialCapacity) {
        // Accept zero or negative and treat as minimum chunk size 2 (JCTools requires at least 2)
        int safeCapacity = initialCapacity <= 0 ? 2 : initialCapacity;
        int capacity = nextPowerOfTwo(safeCapacity);
        this.queue = new MpscUnboundedArrayQueue<>(capacity);
        this.lock = new ReentrantLock();
        this.notEmpty = lock.newCondition();
        this.initialCapacity = capacity;
    }

    @Override
    public boolean offer(T message) {
        Objects.requireNonNull(message, "Message cannot be null");

        totalMessagesOffered++;

        // Check memory pressure before accepting message (if enabled)
        if (shouldApplyBackpressure()) {
            totalMessagesRejected++;
            return false;
        }

        boolean added = queue.offer(message);

        if (added) {
            // Signal waiting consumers (only if someone might be waiting)
            // This is optimistic - we avoid lock if queue was not empty
            signalNotEmpty();
        }

        return added;
    }

    @Override
    public boolean offer(T message, long timeout, TimeUnit unit) throws InterruptedException {
        // MPSC unbounded queue never fails to add, so timeout is irrelevant
        return offer(message);
    }

    @Override
    public void put(T message) throws InterruptedException {
        // MPSC unbounded queue never blocks on put
        offer(message);
    }

    @Override
    public T poll() {
        return queue.poll();
    }

    @Override
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        // Fast path: try non-blocking poll first
        T message = queue.poll();
        if (message != null) {
            return message;
        }

        // Slow path: wait with timeout
        if (timeout <= 0) {
            return null;
        }

        long nanos = unit.toNanos(timeout);
        lock.lock();
        try {
            // Double-check after acquiring lock
            message = queue.poll();
            if (message != null) {
                return message;
            }

            // Indicate we're waiting
            hasWaitingConsumers = true;

            // Wait for signal or timeout
            long deadline = System.nanoTime() + nanos;
            while (nanos > 0) {
                message = queue.poll();
                if (message != null) {
                    return message;
                }

                nanos = notEmpty.awaitNanos(nanos);

                // Check again after waking up
                message = queue.poll();
                if (message != null) {
                    return message;
                }

                // Recalculate remaining time
                long now = System.nanoTime();
                nanos = deadline - now;
            }

            return null; // Timeout
        } finally {
            hasWaitingConsumers = false;
            lock.unlock();
        }
    }

    @Override
    public T take() throws InterruptedException {
        // Fast path: try non-blocking poll first
        T message = queue.poll();
        if (message != null) {
            return message;
        }

        // Slow path: wait indefinitely
        lock.lock();
        try {
            // Indicate we're waiting
            hasWaitingConsumers = true;

            while (true) {
                message = queue.poll();
                if (message != null) {
                    return message;
                }

                notEmpty.await();
            }
        } finally {
            hasWaitingConsumers = false;
            lock.unlock();
        }
    }

    @Override
    public int drainTo(Collection<? super T> collection, int maxElements) {
        Objects.requireNonNull(collection, "Collection cannot be null");
        if (collection == this) {
            throw new IllegalArgumentException("Cannot drain to self");
        }

        int count = 0;
        while (count < maxElements) {
            T message = queue.poll();
            if (message == null) {
                break;
            }
            collection.add(message);
            count++;
        }
        return count;
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    @Override
    public int remainingCapacity() {
        // Unbounded queue
        return Integer.MAX_VALUE;
    }

    @Override
    public void clear() {
        queue.clear();
    }

    @Override
    public int capacity() {
        // Unbounded
        return Integer.MAX_VALUE;
    }

    /**
     * Signals waiting consumers that a message is available.
     * This is called after adding a message.
     * Only acquires lock if threads are actually waiting to preserve lock-free performance.
     */
    private void signalNotEmpty() {
        // Check volatile flag first - avoids lock acquisition on hot path
        if (hasWaitingConsumers) {
            lock.lock();
            try {
                notEmpty.signal();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Rounds up to the next power of 2.
     */
    private static int nextPowerOfTwo(int value) {
        if (value <= 0) {
            return 1;
        }
        if ((value & (value - 1)) == 0) {
            return value; // Already power of 2
        }
        int result = 1;
        while (result < value) {
            result <<= 1;
        }
        return result;
    }

    // Memory pressure monitoring methods

    /**
     * Checks if backpressure should be applied based on current queue size and memory pressure settings.
     * This is called before accepting a message in high-throughput scenarios.
     *
     * @return true if message should be rejected due to memory pressure
     */
    private boolean shouldApplyBackpressure() {
        if (!memoryPressureEnabled) {
            return false;
        }

        // Check queue size against threshold
        return queue.size() >= memoryPressureThreshold;
    }

    /**
     * Enables memory pressure monitoring for high-throughput scenarios.
     * When enabled, messages will be rejected if the queue size exceeds the threshold.
     *
     * @param threshold the maximum queue size before rejecting messages (default: 10000)
     */
    public void enableMemoryPressure(int threshold) {
        this.memoryPressureThreshold = threshold;
        this.memoryPressureEnabled = true;
    }

    /**
     * Disables memory pressure monitoring.
     * The mailbox will accept messages without size limits (unbounded behavior).
     */
    public void disableMemoryPressure() {
        this.memoryPressureEnabled = false;
    }

    /**
     * Returns the total number of messages offered to this mailbox.
     *
     * @return total messages offered (including rejected)
     */
    public long getTotalMessagesOffered() {
        return totalMessagesOffered;
    }

    /**
     * Returns the total number of messages rejected due to memory pressure.
     *
     * @return total messages rejected
     */
    public long getTotalMessagesRejected() {
        return totalMessagesRejected;
    }

    /**
     * Returns the rejection rate (0.0 to 1.0) due to memory pressure.
     *
     * @return rejection rate, or 0.0 if no messages have been offered
     */
    public double getRejectionRate() {
        if (totalMessagesOffered == 0) {
            return 0.0;
        }
        return (double) totalMessagesRejected / totalMessagesOffered;
    }
}
