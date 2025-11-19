package com.cajunsystems.mailbox;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Default mailbox implementation using LinkedBlockingQueue.
 * This provides good performance with lock-free optimizations for common cases.
 *
 * Recommended for:
 * - General-purpose actor mailboxes
 * - Mixed workloads (CPU and I/O bound)
 * - When backpressure/bounded capacity is needed
 *
 * @param <T> The type of messages
 */
public class LinkedMailbox<T> implements Mailbox<T> {

    private final LinkedBlockingQueue<T> queue;
    private final int capacity;

    /**
     * Creates an unbounded mailbox.
     */
    public LinkedMailbox() {
        this.queue = new LinkedBlockingQueue<>();
        this.capacity = Integer.MAX_VALUE;
    }

    /**
     * Creates a bounded mailbox with the specified capacity.
     *
     * @param capacity the maximum number of messages
     */
    public LinkedMailbox(int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.capacity = capacity;
    }

    @Override
    public boolean offer(T message) {
        Objects.requireNonNull(message, "Message cannot be null");
        return queue.offer(message);
    }

    @Override
    public boolean offer(T message, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(message, "Message cannot be null");
        return queue.offer(message, timeout, unit);
    }

    @Override
    public void put(T message) throws InterruptedException {
        Objects.requireNonNull(message, "Message cannot be null");
        queue.put(message);
    }

    @Override
    public T poll() {
        return queue.poll();
    }

    @Override
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    @Override
    public T take() throws InterruptedException {
        return queue.take();
    }

    @Override
    public int drainTo(Collection<? super T> collection, int maxElements) {
        return queue.drainTo(collection, maxElements);
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
        return queue.remainingCapacity();
    }

    @Override
    public void clear() {
        queue.clear();
    }

    @Override
    public int capacity() {
        return capacity;
    }
}
