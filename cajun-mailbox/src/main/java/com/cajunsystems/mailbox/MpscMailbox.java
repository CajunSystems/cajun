package com.cajunsystems.mailbox;

import org.jctools.queues.MpscUnboundedArrayQueue;

import java.util.Collection;
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
        // JCTools MPSC requires power of 2
        int capacity = nextPowerOfTwo(initialCapacity);
        this.queue = new MpscUnboundedArrayQueue<>(capacity);
        this.lock = new ReentrantLock();
        this.notEmpty = lock.newCondition();
        this.initialCapacity = capacity;
    }

    @Override
    public boolean offer(T message) {
        if (message == null) {
            throw new NullPointerException("Message cannot be null");
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
            while (true) {
                message = queue.poll();
                if (message != null) {
                    return message;
                }

                notEmpty.await();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int drainTo(Collection<? super T> collection, int maxElements) {
        if (collection == null) {
            throw new NullPointerException();
        }
        if (collection == this) {
            throw new IllegalArgumentException();
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
     */
    private void signalNotEmpty() {
        // Only acquire lock if we think someone might be waiting
        // This is an optimization to avoid lock contention on every offer
        if (lock.hasQueuedThreads()) {
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
}
