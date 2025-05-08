package systems.cajun.backpressure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// import systems.cajun.Actor; // Unused import removed
import systems.cajun.ActorSystem;
import systems.cajun.StatefulActor;
import systems.cajun.persistence.BatchedMessageJournal;
import systems.cajun.persistence.SnapshotStore;

import java.io.Serializable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * An extension of StatefulActor that adds backpressure awareness and enhanced error recovery.
 * This implementation monitors the message queue size and processing times to apply
 * backpressure when the system is under high load.
 *
 * @param <State> The type of the actor's state
 * @param <Message> The type of messages the actor can process
 */
public abstract class BackpressureAwareStatefulActor<State extends Serializable, Message extends Serializable> extends StatefulActor<State, Message> {
    private static final Logger logger = LoggerFactory.getLogger(BackpressureAwareStatefulActor.class);
    
    // Backpressure strategy
    private final BackpressureStrategy backpressureStrategy;
    
    // Retry strategy for error recovery
    private final RetryStrategy retryStrategy;
    
    // Queue monitoring
    private final ConcurrentLinkedQueue<Long> processingTimes = new ConcurrentLinkedQueue<>();
    private final AtomicInteger estimatedQueueSize = new AtomicInteger(0);
    private final int maxProcessingTimeSamples = 100;
    
    // Error hook for custom error handling
    private Consumer<Throwable> errorHook = ex -> {};
    
    /**
     * Creates a new BackpressureAwareStatefulActor with default strategies.
     *
     * @param system The actor system
     * @param initialState The initial state of the actor
     */
    public BackpressureAwareStatefulActor(ActorSystem system, State initialState) {
        super(system, initialState);
        this.backpressureStrategy = new AdaptiveBackpressureStrategy();
        this.retryStrategy = new RetryStrategy();
    }
    
    /**
     * Creates a new BackpressureAwareStatefulActor with custom strategies.
     *
     * @param system The actor system
     * @param initialState The initial state of the actor
     * @param backpressureStrategy The backpressure strategy to use
     * @param retryStrategy The retry strategy to use
     */
    public BackpressureAwareStatefulActor(
            ActorSystem system,
            State initialState,
            BackpressureStrategy backpressureStrategy,
            RetryStrategy retryStrategy) {
        super(system, initialState);
        this.backpressureStrategy = backpressureStrategy;
        this.retryStrategy = retryStrategy;
    }
    
    /**
     * Creates a new BackpressureAwareStatefulActor with custom ID and strategies.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     * @param backpressureStrategy The backpressure strategy to use
     * @param retryStrategy The retry strategy to use
     */
    public BackpressureAwareStatefulActor(
            ActorSystem system,
            String actorId,
            State initialState,
            BackpressureStrategy backpressureStrategy,
            RetryStrategy retryStrategy) {
        super(system, actorId, initialState);
        this.backpressureStrategy = backpressureStrategy;
        this.retryStrategy = retryStrategy;
    }
    
    /**
     * Creates a new BackpressureAwareStatefulActor with full configuration.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     * @param messageJournal The message journal to use
     * @param snapshotStore The snapshot store to use
     * @param backpressureStrategy The backpressure strategy to use
     * @param retryStrategy The retry strategy to use
     */
    public BackpressureAwareStatefulActor(
            ActorSystem system,
            String actorId,
            State initialState,
            BatchedMessageJournal<Message> messageJournal,
            SnapshotStore<State> snapshotStore,
            BackpressureStrategy backpressureStrategy,
            RetryStrategy retryStrategy) {
        super(system, actorId, initialState, messageJournal, snapshotStore);
        this.backpressureStrategy = backpressureStrategy;
        this.retryStrategy = retryStrategy;
    }
    
