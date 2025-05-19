package systems.cajun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import systems.cajun.backpressure.BackpressureEvent;
import systems.cajun.backpressure.BackpressureManager;
import systems.cajun.backpressure.BackpressureSendOptions;
import systems.cajun.backpressure.BackpressureStrategy;
import systems.cajun.backpressure.CustomBackpressureHandler;
import systems.cajun.config.BackpressureConfig;
import systems.cajun.config.MailboxConfig;
import systems.cajun.config.ResizableMailboxConfig;
import systems.cajun.util.ResizableBlockingQueue;

/**
 * Core Actor class for the Cajun actor system.
 * Handles message passing, lifecycle management, and backpressure.
 *
 * @param <Message> The type of messages this actor processes
 */
public abstract class Actor<Message> {

    private static final Logger logger = LoggerFactory.getLogger(Actor.class);
    private static final int DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_BATCH_SIZE = 10; // Default number of messages to process in a batch
    
    // Default values for backpressure configuration
    private static final float DEFAULT_HIGH_WATERMARK = 0.8f;
    private static final float DEFAULT_LOW_WATERMARK = 0.2f;

    // Core Actor fields
    private final String actorId;
    private Pid pid;
    private BlockingQueue<Message> mailbox;
    private volatile Thread mailboxThread;
    private volatile boolean isRunning = false;
    private final ActorSystem system;
    private SupervisionStrategy supervisionStrategy = SupervisionStrategy.RESUME;
    private Actor<?> parent;
    private final Map<String, Actor<?>> children = new ConcurrentHashMap<>();
    private int shutdownTimeoutSeconds = DEFAULT_SHUTDOWN_TIMEOUT_SECONDS;
    private int batchSize = DEFAULT_BATCH_SIZE;

    // Reusable batch buffer for message processing
    private final List<Message> batchBuffer = new ArrayList<>(DEFAULT_BATCH_SIZE);
    
    // Backpressure support
    private boolean backpressureEnabled = false;
    private int maxCapacity = Integer.MAX_VALUE; // Maximum capacity of the mailbox
    private float warningThreshold = DEFAULT_HIGH_WATERMARK;
    private float recoveryThreshold = DEFAULT_LOW_WATERMARK;
    private BackpressureStrategy backpressureStrategy = BackpressureStrategy.BLOCK;
    private CustomBackpressureHandler<Message> customBackpressureHandler;
    
    // For tracking metrics
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong messagesProcessedSinceLastRateCalculation = new AtomicLong(0);
    private final AtomicLong lastMetricsUpdateTime = new AtomicLong(System.currentTimeMillis());
    private static final int METRICS_UPDATE_INTERVAL_MS = 1000; // Update metrics once per second
    
    // Enhanced backpressure management
    private BackpressureManager<Message> backpressureManager;
    private AtomicLong lastProcessingTimestamp = new AtomicLong(System.currentTimeMillis());
    
    /**
     * Gets the current number of messages in the mailbox.
     *
     * @return The current mailbox size
     */
    public int getCurrentSize() {
        return mailbox != null ? mailbox.size() : 0;
    }
    
    /**
     * Gets the current capacity of the mailbox.
     *
     * @return The current mailbox capacity
     */
    public int getCapacity() {
        return maxCapacity;
    }
    
    /**
     * Gets the current processing rate in messages per second.
     *
     * @return The current processing rate
     */
    public long getProcessingRate() {
        return calculateProcessingRate();
    }
    
    /**
     * Checks if backpressure is currently active.
     *
     * @return true if backpressure is active, false otherwise
     */
    public boolean isBackpressureActive() {
        return backpressureManager != null && backpressureManager.isBackpressureActive();
    }
    
    /**
     * Checks if backpressure is enabled for this actor.
     *
     * @return true if backpressure is enabled, false otherwise
     */
    public boolean isBackpressureEnabled() {
        return backpressureEnabled;
    }
    
    /**
     * Gets the current fill ratio of the mailbox (size/capacity).
     *
     * @return The current fill ratio
     */
    public float getFillRatio() {
        int currentSize = getCurrentSize();
        int capacity = getCapacity();
        return capacity > 0 ? (float) currentSize / capacity : 0;
    }
    
    public Actor(ActorSystem system) {
        this(system, generateDefaultActorId());
    }

    /**
     * Creates a new Actor with the specified system and ID.
     * Backpressure is disabled by default.
     *
     * @param system The actor system
     * @param actorId The actor ID
     */
    public Actor(ActorSystem system, String actorId) {
        this(system, actorId, null, new ResizableMailboxConfig());
    }
    
