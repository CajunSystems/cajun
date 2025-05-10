package systems.cajun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import systems.cajun.config.BackpressureConfig;

public abstract class Actor<Message> {

    private static final Logger logger = LoggerFactory.getLogger(Actor.class);
    private static final int DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_BATCH_SIZE = 10; // Default number of messages to process in a batch
    
    // Default values for backpressure configuration
    private static final int DEFAULT_INITIAL_CAPACITY = BackpressureConfig.DEFAULT_INITIAL_CAPACITY;
    private static final int DEFAULT_MAX_CAPACITY = BackpressureConfig.DEFAULT_MAX_CAPACITY;
    private static final int DEFAULT_MIN_CAPACITY = BackpressureConfig.DEFAULT_MIN_CAPACITY;
    private static final long DEFAULT_METRICS_UPDATE_INTERVAL_MS = BackpressureConfig.DEFAULT_METRICS_UPDATE_INTERVAL_MS;
    private static final float DEFAULT_GROWTH_FACTOR = BackpressureConfig.DEFAULT_GROWTH_FACTOR;
    private static final float DEFAULT_SHRINK_FACTOR = BackpressureConfig.DEFAULT_SHRINK_FACTOR;
    private static final float DEFAULT_HIGH_WATERMARK = BackpressureConfig.DEFAULT_HIGH_WATERMARK;
    private static final float DEFAULT_LOW_WATERMARK = BackpressureConfig.DEFAULT_LOW_WATERMARK;

    private final BlockingQueue<Message> mailbox;
    private volatile boolean isRunning;
    private final String actorId;
    private final ActorSystem system;
    private final Pid pid;
    private final ExecutorService executor;
    private SupervisionStrategy supervisionStrategy = SupervisionStrategy.RESUME;
    private Actor<?> parent;
    private final Map<String, Actor<?>> children = new ConcurrentHashMap<>();
    private int shutdownTimeoutSeconds;
    private int batchSize;
    private volatile Thread mailboxThread;

    // Reusable batch buffer for message processing
    private final List<Message> batchBuffer = new ArrayList<>(DEFAULT_BATCH_SIZE);
    
    // Backpressure support
    private final boolean backpressureEnabled;
    private final AtomicInteger currentSize = new AtomicInteger(0);
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong lastProcessingTimestamp = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong processingRate = new AtomicLong(0); // messages per second
    private final AtomicLong lastMetricsUpdateTime = new AtomicLong(System.currentTimeMillis());
    
    // Backpressure configuration (only used when backpressureEnabled is true)
    private final int maxCapacity;
    private final int minCapacity;
    private final float growthFactor;
    private final float shrinkFactor;
    private final float highWatermark;
    private final float lowWatermark;
    private final long metricsUpdateIntervalMs;
    
    // Backpressure callbacks
    private Consumer<BackpressureMetrics> backpressureCallback;
    private final BackpressureMetrics metrics = new BackpressureMetrics();

    public Actor(ActorSystem system) {
        this(system, UUID.randomUUID().toString());
    }

    public Actor(ActorSystem system, String actorId) {
        this(system, actorId, false, DEFAULT_INITIAL_CAPACITY, DEFAULT_MAX_CAPACITY);
    }
    
    /**
     * Creates a new Actor with optional backpressure support.
     *
     * @param system The actor system this actor belongs to
     * @param actorId The ID for this actor
     * @param enableBackpressure Whether to enable backpressure support
     */
    public Actor(ActorSystem system, String actorId, boolean enableBackpressure) {
        this(system, actorId, enableBackpressure, DEFAULT_INITIAL_CAPACITY, DEFAULT_MAX_CAPACITY);
    }
    
