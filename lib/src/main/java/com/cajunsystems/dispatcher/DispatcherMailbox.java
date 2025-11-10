package com.cajunsystems.dispatcher;

import org.jctools.queues.MpscArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DispatcherMailbox wraps either a ConcurrentLinkedQueue or MpscArrayQueue
 * and implements the coalesced scheduling pattern for dispatcher-based actors.
 * 
 * Key features:
 * - Coalesced scheduling: Only schedules actor when transitioning from empty to non-empty
 * - Configurable overflow strategies: BLOCK or DROP
 * - Support for both non-blocking (CBQ) and lock-free (MPSC) queues
 * 
 * @param <T> The type of messages in the mailbox
 */
public final class DispatcherMailbox<T> {
    
    private static final Logger logger = LoggerFactory.getLogger(DispatcherMailbox.class);
    
    private final Object queue; // Either ConcurrentLinkedQueue<T> or MpscArrayQueue<T>
    private final MailboxType mailboxType;
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final int capacity;
    private final OverflowStrategy overflowStrategy;
    private final Runnable scheduleAction;
    private final String actorId;
    
    /**
     * Creates a dispatcher mailbox with ConcurrentLinkedQueue.
     * 
     * @param capacity The maximum capacity of the mailbox (for monitoring, CBQ is unbounded)
     * @param overflowStrategy How to handle overflow (BLOCK or DROP)
     * @param scheduleAction The action to invoke when scheduling the actor
     * @param actorId The actor ID for logging
     */
    public static <T> DispatcherMailbox<T> createWithConcurrentLinkedQueue(
            int capacity,
            OverflowStrategy overflowStrategy,
            Runnable scheduleAction,
            String actorId) {
        ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();
        return new DispatcherMailbox<>(queue, MailboxType.DISPATCHER_CBQ, capacity, overflowStrategy, scheduleAction, actorId);
    }
    
    /**
     * Creates a dispatcher mailbox with MpscArrayQueue (lock-free).
     * 
     * @param capacity The maximum capacity of the mailbox (must be power of 2)
     * @param overflowStrategy How to handle overflow (BLOCK or DROP)
     * @param scheduleAction The action to invoke when scheduling the actor
     * @param actorId The actor ID for logging
     */
    public static <T> DispatcherMailbox<T> createWithMpscArrayQueue(
            int capacity,
            OverflowStrategy overflowStrategy,
            Runnable scheduleAction,
            String actorId) {
        // Ensure capacity is power of 2 for MpscArrayQueue
        if (Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("Capacity must be a power of 2 for MPSC queue, got: " + capacity);
        }
        MpscArrayQueue<T> queue = new MpscArrayQueue<>(capacity);
        return new DispatcherMailbox<>(queue, MailboxType.DISPATCHER_MPSC, capacity, overflowStrategy, scheduleAction, actorId);
    }
    
    private DispatcherMailbox(
            Object queue,
            MailboxType mailboxType,
            int capacity,
            OverflowStrategy overflowStrategy,
            Runnable scheduleAction,
            String actorId) {
        this.queue = queue;
        this.mailboxType = mailboxType;
        this.capacity = capacity;
        this.overflowStrategy = Objects.requireNonNull(overflowStrategy, "overflowStrategy");
        this.scheduleAction = Objects.requireNonNull(scheduleAction, "scheduleAction");
        this.actorId = actorId;
    }
    
