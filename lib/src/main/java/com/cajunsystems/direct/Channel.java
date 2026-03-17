package com.cajunsystems.direct;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A typed channel providing direct-style blocking send/receive methods
 * that suspend virtual threads efficiently.
 *
 * <p>Inspired by Ox for Scala, this channel provides a simple, direct-style API
 * for communicating between virtual threads.</p>
 *
 * @param <T> the type of elements in this channel
 */
public class Channel<T> implements Iterable<T> {

    private final BlockingQueue<T> queue;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private Channel(BlockingQueue<T> queue) {
        this.queue = queue;
    }

    /**
     * Creates an unbounded channel.
     *
     * @param <T> the type of elements in the channel
     * @return a new unbounded channel
     */
    public static <T> Channel<T> create() {
        return new Channel<>(new LinkedBlockingQueue<>());
    }

    /**
     * Creates a bounded channel with the specified capacity.
     *
     * @param <T>      the type of elements in the channel
     * @param capacity the maximum number of elements the channel can hold
     * @return a new bounded channel
     * @throws IllegalArgumentException if capacity is less than 1
     */
    public static <T> Channel<T> create(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Channel capacity must be at least 1, got: " + capacity);
        }
        return new Channel<>(new ArrayBlockingQueue<>(capacity));
    }

    /**
     * Sends a value to the channel, blocking until space is available.
     *
     * <p>When used with virtual threads, this method suspends the virtual thread
     * efficiently rather than blocking a platform thread.</p>
     *
     * @param value the value to send
     * @throws ChannelClosedException if the channel has been closed
     */
    public void send(T value) {
        if (closed.get()) {
            throw new ChannelClosedException("Cannot send to a closed channel");
        }
        try {
            // Use a polling loop so we can detect channel closure while waiting
            while (!closed.get()) {
                if (queue.offer(value, 100, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
            throw new ChannelClosedException("Cannot send to a closed channel");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelClosedException("Send interrupted", e);
        }
    }

    /**
     * Receives a value from the channel, blocking until an element is available.
     *
     * <p>When used with virtual threads, this method suspends the virtual thread
     * efficiently rather than blocking a platform thread.</p>
     *
     * @return the received value
     * @throws ChannelClosedException if the channel has been closed and is empty
     */
    public T receive() {
        try {
            while (true) {
                T value = queue.poll(100, TimeUnit.MILLISECONDS);
                if (value != null) {
                    return value;
                }
                if (closed.get()) {
                    // Try one more time to drain any remaining element
                    value = queue.poll();
                    if (value != null) {
                        return value;
                    }
                    throw new ChannelClosedException("Cannot receive from a closed and empty channel");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelClosedException("Receive interrupted", e);
        }
    }

    /**
     * Attempts to send a value to the channel without blocking.
     *
     * @param value the value to send
     * @return {@code true} if the value was sent successfully, {@code false} if the channel
     *         is full or closed
     */
    public boolean trySend(T value) {
        if (closed.get()) {
            return false;
        }
        return queue.offer(value);
    }

    /**
     * Attempts to receive a value from the channel without blocking.
     *
     * @return an {@link Optional} containing the received value, or empty if
     *         the channel is empty
     */
    public Optional<T> tryReceive() {
        T value = queue.poll();
        return Optional.ofNullable(value);
    }

    /**
     * Closes the channel. After closing, no more elements can be sent.
     * Elements already in the channel can still be received.
     *
     * <p>Calling close on an already closed channel has no effect.</p>
     */
    public void close() {
        closed.set(true);
    }

    /**
     * Signals that no more elements will be sent to this channel.
     * This is an alias for {@link #close()}.
     */
    public void done() {
        close();
    }

    /**
     * Returns whether this channel has been closed.
     *
     * @return {@code true} if the channel is closed
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Returns an iterator over the elements in this channel.
     * The iterator blocks on {@code next()} until an element is available.
     * The iterator terminates when the channel is closed and all remaining
     * elements have been consumed.
     *
     * <p>This enables using channels in for-each loops:</p>
     * <pre>{@code
     * for (T value : channel) {
     *     // process value
     * }
     * }</pre>
     *
     * @return an iterator over the channel's elements
     */
    @Override
    public Iterator<T> iterator() {
        return new ChannelIterator();
    }

    private class ChannelIterator implements Iterator<T> {

        private T nextValue;
        private boolean hasNextValue;
        private boolean exhausted;

        @Override
        public boolean hasNext() {
            if (exhausted) {
                return false;
            }
            if (hasNextValue) {
                return true;
            }
            try {
                while (true) {
                    T value = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (value != null) {
                        nextValue = value;
                        hasNextValue = true;
                        return true;
                    }
                    if (closed.get()) {
                        // Drain any remaining element
                        value = queue.poll();
                        if (value != null) {
                            nextValue = value;
                            hasNextValue = true;
                            return true;
                        }
                        exhausted = true;
                        return false;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exhausted = true;
                return false;
            }
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException("No more elements in channel");
            }
            T value = nextValue;
            nextValue = null;
            hasNextValue = false;
            return value;
        }
    }
}