    /**
     * Creates a new Actor with the specified system, ID, and backpressure configuration.
     * Uses default mailbox configuration.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     */
    public Actor(ActorSystem system, String actorId, BackpressureConfig backpressureConfig) {
        this(system, actorId, backpressureConfig, new ResizableMailboxConfig());
    }
    
    public Actor(ActorSystem system, String actorId, ResizableMailboxConfig mailboxConfig) {
        this(system, actorId, null, mailboxConfig);
    }

    /**
     * Creates a new Actor with the specified system, ID, backpressure configuration, and mailbox configuration.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @param mailboxConfig The mailbox configuration
     */
    public Actor(ActorSystem system, String actorId, BackpressureConfig backpressureConfig, ResizableMailboxConfig mailboxConfig) {
        this.system = system;
        this.actorId = actorId == null ? generateDefaultActorId() : actorId;
        this.pid = new Pid(this.actorId, system);
        
        // Handle null mailboxConfig
        if (mailboxConfig == null) {
            mailboxConfig = new ResizableMailboxConfig();
        }
        
        // Get mailbox configuration values
        int initialCapacity = mailboxConfig.getInitialCapacity();
        int maxCapacity = mailboxConfig.getMaxCapacity();
        
        // Initialize backpressure configuration
        initializeBackpressure(backpressureConfig, null, maxCapacity);
        
        // Now create the mailbox based on backpressure configuration
        createMailbox(initialCapacity, maxCapacity, mailboxConfig);

        // Use configuration from the actor system
        if (system.getThreadPoolFactory() != null) {
            this.shutdownTimeoutSeconds = system.getThreadPoolFactory().getActorShutdownTimeoutSeconds();
            this.batchSize = system.getThreadPoolFactory().getActorBatchSize();
        } else {
            // Fallback to defaults if no config is available
            this.shutdownTimeoutSeconds = DEFAULT_SHUTDOWN_TIMEOUT_SECONDS;
            this.batchSize = DEFAULT_BATCH_SIZE;
        }
        
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
     * Updates the backpressure metrics for this actor.
     * This is called periodically to assess the current load and adjust backpressure behavior if needed.
     */
    private void updateMetrics() {
        if (backpressureManager != null && backpressureEnabled) {
            int currentSize = mailbox.size();
            int capacity = mailbox.remainingCapacity() + currentSize;
            long rate = calculateProcessingRate();
            
            // Update metrics in the manager
            backpressureManager.updateMetrics(currentSize, capacity, rate);
            
            // Update the last processing timestamp
            lastProcessingTimestamp.set(System.currentTimeMillis());
        }
    }
    
    /**
     * Calculates the current message processing rate (messages per second).
     * This is used for backpressure metric updates.
     * 
     * @return The current processing rate
     */
    private long calculateProcessingRate() {
        long now = System.currentTimeMillis();
        long timeSinceLastCalculation = now - lastMetricsUpdateTime.get();
        
        // Only recalculate if enough time has passed (at least 100ms) to avoid division by very small numbers
        if (timeSinceLastCalculation >= METRICS_UPDATE_INTERVAL_MS) {
            long messageCount = messagesProcessedSinceLastRateCalculation.getAndSet(0);
            long rate = timeSinceLastCalculation > 0 ? (messageCount * 1000) / timeSinceLastCalculation : 0; // Convert to per second
            
            // Update the timestamp
            lastMetricsUpdateTime.set(now);
            
            return rate;
        }
        
        // If not enough time has passed, return 0 as a safe default
        return 0;
    }

    /**
     * Checks if a message should be accepted based on backpressure settings and options.
     * 
     * @param options The backpressure send options
     * @return true if the message should be accepted, false otherwise
     */
    protected boolean checkBackpressure(BackpressureSendOptions options) {
        // Use the backpressure manager for new functionality
        if (backpressureManager != null && backpressureEnabled) {
            return backpressureManager.shouldAcceptMessage(options);
        } else {
            // No backpressure management when disabled, always accept
            return true;
        }
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
     * Generates a default actor ID using a UUID.
     * 
     * @return A string representation of a generated UUID
     */
    protected static String generateDefaultActorId() {
        return UUID.randomUUID().toString();
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

    /**
     * Process messages from the mailbox in batches.
     * This is the main loop that handles actor message processing.
     */
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
                        
                        // Update processing metrics for backpressure
                        if (backpressureEnabled) {
                            messagesProcessed.incrementAndGet();
                            messagesProcessedSinceLastRateCalculation.incrementAndGet();
                            lastProcessingTimestamp.set(System.currentTimeMillis());
                        }
                    } catch (Exception e) {
                        logger.error("Actor {} error processing message: {}", actorId, message, e);
                        handleException(message, e);
                    }
                }
                
                // Update metrics and potentially adjust backpressure if enabled
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
 * Initialize the backpressure system with the provided configuration.
 * 
 * @param backpressureConfig The backpressure configuration to use
 * @param maxCapacity The maximum capacity of the mailbox
 */
private void initializeBackpressure(BackpressureConfig backpressureConfig, int maxCapacity) {
    initializeBackpressure(backpressureConfig, null, maxCapacity);
}

/**
 * Initialize the backpressure system with the provided configuration and callback.
 * 
 * @param backpressureConfig The backpressure configuration to use
 * @param callback The callback to invoke when backpressure events occur
 */
public void initializeBackpressure(BackpressureConfig backpressureConfig, Consumer<BackpressureEvent> callback) {
    initializeBackpressure(backpressureConfig, callback, backpressureConfig != null ? backpressureConfig.getMaxCapacity() : Integer.MAX_VALUE);
}

/**
 * Initialize the backpressure system with the provided configuration, callback, and capacity.
 * 
 * @param backpressureConfig The backpressure configuration to use
 * @param callback The callback to invoke when backpressure events occur
 * @param maxCapacity The maximum capacity of the mailbox
 */
private void initializeBackpressure(BackpressureConfig backpressureConfig, Consumer<BackpressureEvent> callback, int maxCapacity) {
    // Handle null configuration
    if (backpressureConfig == null) {
        this.backpressureEnabled = false;
        this.warningThreshold = DEFAULT_HIGH_WATERMARK;
        this.recoveryThreshold = DEFAULT_LOW_WATERMARK;
        this.backpressureStrategy = BackpressureStrategy.BLOCK;
        this.customBackpressureHandler = null;
        return;
    }
        
    // Set up basic backpressure fields
    this.warningThreshold = backpressureConfig.getWarningThreshold();
    this.recoveryThreshold = backpressureConfig.getRecoveryThreshold();
    this.backpressureStrategy = backpressureConfig.getStrategy();
    this.customBackpressureHandler = (CustomBackpressureHandler<Message>) backpressureConfig.getCustomHandler();
    this.backpressureEnabled = backpressureConfig.isEnabled();
    this.maxCapacity = maxCapacity;
    
    // Create backpressure manager with the current configuration
    BackpressureConfig config = new BackpressureConfig();
    config.setEnabled(backpressureEnabled);
    config.setWarningThreshold(warningThreshold);
    config.setCriticalThreshold(warningThreshold * 1.2f); // Calculate based on warning threshold
    config.setRecoveryThreshold(recoveryThreshold);
    config.setStrategy(backpressureStrategy);
    config.setCustomHandler(customBackpressureHandler);
    config.setMaxCapacity(maxCapacity);

    // Initialize the backpressure manager
    this.backpressureManager = new BackpressureManager<Message>(this, config);
        
    // Register the callback if provided
    if (callback != null) {
        this.backpressureManager.setCallback(callback);
    }
}

/**
 * Creates the mailbox for this actor with the specified capacities and configuration.
 * 
 * @param initialCapacity The initial capacity of the mailbox
 * @param maxCapacity The maximum capacity the mailbox can grow to
 * @param mailboxConfig The mailbox configuration parameters
 */
private void createMailbox(int initialCapacity, int maxCapacity, MailboxConfig mailboxConfig) {
    if (mailboxConfig != null && mailboxConfig instanceof ResizableMailboxConfig) {
        this.mailbox = new ResizableBlockingQueue<>(initialCapacity, maxCapacity);
    } else {
        this.mailbox = new LinkedBlockingQueue<>(maxCapacity);
    }
        
    // Store capacity configuration for backpressure management
    this.maxCapacity = maxCapacity;
}

/**
 * Drops the oldest message from the mailbox to make room for new messages.
 * This is used by the backpressure system when using the DROP_OLDEST strategy.
 * 
 * @return true if a message was successfully dropped, false otherwise
 */
public boolean dropOldestMessage() {
    if (mailbox == null || mailbox.isEmpty()) {
        return false;
    }
    
    try {
        // Remove the oldest message (head of the queue)
        Object removed = mailbox.poll();
        if (removed != null) {
            logger.debug("Actor {} dropped oldest message to make room", actorId);
            return true;
        }
    } catch (Exception e) {
        logger.error("Error dropping oldest message: {}", e.getMessage(), e);
    }
    
    return false;
}
}
