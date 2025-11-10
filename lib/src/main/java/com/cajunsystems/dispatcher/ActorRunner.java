package com.cajunsystems.dispatcher;

import com.cajunsystems.ActorLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * ActorRunner processes messages from a DispatcherMailbox in batches.
 * 
 * Key features:
 * - Batch processing: Processes up to 'throughput' messages per activation
 * - Re-schedules itself if more messages remain after batch completion
 * - Exception handling: Routes errors to the actor's exception handler
 * - Coalesced execution: Uses the scheduled flag to avoid redundant scheduling
 * 
 * @param <T> The type of messages to process
 */
public final class ActorRunner<T> implements Runnable {
    
    private static final Logger logger = LoggerFactory.getLogger(ActorRunner.class);
    
    private final String actorId;
    private final DispatcherMailbox<T> mailbox;
    private final ActorLifecycle<T> lifecycle;
    private final BiConsumer<T, Throwable> exceptionHandler;
    private final Dispatcher dispatcher;
    private final int throughput;
    
    /**
     * Creates an ActorRunner.
     * 
     * @param actorId The ID of the actor for logging
     * @param mailbox The dispatcher mailbox to process messages from
     * @param lifecycle The actor lifecycle callbacks (receive, preStart, postStop)
     * @param exceptionHandler Handler for message processing errors
     * @param dispatcher The dispatcher for re-scheduling
     * @param throughput Maximum number of messages to process per activation
     */
    public ActorRunner(
            String actorId,
            DispatcherMailbox<T> mailbox,
            ActorLifecycle<T> lifecycle,
            BiConsumer<T, Throwable> exceptionHandler,
            Dispatcher dispatcher,
            int throughput) {
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.mailbox = Objects.requireNonNull(mailbox, "mailbox");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.exceptionHandler = Objects.requireNonNull(exceptionHandler, "exceptionHandler");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.throughput = Math.max(1, throughput);
    }
    
    /**
     * Processes up to 'throughput' messages from the mailbox.
     * After processing, clears the scheduled flag and re-schedules if more messages exist.
     */
    @Override
    public void run() {
        try {
            int processed = 0;
            T msg;
            
            // Process up to 'throughput' messages
            while (processed < throughput && (msg = mailbox.poll()) != null) {
                try {
                    lifecycle.receive(msg);
                    processed++;
                } catch (Throwable t) {
                    // Route errors to the actor's exception handler
                    logger.error("Actor {} error processing message: {}", actorId, msg, t);
                    try {
                        exceptionHandler.accept(msg, t);
                    } catch (Throwable handlerError) {
                        logger.error("Actor {} exception handler failed", actorId, handlerError);
                    }
                }
            }
            
            if (processed > 0) {
                logger.trace("Actor {} processed {} messages", actorId, processed);
            }
            
        } finally {
            // Critical section: clear scheduled flag and check for more messages
            // This ensures we don't miss messages that arrived during processing
            if (mailbox.tryClearScheduled()) {
                // Successfully cleared scheduled flag
                // Now check if more messages arrived
                if (!mailbox.isEmpty()) {
                    // More messages exist, re-schedule this runner
                    if (mailbox.getScheduled().compareAndSet(false, true)) {
                        dispatcher.schedule(this);
                    }
                }
            }
            // If tryClearScheduled() returned false, another thread already set it to true
            // and will handle scheduling, so we don't need to do anything
        }
    }
    
    /**
     * Gets the throughput (batch size) of this runner.
     * 
     * @return The maximum messages per activation
     */
    public int getThroughput() {
        return throughput;
    }
    
    /**
     * Gets the actor ID.
     * 
     * @return The actor ID
     */
    public String getActorId() {
        return actorId;
    }
}
