package com.cajunsystems;

import com.cajunsystems.config.ThreadPoolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Encapsulates mailbox polling and message dispatch for an actor.
 * Handles batching, interruption, and exception routing.
 *
 * @param <T> The type of messages in the mailbox
 */
public class MailboxProcessor<T> {
    private static final Logger logger = LoggerFactory.getLogger(Actor.class);

    private final String actorId;
    private final BlockingQueue<T> mailbox;
    private final int batchSize;
    private final List<T> batchBuffer;
    private final BiConsumer<T, Throwable> exceptionHandler;
    private final ActorLifecycle<T> lifecycle;
    private final ThreadPoolFactory threadPoolFactory;

    private volatile boolean running = false;
    private volatile Thread thread;

    /**
     * Creates a new mailbox processor.
     *
     * @param actorId          The ID of the actor for logging
     * @param mailbox          The queue to poll messages from
     * @param batchSize        Number of messages to process per batch
     * @param exceptionHandler Handler to route message processing errors
     * @param lifecycle        Lifecycle hooks (preStart/postStop)
     */
    public MailboxProcessor(
            String actorId,
            BlockingQueue<T> mailbox,
            int batchSize,
            BiConsumer<T, Throwable> exceptionHandler,
            ActorLifecycle<T> lifecycle) {
        this(actorId, mailbox, batchSize, exceptionHandler, lifecycle, null);
    }
    
    /**
     * Creates a new mailbox processor with a thread pool factory.
     *
     * @param actorId          The ID of the actor for logging
     * @param mailbox          The queue to poll messages from
     * @param batchSize        Number of messages to process per batch
     * @param exceptionHandler Handler to route message processing errors
     * @param lifecycle        Lifecycle hooks (preStart/postStop)
     * @param threadPoolFactory The thread pool factory, or null to use default virtual threads
     */
    public MailboxProcessor(
            String actorId,
            BlockingQueue<T> mailbox,
            int batchSize,
            BiConsumer<T, Throwable> exceptionHandler,
            ActorLifecycle<T> lifecycle,
            ThreadPoolFactory threadPoolFactory) {
        this.actorId = actorId;
        this.mailbox = mailbox;
        this.batchSize = batchSize;
        this.batchBuffer = new ArrayList<>(batchSize);
        this.exceptionHandler = exceptionHandler;
        this.lifecycle = lifecycle;
        this.threadPoolFactory = threadPoolFactory;
    }

    /**
     * Starts mailbox polling using the configured thread factory or virtual threads.
     */
    public void start() {
        if (running) {
            logger.debug("Actor {} mailbox already running", actorId);
            return;
        }
        running = true;
        logger.info("Starting actor {} mailbox", actorId);
        lifecycle.preStart();
        
        if (threadPoolFactory != null) {
            // Use the configured thread pool factory to create a thread
            thread = threadPoolFactory.createThreadFactory(actorId).newThread(this::processMailboxLoop);
            thread.start();
        } else {
            // Fall back to virtual threads (default behavior)
            thread = Thread.ofVirtual()
                    .name("actor-" + actorId)
                    .start(this::processMailboxLoop);
        }
    }

    /**
     * Stops mailbox polling and drains remaining messages.
     */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        logger.debug("Stopping actor {} mailbox", actorId);
        mailbox.clear();
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(TimeUnit.SECONDS.toMillis(1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        lifecycle.postStop();
    }

    /**
     * Delivers a message to the mailbox.
     *
     * @param message The message to enqueue
     */
    public void tell(T message) {
        mailbox.offer(message);
    }

    /**
     * Returns true if the mailbox processor is running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Gets the current number of messages in the mailbox.
     *
     * @return The current size of the mailbox
     */
    public int getCurrentSize() {
        return mailbox.size();
    }

    /**
     * Gets the remaining capacity of the mailbox.
     *
     * @return The remaining capacity
     */
    public int getRemainingCapacity() {
        return mailbox.remainingCapacity();
    }

    /**
     * Drops the oldest message from the mailbox to make room when using DROP_OLDEST strategy.
     *
     * @return true if a message was dropped, false otherwise
     */
    public boolean dropOldestMessage() {
        if (mailbox.isEmpty()) {
            return false;
        }
        try {
            T removed = mailbox.poll();
            if (removed != null) {
                logger.debug("Actor {} dropped oldest message to make room", actorId);
                return true;
            }
        } catch (Exception e) {
            logger.error("Error dropping oldest message in actor {}: {}", actorId, e.getMessage(), e);
        }
        return false;
    }

    private void processMailboxLoop() {
        while (running) {
            try {
                batchBuffer.clear();
                T first = mailbox.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) {
                    // No messages available, yield to prevent busy waiting
                    Thread.yield();
                    continue;
                }
                batchBuffer.add(first);
                if (batchSize > 1) {
                    mailbox.drainTo(batchBuffer, batchSize - 1);
                }
                for (T msg : batchBuffer) {
                    if (!running) break;
                    try {
                        lifecycle.receive(msg);
                    } catch (Throwable e) {
                        logger.error("Actor {} error processing message: {}", actorId, msg, e);
                        exceptionHandler.accept(msg, e);
                    }
                }
            } catch (InterruptedException e) {
                logger.debug("Actor {} mailbox interrupted", actorId);
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
