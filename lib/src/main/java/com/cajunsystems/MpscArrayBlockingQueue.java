package com.cajunsystems;

import org.jctools.queues.MpscArrayQueue;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A BlockingQueue adapter for JCTools MpscArrayQueue.
 *
 * <p>This class wraps a JCTools MpscArrayQueue to provide a BlockingQueue interface,
 * allowing it to be used as a high-performance mailbox in the actor system.
 *
 * <p>MpscArrayQueue is a Multiple Producer Single Consumer bounded queue optimized for
 * high throughput with multiple producers. This adapter adds blocking semantics required
 * by the BlockingQueue interface.
 *
 * <p><strong>Performance characteristics:</strong>
 * <ul>
 *   <li>Wait-free producers (single CAS operation)</li>
 *   <li>Lock-free consumer</li>
 *   <li>Excellent cache locality due to array-based storage</li>
 *   <li>Lower overhead than LinkedBlockingQueue in multi-producer scenarios</li>
 * </ul>
 *
 * <p><strong>Limitations:</strong>
 * <ul>
 *   <li>Capacity must be a power of 2</li>
 *   <li>Fixed capacity (no resizing)</li>
 *   <li>Some BlockingQueue methods throw UnsupportedOperationException</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class MpscArrayBlockingQueue<E> implements BlockingQueue<E> {

    private final MpscArrayQueue<E> queue;
    private final int capacity;

    /**
     * Creates a new MpscArrayBlockingQueue with the specified capacity.
     *
     * @param capacity the capacity (must be a power of 2)
     * @throws IllegalArgumentException if capacity is not a power of 2 or is less than 2
     */
    public MpscArrayBlockingQueue(int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("Capacity must be at least 2");
        }
        if (!isPowerOfTwo(capacity)) {
            throw new IllegalArgumentException(
                "Capacity must be a power of 2 for MpscArrayQueue. Provided: " + capacity
            );
        }
        this.capacity = capacity;
        this.queue = new MpscArrayQueue<>(capacity);
    }

    @Override
    public boolean add(E e) {
        if (offer(e)) {
            return true;
        }
        throw new IllegalStateException("Queue full");
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
        // Spin-wait with periodic sleep to add blocking semantics
        while (!queue.offer(e)) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            // Small sleep to avoid busy-waiting
            Thread.sleep(1);
        }
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException("Null elements not allowed");
        }
        long nanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanos;

        while (!queue.offer(e)) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            // Small sleep to avoid busy-waiting
            Thread.sleep(1);
        }
        return true;
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
        E element = poll();
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
        E element = peek();
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
        return capacity - queue.size();
    }

    @Override
    public void clear() {
        queue.clear();
    }

    @Override
    public int drainTo(Collection<? super E> c) {
        return queue.drain(c::add);
    }

    @Override
    public int drainTo(Collection<? super E> c, int maxElements) {
        return queue.drain(c::add, maxElements);
    }

    @Override
    public boolean contains(Object o) {
        throw new UnsupportedOperationException("contains() not supported for performance reasons");
    }

    @Override
    public Iterator<E> iterator() {
        throw new UnsupportedOperationException("iterator() not supported for performance reasons");
    }

    @Override
    public Object[] toArray() {
        throw new UnsupportedOperationException("toArray() not supported for performance reasons");
    }

    @Override
    public <T> T[] toArray(T[] a) {
        throw new UnsupportedOperationException("toArray() not supported for performance reasons");
    }

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException("remove(Object) not supported for performance reasons");
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        throw new UnsupportedOperationException("containsAll() not supported for performance reasons");
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
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
        throw new UnsupportedOperationException("removeAll() not supported for performance reasons");
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException("retainAll() not supported for performance reasons");
    }

    /**
     * Checks if a number is a power of 2.
     *
     * @param n the number to check
     * @return true if n is a power of 2, false otherwise
     */
    private static boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }
}
