package com.cajunsystems;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * A BlockingQueue adapter for ConcurrentLinkedQueue.
 *
 * <p>This class wraps a ConcurrentLinkedQueue to provide a BlockingQueue interface,
 * allowing it to be used as an unbounded mailbox in the actor system.
 *
 * <p>ConcurrentLinkedQueue is an unbounded, thread-safe, non-blocking queue based on
 * linked nodes. It provides lock-free operations and never blocks producers. This adapter
 * adds blocking semantics for consumers as required by the BlockingQueue interface.
 *
 * <p><strong>Performance characteristics:</strong>
 * <ul>
 *   <li>Truly unbounded (limited only by available memory)</li>
 *   <li>Lock-free operations using CAS</li>
 *   <li>Non-blocking for producers</li>
 *   <li>No pre-allocation overhead</li>
 *   <li>Good for variable or bursty traffic</li>
 * </ul>
 *
 * <p><strong>Trade-offs:</strong>
 * <ul>
 *   <li>No backpressure - can lead to out-of-memory if consumer is too slow</li>
 *   <li>Higher memory overhead per element (linked nodes)</li>
 *   <li>Slightly lower throughput than bounded queues in some scenarios</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class UnboundedConcurrentBlockingQueue<E> implements BlockingQueue<E> {

    private final ConcurrentLinkedQueue<E> queue;

    /**
     * Creates a new UnboundedConcurrentBlockingQueue.
     */
    public UnboundedConcurrentBlockingQueue() {
        this.queue = new ConcurrentLinkedQueue<>();
    }

    @Override
    public boolean add(E e) {
        if (e == null) {
            throw new NullPointerException("Null elements not allowed");
        }
        return queue.add(e);
    }

    @Override
    public boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException("Null elements not allowed");
        }
        return queue.offer(e);
    }

    @Override
    public void put(E e) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException("Null elements not allowed");
        }
        // ConcurrentLinkedQueue is unbounded, so offer always succeeds
        queue.offer(e);
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException("Null elements not allowed");
        }
        // ConcurrentLinkedQueue is unbounded, so offer always succeeds
        return queue.offer(e);
    }

    @Override
    public E take() throws InterruptedException {
        E element;
        // Spin-wait with periodic sleep to add blocking semantics
        while ((element = queue.poll()) == null) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            // Small sleep to avoid busy-waiting
            Thread.sleep(1);
        }
        return element;
    }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanos;

        E element;
        while ((element = queue.poll()) == null) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return null;
            }
            // Small sleep to avoid busy-waiting
            Thread.sleep(1);
        }
        return element;
    }

    @Override
    public E remove() {
        E element = queue.poll();
        if (element == null) {
            throw new java.util.NoSuchElementException();
        }
        return element;
    }

    @Override
    public E poll() {
        return queue.poll();
    }

    @Override
    public E element() {
        E element = queue.peek();
        if (element == null) {
            throw new java.util.NoSuchElementException();
        }
        return element;
    }

    @Override
    public E peek() {
        return queue.peek();
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
        // Unbounded queue - return max int
        return Integer.MAX_VALUE;
    }

    @Override
    public void clear() {
        queue.clear();
    }

    @Override
    public int drainTo(Collection<? super E> c) {
        if (c == null) {
            throw new NullPointerException();
        }
        if (c == this) {
            throw new IllegalArgumentException();
        }
        int count = 0;
        E element;
        while ((element = queue.poll()) != null) {
            c.add(element);
            count++;
        }
        return count;
    }

    @Override
    public int drainTo(Collection<? super E> c, int maxElements) {
        if (c == null) {
            throw new NullPointerException();
        }
        if (c == this) {
            throw new IllegalArgumentException();
        }
        if (maxElements <= 0) {
            return 0;
        }
        int count = 0;
        E element;
        while (count < maxElements && (element = queue.poll()) != null) {
            c.add(element);
            count++;
        }
        return count;
    }

    @Override
    public boolean contains(Object o) {
        return queue.contains(o);
    }

    @Override
    public Iterator<E> iterator() {
        return queue.iterator();
    }

    @Override
    public Object[] toArray() {
        return queue.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return queue.toArray(a);
    }

    @Override
    public boolean remove(Object o) {
        return queue.remove(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return queue.containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        if (c == null) {
            throw new NullPointerException();
        }
        if (c == this) {
            throw new IllegalArgumentException();
        }
        boolean modified = false;
        for (E e : c) {
            if (add(e)) {
                modified = true;
            }
        }
        return modified;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return queue.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return queue.retainAll(c);
    }
}
