package com.cajunsystems;

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
    private final ActorLifecycle lifecycle;

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
            ActorLifecycle lifecycle) {
        this.actorId = actorId;
        this.mailbox = mailbox;
        this.batchSize = batchSize;
        this.batchBuffer = new ArrayList<>(batchSize);
        this.exceptionHandler = exceptionHandler;
        this.lifecycle = lifecycle;
    }

    /**
     * Starts mailbox polling on a virtual thread.
     */
    public void start() {
        if (running) {
            logger.debug("Actor {} mailbox already running", actorId);
            return;
        }
        running = true;
        logger.info("Starting actor {} mailbox", actorId);
        lifecycle.preStart();
        thread = Thread.ofVirtual()
                .name("actor-" + actorId)
                .start(this::processMailboxLoop);
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
                T first = mailbox.take();
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
