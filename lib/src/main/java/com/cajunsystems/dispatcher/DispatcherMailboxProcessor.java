package com.cajunsystems.dispatcher;

import com.cajunsystems.ActorLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * DispatcherMailboxProcessor is a drop-in replacement for MailboxProcessor
 * that uses dispatcher-based execution instead of a dedicated thread per actor.
 * 
 * Key differences from MailboxProcessor:
 * - No dedicated thread - actors share a dispatcher thread pool
 * - Uses DispatcherMailbox with coalesced scheduling
 * - Batch processing via ActorRunner
 * - Better scalability for large numbers of actors
 * 
 * @param <T> The type of messages in the mailbox
 */
public class DispatcherMailboxProcessor<T> {
    
    private static final Logger logger = LoggerFactory.getLogger(DispatcherMailboxProcessor.class);
    
    private final String actorId;
    private final DispatcherMailbox<T> mailbox;
    private final ActorRunner<T> runner;
    private final ActorLifecycle<T> lifecycle;
    private final AtomicReference<Runnable> scheduleRef;
    
    private volatile boolean running = false;
    
    /**
     * Creates a new DispatcherMailboxProcessor.
     * 
     * @param actorId The ID of the actor for logging
     * @param mailboxType The type of mailbox to use (DISPATCHER_CBQ or DISPATCHER_MPSC)
     * @param capacity The mailbox capacity
     * @param overflowStrategy How to handle overflow (BLOCK or DROP)
     * @param throughput Maximum messages to process per activation
     * @param exceptionHandler Handler for message processing errors
     * @param lifecycle Lifecycle callbacks (preStart/receive/postStop)
     * @param dispatcher The dispatcher for scheduling actor runs
     */
    public DispatcherMailboxProcessor(
            String actorId,
            MailboxType mailboxType,
            int capacity,
            OverflowStrategy overflowStrategy,
            int throughput,
            BiConsumer<T, Throwable> exceptionHandler,
            ActorLifecycle<T> lifecycle,
            Dispatcher dispatcher) {
        
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        
        // Create schedule reference for wiring
        this.scheduleRef = new AtomicReference<>();
        
        // Create mailbox with schedule action that delegates to scheduleRef
        if (mailboxType == MailboxType.DISPATCHER_CBQ) {
            this.mailbox = DispatcherMailbox.createWithConcurrentLinkedQueue(
                capacity, 
                overflowStrategy,
                () -> {
                    Runnable r = scheduleRef.get();
                    if (r != null && running) {
                        dispatcher.schedule(r);
                    }
                },
                actorId
            );
        } else if (mailboxType == MailboxType.DISPATCHER_MPSC) {
            this.mailbox = DispatcherMailbox.createWithMpscArrayQueue(
                capacity,
                overflowStrategy,
                () -> {
                    Runnable r = scheduleRef.get();
                    if (r != null && running) {
                        dispatcher.schedule(r);
                    }
                },
                actorId
            );
        } else {
            throw new IllegalArgumentException("MailboxType must be DISPATCHER_CBQ or DISPATCHER_MPSC, got: " + mailboxType);
        }
        
        // Create actor runner
        this.runner = new ActorRunner<>(
            actorId,
            mailbox,
            lifecycle,
            exceptionHandler,
            dispatcher,
            throughput
        );
        
        // Wire runner into scheduleRef
        scheduleRef.set(runner);
        
        logger.debug("Created DispatcherMailboxProcessor for actor {} with type {} and throughput {}", 
                    actorId, mailboxType, throughput);
    }
    
    /**
     * Starts the mailbox processor.
     * Invokes preStart lifecycle hook.
     */
    public void start() {
        if (running) {
            logger.debug("Actor {} dispatcher mailbox already running", actorId);
            return;
        }
        running = true;
        logger.info("Starting actor {} dispatcher mailbox", actorId);
        
        // Call preStart lifecycle hook
        try {
            lifecycle.preStart();
        } catch (Throwable t) {
            logger.error("Actor {} preStart failed", actorId, t);
        }
    }
    
    /**
     * Stops the mailbox processor.
     * Invokes postStop lifecycle hook.
     */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        logger.debug("Stopping actor {} dispatcher mailbox", actorId);
        
        // Clear scheduleRef to prevent further scheduling
        scheduleRef.set(null);
        
        // Call postStop lifecycle hook
        try {
            lifecycle.postStop();
        } catch (Throwable t) {
            logger.error("Actor {} postStop failed", actorId, t);
        }
    }
    
    /**
     * Delivers a message to the mailbox.
     * 
     * @param message The message to enqueue
     */
    public void tell(T message) {
        if (!running) {
            logger.warn("Attempted to send message to stopped actor {}", actorId);
            return;
        }
        mailbox.enqueue(message);
    }
    
    /**
     * Returns true if the mailbox processor is running.
     */
    public boolean isRunning() {
        return running;
    }
    
    /**
     * Gets the current number of messages in the mailbox.
     */
    public int getCurrentSize() {
        return mailbox.size();
    }
    
    /**
     * Gets the remaining capacity of the mailbox.
     */
    public int getRemainingCapacity() {
        return mailbox.getCapacity() - mailbox.size();
    }
    
    /**
     * Drops the oldest message from the mailbox (not supported for dispatcher mailboxes).
     * Dispatcher mailboxes handle overflow via their configured OverflowStrategy.
     * 
     * @return false always (not supported)
     */
    public boolean dropOldestMessage() {
        logger.warn("dropOldestMessage() not supported for dispatcher mailboxes. Use OverflowStrategy instead.");
        return false;
    }
    
    /**
     * Gets the mailbox instance (for advanced use cases).
     * 
     * @return The dispatcher mailbox
     */
    public DispatcherMailbox<T> getMailbox() {
        return mailbox;
    }
    
    /**
     * Gets the actor runner instance (for advanced use cases).
     * 
     * @return The actor runner
     */
    public ActorRunner<T> getRunner() {
        return runner;
    }
}