    /**
     * Enqueues a message to the mailbox.
     * Non-blocking hot-path with coalesced scheduling.
     * 
     * @param message The message to enqueue
     * @return true if accepted, false if dropped (only when overflow strategy is DROP)
     */
    public boolean enqueue(T message) {
        Objects.requireNonNull(message, "message cannot be null");
        
        boolean offered = offer(message);
        
        if (!offered) {
            if (overflowStrategy == OverflowStrategy.DROP) {
                logger.debug("Actor {} mailbox full, dropping message due to DROP strategy", actorId);
                return false;
            } else {
                // BLOCK strategy: spin-wait with adaptive sleep
                offered = blockingOffer(message);
                if (!offered) {
                    return false; // Interrupted or failed
                }
            }
        }
        
        // Coalesced scheduling: only schedule if transitioning from not-scheduled to scheduled
        if (scheduled.compareAndSet(false, true)) {
            try {
                scheduleAction.run();
            } catch (Throwable t) {
                // Protect enqueue callers from scheduler exceptions
                logger.error("Actor {} schedule action failed", actorId, t);
                scheduled.set(false); // Reset scheduled flag on failure
            }
        }
        
        return true;
    }
    
    /**
     * Attempts to offer a message to the queue (non-blocking).
     */
    @SuppressWarnings("unchecked")
    private boolean offer(T message) {
        if (mailboxType == MailboxType.DISPATCHER_CBQ) {
            return ((ConcurrentLinkedQueue<T>) queue).offer(message);
        } else {
            return ((MpscArrayQueue<T>) queue).offer(message);
        }
    }
    
    /**
     * Blocking offer with spin-wait and adaptive sleep.
     */
    @SuppressWarnings("unchecked")
    private boolean blockingOffer(T message) {
        int spins = 0;
        while (true) {
            if (offer(message)) {
                return true;
            }
            
            Thread.onSpinWait();
            spins++;
            
            if (spins > 128) {
                // After many spins, use a tiny sleep to avoid busy-waiting
                try {
                    Thread.sleep(0, 200); // 200 nanoseconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.debug("Actor {} interrupted during blocking offer", actorId);
                    return false;
                }
            }
            
            // Safety valve: if spinning too long, yield
            if (spins > 1000) {
                Thread.yield();
                spins = 0;
            }
        }
    }
    
    /**
     * Polls a message from the mailbox (consumer side).
     * 
     * @return The next message, or null if empty
     */
    @SuppressWarnings("unchecked")
    public T poll() {
        if (mailboxType == MailboxType.DISPATCHER_CBQ) {
            return ((ConcurrentLinkedQueue<T>) queue).poll();
        } else {
            return ((MpscArrayQueue<T>) queue).poll();
        }
    }
    
    /**
     * Checks if the mailbox is empty.
     * 
     * @return true if empty, false otherwise
     */
    @SuppressWarnings("unchecked")
    public boolean isEmpty() {
        if (mailboxType == MailboxType.DISPATCHER_CBQ) {
            return ((ConcurrentLinkedQueue<T>) queue).isEmpty();
        } else {
            return ((MpscArrayQueue<T>) queue).isEmpty();
        }
    }
    
    /**
     * Gets the approximate size of the mailbox.
     * Note: For MPSC queues, this is an estimate.
     * 
     * @return The approximate number of messages
     */
    @SuppressWarnings("unchecked")
    public int size() {
        if (mailboxType == MailboxType.DISPATCHER_CBQ) {
            return ((ConcurrentLinkedQueue<T>) queue).size();
        } else {
            return ((MpscArrayQueue<T>) queue).size();
        }
    }
    
    /**
     * Attempts to clear the scheduled flag after processing a batch.
     * Returns true if successfully cleared.
     * 
     * @return true if cleared, false if already false
     */
    public boolean tryClearScheduled() {
        return scheduled.compareAndSet(true, false);
    }
    
    /**
     * Gets the scheduled flag for visibility.
     * 
     * @return The scheduled atomic boolean
     */
    public AtomicBoolean getScheduled() {
        return scheduled;
    }
    
    /**
     * Gets the capacity of the mailbox.
     * 
     * @return The maximum capacity
     */
    public int getCapacity() {
        return capacity;
    }
    
    /**
     * Gets the mailbox type.
     * 
     * @return The mailbox type (DISPATCHER_CBQ or DISPATCHER_MPSC)
     */
    public MailboxType getMailboxType() {
        return mailboxType;
    }
}
