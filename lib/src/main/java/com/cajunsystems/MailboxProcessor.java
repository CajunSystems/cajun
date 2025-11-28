package com.cajunsystems;

import com.cajunsystems.mailbox.Mailbox;
import com.cajunsystems.config.ThreadPoolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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

    // Performance tuning: reduced from 100ms to 1ms for lower latency
    private static final long POLL_TIMEOUT_MS = 1;

    private final String actorId;
    private final Mailbox<T> mailbox;
    private final int batchSize;
    private final List<T> batchBuffer;
    private final BiConsumer<T, Throwable> exceptionHandler;
    private final ActorLifecycle<T> lifecycle;
    private final ThreadPoolFactory threadPoolFactory;

    private volatile boolean running = false;
    private volatile boolean restartRequested = false;
    private volatile Thread thread;
    private volatile CountDownLatch readyLatch;
    private Runnable restartCallback;

    /**
     * Creates a new mailbox processor.
     *
     * @param actorId          The ID of the actor for logging
     * @param mailbox          The mailbox to poll messages from
     * @param batchSize        Number of messages to process per batch
     * @param exceptionHandler Handler to route message processing errors
     * @param lifecycle        Lifecycle hooks (preStart/postStop)
     */
    public MailboxProcessor(
            String actorId,
            Mailbox<T> mailbox,
            int batchSize,
            BiConsumer<T, Throwable> exceptionHandler,
            ActorLifecycle<T> lifecycle) {
        this(actorId, mailbox, batchSize, exceptionHandler, lifecycle, null);
    }

    /**
     * Creates a new mailbox processor with a thread pool factory.
     *
     * @param actorId          The ID of the actor for logging
     * @param mailbox          The mailbox to poll messages from
     * @param batchSize        Number of messages to process per batch
     * @param exceptionHandler Handler to route message processing errors
     * @param lifecycle        Lifecycle hooks (preStart/postStop)
     * @param threadPoolFactory The thread pool factory, or null to use default virtual threads
     */
    public MailboxProcessor(
            String actorId,
            Mailbox<T> mailbox,
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
        this.restartCallback = null;
    }

    /**
     * Starts mailbox polling using the configured thread factory or virtual threads.
     * Blocks until the actor thread is actually running to avoid race conditions with the ask pattern.
     */
    public void start() {
        if (running) {
            logger.debug("Actor {} mailbox already running", actorId);
            return;
        }
        running = true;
        logger.info("Starting actor {} mailbox", actorId);
        lifecycle.preStart();
        
        // Create a latch to signal when the actor thread is actually running
        readyLatch = new CountDownLatch(1);

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

        // Wait for the actor thread to signal it's ready (with a timeout to avoid hanging)
        try {
            if (!readyLatch.await(5, TimeUnit.SECONDS)) {
                logger.warn("Actor {} did not start within timeout", actorId);
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for actor {} to start", actorId);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stops mailbox polling and drains remaining messages.
     */
    public void stop() {
        stop(true);
    }

    /**
     * Stops mailbox polling with optional mailbox clearing.
     *
     * @param clearMailbox If true, clears pending messages; if false, preserves them (for restart)
     */
    public void stop(boolean clearMailbox) {
        if (!running) {
            return;
        }
        running = false;
        logger.debug("Stopping actor {} mailbox (clearMailbox={})", actorId, clearMailbox);
        if (clearMailbox) {
            mailbox.clear();
        }
        // Only interrupt and join if we're NOT on the mailbox thread itself
        // (during restart, we're called from within the mailbox thread)
        if (thread != null && Thread.currentThread() != thread) {
            thread.interrupt();
            try {
                thread.join(TimeUnit.SECONDS.toMillis(1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
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
     * Requests a restart of the actor. The restart will happen after the current batch completes.
     * This avoids ConcurrentModificationException during batch processing.
     *
     * @param callback The callback to execute for the restart (typically stop + start)
     */
    public void requestRestart(Runnable callback) {
        this.restartCallback = callback;
        this.restartRequested = true;
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
        // Signal that the thread is now running and ready to process messages
        if (readyLatch != null) {
            readyLatch.countDown();
        }

        boolean shouldRestart = false;
        Runnable pendingRestart = null;

        while (running) {
            // Check if restart was requested before starting a new batch
            if (restartRequested) {
                shouldRestart = true;
                pendingRestart = restartCallback;
                restartRequested = false;
                restartCallback = null;
                running = false;
                break;
            }
            
            try {
                batchBuffer.clear();
                // Performance fix: reduced timeout from 100ms to 1ms for lower latency
                T first = mailbox.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    // No messages available - virtual threads park efficiently
                    // Removed Thread.yield() - unnecessary with virtual threads
                    continue;
                }
                batchBuffer.add(first);
                if (batchSize > 1) {
                    mailbox.drainTo(batchBuffer, batchSize - 1);
                }
                int processedCount = 0;
                for (T msg : batchBuffer) {
                    if (!running) break;
                    if (restartRequested) {
                        // Return unprocessed messages back to the front of the mailbox
                        for (int i = processedCount; i < batchBuffer.size(); i++) {
                            // Add back to front of mailbox to preserve message order
                            // Note: This is a best-effort approach - if mailbox is full, messages may be lost
                            mailbox.offer(batchBuffer.get(i));
                        }
                        break;
                    }
                    try {
                        lifecycle.receive(msg);
                        processedCount++;
                    } catch (Throwable e) {
                        logger.error("Actor {} error processing message: {}", actorId, msg, e);
                        exceptionHandler.accept(msg, e);
                        // Increment processedCount to avoid re-adding failed message to mailbox
                        // The Supervisor will handle reprocessing via shouldReprocess flag
                        processedCount++;
                    }
                }
            } catch (InterruptedException e) {
                logger.debug("Actor {} mailbox interrupted", actorId);
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        // Execute restart after loop exits cleanly
        if (shouldRestart && pendingRestart != null) {
            pendingRestart.run();
        }
    }
}