    /**
     * Creates a new Actor with backpressure support and custom mailbox capacities.
     *
     * @param system The actor system this actor belongs to
     * @param actorId The ID for this actor
     * @param enableBackpressure Whether to enable backpressure support
     * @param initialCapacity The initial capacity of the mailbox (only used if backpressure is enabled)
     * @param maxCapacity The maximum capacity the mailbox can grow to (only used if backpressure is enabled)
     */
    public Actor(ActorSystem system, String actorId, boolean enableBackpressure, int initialCapacity, int maxCapacity) {
        this.system = system;
        this.actorId = actorId;
        this.backpressureEnabled = enableBackpressure;
        
        // Initialize backpressure configuration from system or defaults
        BackpressureConfig config = system.getBackpressureConfig();
        if (config != null) {
            this.maxCapacity = maxCapacity;
            this.minCapacity = config.getMinCapacity();
            this.growthFactor = config.getGrowthFactor();
            this.shrinkFactor = config.getShrinkFactor();
            this.highWatermark = config.getHighWatermark();
            this.lowWatermark = config.getLowWatermark();
            this.metricsUpdateIntervalMs = config.getMetricsUpdateIntervalMs();
        } else {
            // Fallback to defaults if system config is not available
            this.maxCapacity = maxCapacity;
            this.minCapacity = DEFAULT_MIN_CAPACITY;
            this.growthFactor = DEFAULT_GROWTH_FACTOR;
            this.shrinkFactor = DEFAULT_SHRINK_FACTOR;
            this.highWatermark = DEFAULT_HIGH_WATERMARK;
            this.lowWatermark = DEFAULT_LOW_WATERMARK;
            this.metricsUpdateIntervalMs = DEFAULT_METRICS_UPDATE_INTERVAL_MS;
        }
        
        // Create appropriate mailbox based on backpressure setting
        if (backpressureEnabled) {
            this.mailbox = new ResizableBlockingQueue<>(initialCapacity, maxCapacity);
            logger.debug("Actor {} created with backpressure support (initial capacity: {}, max capacity: {})",
                    actorId, initialCapacity, maxCapacity);
        } else {
            this.mailbox = new LinkedBlockingQueue<>();
            logger.debug("Actor {} created with unbounded mailbox", actorId);
        }

        // Use configuration from the actor system
        if (system.getThreadPoolFactory() != null) {
            this.shutdownTimeoutSeconds = system.getThreadPoolFactory().getActorShutdownTimeoutSeconds();
            this.batchSize = system.getThreadPoolFactory().getActorBatchSize();

            // Use a shared executor from the actor system if available, otherwise create a dedicated one
            if (system.getSharedExecutor() != null) {
                this.executor = system.getSharedExecutor();
            } else {
                this.executor = system.getThreadPoolFactory().createExecutorService("actor-" + actorId);
            }
        } else {
            // Fallback to defaults if no config is available
            this.shutdownTimeoutSeconds = DEFAULT_SHUTDOWN_TIMEOUT_SECONDS;
            this.batchSize = DEFAULT_BATCH_SIZE;
            this.executor = system.getSharedExecutor() != null ? 
                           system.getSharedExecutor() : 
                           Executors.newVirtualThreadPerTaskExecutor();
        }

        this.pid = new Pid(actorId, system);
        logger.debug("Actor {} created with batch size {}", actorId, batchSize);
    }

    protected abstract void receive(Message message);

    /**
     * Called before the actor starts processing messages.
     * Override to perform initialization logic.
     */
    protected void preStart() {
        // Default implementation does nothing
    }

    /**
     * Called after the actor has stopped processing messages.
     * Override to perform cleanup logic.
     */
    protected void postStop() {
        // Default implementation does nothing
    }

    /**
     * Called when an exception occurs during message processing.
     * Override to provide custom error handling.
     * 
     * @param message The message that caused the exception
     * @param exception The exception that was thrown
     * @return true if the message should be reprocessed, false otherwise
     */
    protected boolean onError(Message message, Throwable exception) {
        // Default implementation logs the error and doesn't reprocess
        return false;
    }

