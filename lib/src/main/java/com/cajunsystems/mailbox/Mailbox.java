package com.cajunsystems.mailbox;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * Abstraction for actor mailbox operations.
 * This interface decouples the core actor system from specific queue implementations,
 * allowing pluggable high-performance mailbox strategies.
 *
 * @param <T> The type of messages stored in the mailbox
 */
public interface Mailbox<T> {

    /**
     * Inserts the specified message into this mailbox if it is possible to do
     * so immediately without exceeding capacity, returning true upon success
     * and false if the mailbox is full.
     *
     * @param message the message to add
     * @return true if the message was added, false otherwise
     */
    boolean offer(T message);

    /**
     * Inserts the specified message into this mailbox, waiting up to the
     * specified wait time if necessary for space to become available.
     *
     * @param message the message to add
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return true if successful, false if the timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    boolean offer(T message, long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Inserts the specified message into this mailbox, waiting if necessary
     * for space to become available.
     *
     * @param message the message to add
     * @throws InterruptedException if interrupted while waiting
     */
    void put(T message) throws InterruptedException;

    /**
     * Retrieves and removes the head of this mailbox, or returns null if empty.
     *
     * @return the head of this mailbox, or null if empty
     */
    T poll();

    /**
     * Retrieves and removes the head of this mailbox, waiting up to the
     * specified wait time if necessary for a message to become available.
     *
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return the head of this mailbox, or null if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    T poll(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Retrieves and removes the head of this mailbox, waiting if necessary
     * until a message becomes available.
     *
     * @return the head of this mailbox
     * @throws InterruptedException if interrupted while waiting
     */
    T take() throws InterruptedException;

    /**
     * Removes all available messages from this mailbox and adds them to the
     * given collection, up to maxElements.
     *
     * @param collection the collection to transfer messages into
     * @param maxElements the maximum number of messages to transfer
     * @return the number of messages transferred
     */
    int drainTo(Collection<? super T> collection, int maxElements);

    /**
     * Returns the number of messages in this mailbox.
     *
     * @return the number of messages
     */
    int size();

    /**
     * Returns true if this mailbox contains no messages.
     *
     * @return true if empty
     */
    boolean isEmpty();

    /**
     * Returns the number of additional messages this mailbox can accept
     * without blocking, or Integer.MAX_VALUE if unbounded.
     *
     * @return the remaining capacity
     */
    int remainingCapacity();

    /**
     * Removes all messages from this mailbox.
     */
    void clear();

    /**
     * Returns the total capacity of this mailbox (size + remaining capacity).
     * Returns Integer.MAX_VALUE if unbounded.
     *
     * @return the total capacity
     */
    default int capacity() {
        int size = size();
        int remaining = remainingCapacity();
        if (remaining == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return size + remaining;
    }
}