    /**
     * Intercepts messages before they are processed by the StatefulActor.
     * This method is called by the Actor framework before the message is passed to the StatefulActor's receive method.
     * 
     * @param message The message to process
     */
    @Override
    public void tell(Message message) {
        // Increment estimated queue size when message arrives
        estimatedQueueSize.incrementAndGet();
        
        try {
            // Check if we should apply backpressure
            long avgProcessingTime = calculateAverageProcessingTime();
            int persistenceQueueSize = getPersistenceExecutor() instanceof java.util.concurrent.ThreadPoolExecutor ?
                    ((java.util.concurrent.ThreadPoolExecutor) getPersistenceExecutor()).getQueue().size() : 0;
            
            if (backpressureStrategy.shouldApplyBackpressure(
                    estimatedQueueSize.get(), avgProcessingTime, persistenceQueueSize)) {
                
                // Handle message under backpressure
                boolean handled = backpressureStrategy.handleMessageUnderBackpressure(message, m -> {
                    // Normal processing path
                    processMessageWithRetry(m);
                });
                
                if (!handled) {
                    // Message was rejected, decrement queue size
                    estimatedQueueSize.decrementAndGet();
                    logger.warn("Message rejected due to backpressure: {}", message);
                }
            } else {
                // Normal processing path without backpressure
                processMessageWithRetry(message);
            }
        } catch (Exception e) {
            // Handle unexpected exceptions in the backpressure logic itself
            logger.error("Error in backpressure handling for message: {}", message, e);
            estimatedQueueSize.decrementAndGet();
            errorHook.accept(e);
        }
    }
    
    /**
     * Processes a message with retry logic.
     *
     * @param message The message to process
     */
    private void processMessageWithRetry(Message message) {
        long startTime = System.nanoTime();
        
        // Use the retry strategy to handle transient failures
        retryStrategy.executeWithRetry(
            () -> {
                CompletableFuture<Void> future = new CompletableFuture<>();
                
                // Call the parent class's tell method to process the message normally
                try {
                    // Use super.tell to invoke the original StatefulActor's message handling
                    super.tell(message);
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
                
                return future;
            },
            getPersistenceExecutor()
        ).whenComplete((result, ex) -> {
            // Record processing time for this message
            long processingTime = System.nanoTime() - startTime;
            recordProcessingTime(processingTime);
            
            // Decrement estimated queue size when processing completes
            estimatedQueueSize.decrementAndGet();
            
            if (ex != null) {
                // Processing failed even after retries
                logger.error("Failed to process message after retries: {}", message, ex);
                backpressureStrategy.onMessageProcessingFailed(ex);
                errorHook.accept(ex);
            } else {
                // Processing succeeded
                backpressureStrategy.onMessageProcessed(processingTime);
            }
        });
    }
    
    /**
     * Records the processing time for a message.
     *
     * @param processingTimeNanos Processing time in nanoseconds
     */
    private void recordProcessingTime(long processingTimeNanos) {
        processingTimes.add(processingTimeNanos);
        
        // Keep the queue at a reasonable size
        while (processingTimes.size() > maxProcessingTimeSamples) {
            processingTimes.poll();
        }
    }
    
    /**
     * Calculates the average processing time from recent samples.
     *
     * @return Average processing time in nanoseconds
     */
    private long calculateAverageProcessingTime() {
        if (processingTimes.isEmpty()) {
            return 0;
        }
        
        long sum = 0;
        int count = 0;
        
        for (Long time : processingTimes) {
            sum += time;
            count++;
        }
        
        return count > 0 ? sum / count : 0;
    }
    
    /**
     * Sets a custom error hook that will be called when an error occurs during message processing.
     *
     * @param errorHook The error hook to call
     * @return This actor instance for method chaining
     */
    public BackpressureAwareStatefulActor<State, Message> withErrorHook(Consumer<Throwable> errorHook) {
        this.errorHook = errorHook;
        return this;
    }
    
    /**
     * Gets the current backpressure metrics.
     *
     * @return Current backpressure metrics
     */
    public BackpressureMetrics getBackpressureMetrics() {
        return backpressureStrategy.getMetrics();
    }
    
    /**
     * Gets the current backpressure level.
     *
     * @return Current backpressure level (0.0-1.0)
     */
    public double getCurrentBackpressureLevel() {
        return backpressureStrategy.getCurrentBackpressureLevel();
    }
    
    /**
     * Gets the estimated queue size.
     *
     * @return Estimated queue size
     */
    public int getEstimatedQueueSize() {
        return estimatedQueueSize.get();
    }
    
    /**
     * Executes an operation with retry logic.
     * This is useful for operations that might fail due to transient issues.
     *
     * @param operation The operation to execute
     * @param <T> The return type of the operation
     * @return A CompletableFuture that completes with the result of the operation
     */
    protected <T> CompletableFuture<T> executeWithRetry(Supplier<CompletableFuture<T>> operation) {
        return retryStrategy.executeWithRetry(operation, getPersistenceExecutor());
    }
}