    /**
     * Starts the actor and begins processing messages.
     * This should not be called directly for actors registered with an ActorSystem.
     */
    public void start() {
        if (isRunning) {
            logger.debug("Actor {} is already running", actorId);
            return;
        }

        isRunning = true;
        logger.info("Starting actor {}", actorId);

        try {
            preStart();

            // Use a virtual thread directly for the mailbox processing
            // This avoids the overhead of StructuredTaskScope when not needed
            mailboxThread = Thread.ofVirtual()
                .name("actor-" + actorId)
                .start(() -> {
                    try {
                        processMailbox();
                    } catch (Exception e) {
                        if (!(e instanceof InterruptedException)) {
                            logger.error("Unexpected error in actor {}", actorId, e);
                        }
                    }
                });
        } catch (Exception e) {
            isRunning = false;
            logger.error("Error during actor {} startup", actorId, e);
            throw e;
        }
    }

    /**
     * Gets the PID of this actor.
     * 
     * @return The PID of this actor
     */
    public Pid getPid() {
        return pid;
    }
    
    /**
     * Gets the PID of this actor. Alias for getPid().
     * 
     * @return The PID of this actor
     */
    public Pid self() {
        return pid;
    }

    public String getActorId() {
        return actorId;
    }

    public void tell(Message message) {
        mailbox.offer(message);
        
        // Update metrics if backpressure is enabled
        if (backpressureEnabled) {
            updateMetrics();
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Sets the shutdown timeout for this actor.
     * 
     * @param timeoutSeconds The timeout in seconds to wait for actor termination
     * @return This actor instance for method chaining
     */
    public Actor<Message> withShutdownTimeout(int timeoutSeconds) {
        this.shutdownTimeoutSeconds = timeoutSeconds;
        return this;
    }

    public void stop() {
        if (!isRunning) {
            return;
        }
        logger.debug("Stopping actor {}", actorId);
        isRunning = false;

        // Stop all children first
        for (Actor<?> child : new ConcurrentHashMap<>(children).values()) {
            logger.debug("Stopping child actor {} of parent {}", child.getActorId(), actorId);
            child.stop();
        }

        // Drain the mailbox to prevent processing during shutdown
        mailbox.clear();

        // Interrupt the mailbox thread if it exists
        if (mailboxThread != null) {
            mailboxThread.interrupt();
            try {
                // Give the mailbox thread a short time to terminate
                mailboxThread.join(TimeUnit.SECONDS.toMillis(1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mailboxThread = null;
        }

        // Only shut down the executor if it's not shared from the actor system
        if (system.getSharedExecutor() != executor) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                    logger.warn("Actor {} executor did not terminate in time, forcing shutdown", actorId);
                    executor.shutdownNow();
                    // Give it one more chance with a short timeout
                    if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                        logger.error("Actor {} executor could not be terminated even after forced shutdown", actorId);
                    }
                }
            } catch (InterruptedException e) {
                // This is expected during mass shutdowns, so lower the log level
                logger.debug("Actor {} shutdown interrupted", actorId, e);
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        try {
            postStop();
        } catch (Exception e) {
            logger.error("Error during actor {} postStop", actorId, e);
        } finally {
            // Clear children map
            children.clear();

            // Remove from parent if exists
            if (parent != null) {
                parent.removeChild(actorId);
            }

            system.shutdown(actorId);
        }
    }

    /**
     * Sets the supervision strategy for this actor.
     * 
     * @param strategy The supervision strategy to use
     * @return This actor instance for method chaining
     */
    public Actor<Message> withSupervisionStrategy(SupervisionStrategy strategy) {
        this.supervisionStrategy = strategy;
        return this;
    }

    /**
     * Sets the parent of this actor.
     * 
     * @param parent The parent actor
     */
    void setParent(Actor<?> parent) {
        this.parent = parent;
    }

    /**
     * Returns the parent of this actor, or null if this actor has no parent.
     * 
     * @return The parent actor
     */
    public Actor<?> getParent() {
        return parent;
    }

    /**
     * Adds a child actor to this actor.
     * 
     * @param child The child actor to add
     */
    void addChild(Actor<?> child) {
        children.put(child.getActorId(), child);
        child.setParent(this);
    }

    /**
     * Removes a child actor from this actor.
     * 
     * @param childId The ID of the child actor to remove
     */
    void removeChild(String childId) {
        children.remove(childId);
    }

    /**
     * Returns an unmodifiable view of the children of this actor.
     * 
     * @return The children of this actor
     */
    public Map<String, Actor<?>> getChildren() {
        return Collections.unmodifiableMap(children);
    }

    /**
     * Creates and registers a child actor of the specified class.
     * 
     * @param <T> The type of the child actor
     * @param actorClass The class of the child actor
     * @param childId The ID for the child actor
     * @return The PID of the created child actor
     */
    public <T extends Actor<?>> Pid createChild(Class<T> actorClass, String childId) {
        return system.registerChild(actorClass, childId, this);
    }

    /**
     * Creates and registers a child actor of the specified class with an auto-generated ID.
     * 
     * @param <T> The type of the child actor
     * @param actorClass The class of the child actor
     * @return The PID of the created child actor
     */
    public <T extends Actor<?>> Pid createChild(Class<T> actorClass) {
        return system.registerChild(actorClass, this);
    }

    /**
     * Handles exceptions according to the current supervision strategy.
     * 
     * @param message The message that caused the exception
     * @param exception The exception that was thrown
     */
    protected void handleException(Message message, Throwable exception) {
        boolean shouldReprocess = onError(message, exception);

        switch (supervisionStrategy) {
            case RESUME -> {
                logger.debug("Actor {} resuming after error", actorId);
                // Continue processing next message
            }
            case RESTART -> {
                logger.info("Restarting actor {}", actorId);
                stop();
                start();
                if (shouldReprocess) {
                    tell(message); // Reprocess the failed message
                }
            }
            case STOP -> {
                logger.info("Stopping actor {} due to error", actorId);
                stop();
            }
            case ESCALATE -> {
                logger.info("Escalating error from actor {}", actorId);

                // Save parent reference before stopping
                Actor<?> parentRef = parent;

                // Stop before escalating to prevent race conditions
                stop();

                if (parentRef != null) {
                    // Propagate the error to the parent actor
                    parentRef.handleChildError(this, exception);
                } else {
                    // No parent, throw the exception to the system
                    throw new ActorException("Error in actor", exception, actorId);
                }
            }
        }
    }

    /**
     * Handles an error from a child actor.
     * 
     * @param child The child actor that experienced an error
     * @param exception The exception that was thrown
     */
    void handleChildError(Actor<?> child, Throwable exception) {
        logger.info("Actor {} handling error from child {}", actorId, child.getActorId());

        // Always remove the child from our children map since it's already stopped
        // or will be stopped by the supervision strategy
        removeChild(child.getActorId());

        // Apply this actor's supervision strategy to the child error
        switch (supervisionStrategy) {
            case RESUME -> {
                logger.debug("Actor {} allowing child {} to resume after error", actorId, child.getActorId());
                // Child might already be stopped, we need to restart it
                if (!child.isRunning()) {
                    child.start();
                }
                // Re-add the child to our children map
                addChild(child);
            }
            case RESTART -> {
                logger.info("Actor {} restarting child {} after error", actorId, child.getActorId());
                // Make sure the child is stopped before restarting
                if (child.isRunning()) {
                    child.stop();
                }
                child.start();
                // Re-add the child to our children map
                addChild(child);
            }
            case STOP -> {
                logger.info("Actor {} confirming stop of child {} due to error", actorId, child.getActorId());
                // Make sure the child is stopped
                if (child.isRunning()) {
                    child.stop();
                }
                // Child is already removed from children map
            }
            case ESCALATE -> {
                logger.info("Actor {} escalating error from child {}", actorId, child.getActorId());

                // Make sure the child is stopped
                if (child.isRunning()) {
                    child.stop();
                }

                if (parent != null) {
                    // Continue escalating up the hierarchy
                    parent.handleChildError(this, exception);
                } else {
                    // No parent, throw the exception to the system
                    throw new ActorException("Error in child actor", exception, child.getActorId());
                }
            }
        }
    }

    protected void processMailbox() {
        while (isRunning) {
            try {
                // Clear the batch buffer for reuse
                batchBuffer.clear();

                // Get at least one message (blocking)
                Message firstMessage = mailbox.take();
                batchBuffer.add(firstMessage);

                // Try to drain more messages up to batch size (non-blocking)
                if (batchSize > 1) {
                    mailbox.drainTo(batchBuffer, batchSize - 1);
                }

                // Process the batch
                for (Message message : batchBuffer) {
                    if (!isRunning) break; // Check if we should stop processing

                    try {
                        receive(message);
                        
                        // Update processing metrics if backpressure is enabled
                        if (backpressureEnabled) {
                            messagesProcessed.incrementAndGet();
                            lastProcessingTimestamp.set(System.currentTimeMillis());
                        }
                    } catch (Exception e) {
                        logger.error("Actor {} error processing message: {}", actorId, message, e);
                        handleException(message, e);
                    }
                }
                
                // Update metrics and potentially resize the mailbox if backpressure is enabled
                if (backpressureEnabled) {
                    updateMetrics();
                }

            } catch (InterruptedException e) {
                // This is expected during shutdown, so use debug level
                logger.debug("Actor {} mailbox processing interrupted", actorId);
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Supervision strategies for handling actor failures.
     */
    public enum SupervisionStrategy {
        /**
         * Resume processing the next message, ignoring the failure.
         */
        RESUME,

        /**
         * Restart the actor, then continue processing messages.
         */
        RESTART,

        /**
         * Stop the actor.
         */
        STOP,

        /**
         * Escalate the failure to the parent/system.
         */
        ESCALATE
    }

    /**
     * Attempts to send a message to this actor with backpressure awareness.
     * If the mailbox is full, this method will return false instead of blocking.
     * If backpressure is not enabled, this method always returns true.
     *
     * @param message The message to send
     * @return true if the message was accepted, false if backpressure is being applied
     */
    public boolean tryTell(Message message) {
        boolean result = mailbox.offer(message);
        
        // Update metrics if backpressure is enabled
        if (backpressureEnabled && result) {
            updateMetrics();
        }
        
        return result;
    }
    
    /**
     * Attempts to send a message to this actor with a timeout for backpressure.
     * If the mailbox is full, this method will wait up to the specified timeout.
     * If backpressure is not enabled, this method always returns true unless interrupted.
     *
     * @param message The message to send
     * @param timeout The maximum time to wait
     * @param unit The time unit of the timeout argument
     * @return true if the message was accepted, false if the timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean tryTell(Message message, long timeout, TimeUnit unit) throws InterruptedException {
        boolean result = mailbox.offer(message, timeout, unit);
        
        // Update metrics if backpressure is enabled
        if (backpressureEnabled && result) {
            updateMetrics();
        }
        
        return result;
    }
    
    /**
     * Sets a callback to be notified when backpressure conditions change.
     * This can be used by producers to adapt their sending rate.
     * The callback will only be invoked if backpressure is enabled.
     *
     * @param callback The callback to invoke with backpressure metrics
     * @return This actor instance for method chaining
     */
    public Actor<Message> withBackpressureCallback(Consumer<BackpressureMetrics> callback) {
        this.backpressureCallback = callback;
        return this;
    }
    
    /**
     * Gets the current backpressure metrics for this actor.
     * If backpressure is not enabled, the metrics will reflect an unbounded mailbox.
     *
     * @return The current backpressure metrics
     */
    public BackpressureMetrics getBackpressureMetrics() {
        if (backpressureEnabled) {
            updateMetrics();
        } else {
            // For unbounded mailboxes, just update the current size
            metrics.update(mailbox.size(), Integer.MAX_VALUE, processingRate.get(), false);
        }
        return metrics;
    }
    
    /**
     * Checks if this actor has backpressure enabled.
     *
     * @return true if backpressure is enabled, false otherwise
     */
    public boolean isBackpressureEnabled() {
        return backpressureEnabled;
    }

    /**
     * Updates the actor's metrics and potentially resizes the mailbox
     * based on current usage patterns. This is only used when backpressure is enabled.
     */
    private void updateMetrics() {
        if (!backpressureEnabled) {
            return;
        }
        
        long now = System.currentTimeMillis();
        long lastUpdate = lastMetricsUpdateTime.get();
        
        // Only update metrics and resize at the configured interval
        if (now - lastUpdate >= metricsUpdateIntervalMs) {
            if (lastMetricsUpdateTime.compareAndSet(lastUpdate, now)) {
                // Calculate current processing rate
                long processed = messagesProcessed.getAndSet(0);
                long elapsedSeconds = Math.max(1, (now - lastUpdate) / 1000);
                long rate = processed / elapsedSeconds;
                processingRate.set(rate);
                
                // Update current size
                currentSize.set(mailbox.size());
                
                // Check if we need to resize the mailbox
                if (mailbox instanceof ResizableBlockingQueue) {
                    ResizableBlockingQueue<Message> resizableMailbox = (ResizableBlockingQueue<Message>) mailbox;
                    int capacity = resizableMailbox.getCapacity();
                    float fillRatio = (float) currentSize.get() / capacity;
                    
                    if (fillRatio >= highWatermark && capacity < maxCapacity) {
                        // Mailbox is getting full, try to grow it
                        int newCapacity = Math.min(maxCapacity, (int) (capacity * growthFactor));
                        if (newCapacity > capacity) {
                            logger.debug("Growing mailbox for actor {} from {} to {}", 
                                    actorId, capacity, newCapacity);
                            resizableMailbox.resize(newCapacity);
                        }
                    } else if (fillRatio <= lowWatermark && capacity > minCapacity) {
                        // Mailbox is mostly empty, try to shrink it
                        int newCapacity = Math.max(minCapacity, (int) (capacity * shrinkFactor));
                        if (newCapacity < capacity) {
                            logger.debug("Shrinking mailbox for actor {} from {} to {}", 
                                    actorId, capacity, newCapacity);
                            resizableMailbox.resize(newCapacity);
                        }
                    }
                    
                    // Update metrics object for callback
                    metrics.update(
                        currentSize.get(),
                        capacity,
                        processingRate.get(),
                        fillRatio >= highWatermark
                    );
                } else {
                    // For non-resizable mailboxes, just update metrics without resize logic
                    metrics.update(
                        currentSize.get(),
                        Integer.MAX_VALUE, // Unbounded capacity
                        processingRate.get(),
                        false // Never backpressured for unbounded queues
                    );
                }
                
                // Notify callback if registered
                if (backpressureCallback != null) {
                    backpressureCallback.accept(metrics);
                }
            }
        }
    }

    /**
     * Metrics class that provides information about the actor's mailbox state
     * and processing capabilities.
     */
    public static class BackpressureMetrics {
        private int currentSize;
        private int capacity;
        private long processingRate;
        private boolean backpressureActive;
        
        private void update(int currentSize, int capacity, long processingRate, boolean backpressureActive) {
            this.currentSize = currentSize;
            this.capacity = capacity;
            this.processingRate = processingRate;
            this.backpressureActive = backpressureActive;
        }
        
        /**
         * Gets the current number of messages in the mailbox.
         *
         * @return The current mailbox size
         */
        public int getCurrentSize() {
            return currentSize;
        }
        
        /**
         * Gets the current capacity of the mailbox.
         *
         * @return The current mailbox capacity
         */
        public int getCapacity() {
            return capacity;
        }
        
        /**
         * Gets the current processing rate in messages per second.
         *
         * @return The current processing rate
         */
        public long getProcessingRate() {
            return processingRate;
        }
        
        /**
         * Checks if backpressure is currently active.
         *
         * @return true if backpressure is active, false otherwise
         */
        public boolean isBackpressureActive() {
            return backpressureActive;
        }
        
        /**
         * Gets the current fill ratio of the mailbox (size/capacity).
         *
         * @return The current fill ratio
         */
        public float getFillRatio() {
            return capacity > 0 ? (float) currentSize / capacity : 0;
        }
    }
}
