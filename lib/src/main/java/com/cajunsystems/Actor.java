package com.cajunsystems;

import com.cajunsystems.backpressure.*;
import com.cajunsystems.config.*;
import com.cajunsystems.mailbox.config.MailboxProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
    
    // Per-actor logger with actor ID context
    private Logger actorLogger;

    // Default values for backpressure configuration
    private static final float DEFAULT_HIGH_WATERMARK = 0.8f;
    private static final float DEFAULT_LOW_WATERMARK = 0.2f;

    // Core Actor fields
    private final String actorId;
    private Pid pid;
    private com.cajunsystems.mailbox.Mailbox<Message> mailbox;
    private final ActorSystem system;
    private SupervisionStrategy supervisionStrategy = SupervisionStrategy.RESUME;
    private Actor<?> parent;
    private final Map<String, Actor<?>> children = new ConcurrentHashMap<>();
    // Timeout in seconds for actor shutdown
    private int shutdownTimeoutSeconds = DEFAULT_SHUTDOWN_TIMEOUT_SECONDS;
    private final MailboxProcessor<Message> mailboxProcessor;
    private final int actorMailboxMaxCapacity; // Added to store configured max capacity

    // For tracking metrics
    private final AtomicLong messagesProcessedSinceLastRateCalculation = new AtomicLong(0);
    private final AtomicLong lastMetricsUpdateTime = new AtomicLong(System.currentTimeMillis());
    private static final int METRICS_UPDATE_INTERVAL_MS = 1000; // Update metrics once per second

    // Enhanced backpressure management
    private BackpressureManager<Message> backpressureManager;
    private AtomicLong lastProcessingTimestamp = new AtomicLong(System.currentTimeMillis());
    
    // Sender context for ask pattern - stores the actor ID of the sender/replyTo
    private final ThreadLocal<String> senderContext = new ThreadLocal<>();

    /**
     * Gets the current number of messages in the mailbox.
     *
     * @return The current mailbox size
     */
    public int getCurrentSize() {
        return mailboxProcessor.getCurrentSize();
    }

    /**
     * Gets the current capacity of the mailbox.
     *
     * @return The current mailbox capacity
     */
    public int getCapacity() {
        return this.actorMailboxMaxCapacity; // Changed to return stored max capacity
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
     * Gets the current fill ratio of the mailbox (size/capacity).
     *
     * @return The current fill ratio
     */
    public float getFillRatio() {
        int currentSize = getCurrentSize();
        int capacity = getCapacity();
        return capacity > 0 ? (float) currentSize / capacity : 0;
    }

    /**
     * Creates a new Actor with the specified system and an auto-generated ID.
     *
     * @param system The actor system
     * @deprecated Use the new interface-based approach with ActorSystem.actorOf() instead
     */
    @Deprecated
    public Actor(ActorSystem system) {
        this(system, generateDefaultActorId());
    }

    /**
     * Creates a new Actor with the specified system and ID.
     * Backpressure is disabled by default.
     *
     * @param system  The actor system
     * @param actorId The actor ID
     * @deprecated Use the new interface-based approach with ActorSystem.actorOf() instead
     */
    @Deprecated
    public Actor(ActorSystem system, String actorId) {
        this(system, actorId, null, null);
    }

    /**
     * Creates a new Actor with the specified system, ID, and backpressure configuration.
     * Uses default mailbox configuration.
     *
     * @param system             The actor system
     * @param actorId            The actor ID
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @deprecated Use the new interface-based approach with ActorSystem.actorOf() instead
     */
    @Deprecated
    public Actor(ActorSystem system, String actorId, BackpressureConfig backpressureConfig) {
        this(system,
                actorId,
                backpressureConfig,
                system.getMailboxConfig(), // Use system's default MailboxConfig
                system.getThreadPoolFactory(),
                system.getMailboxProvider());
    }

    /**
     * Creates a new Actor with the specified system, ID, and mailbox configuration.
     *
     * @param system        The actor system
     * @param actorId       The actor ID
     * @param mailboxConfig The mailbox configuration
     * @deprecated Use the new interface-based approach with ActorSystem.actorOf() instead
     */
    @Deprecated
    public Actor(ActorSystem system, String actorId, ResizableMailboxConfig mailboxConfig) {
        this(system,
                actorId,
                system.getBackpressureConfig(), // Use system's default BackpressureConfig
                mailboxConfig,
                system.getThreadPoolFactory(),
                system.getMailboxProvider());
    }

    /**
     * Creates a new Actor with the specified system, ID, backpressure configuration, and mailbox configuration.
     *
     * @param system             The actor system
     * @param actorId            The actor ID
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @param mailboxConfig      The mailbox configuration
     */
    protected Actor(ActorSystem system, String actorId, BackpressureConfig backpressureConfig, MailboxConfig mailboxConfig) {
        this(system,
                actorId,
                backpressureConfig,
                mailboxConfig,
                system.getThreadPoolFactory(),
                system.getMailboxProvider());
    }

    /**
     * Creates a new Actor with the specified system, ID, backpressure configuration, mailbox configuration, and thread pool factory.
     *
     * @param system                  The actor system
     * @param actorId                 The actor ID
     * @param backpressureConfig      The backpressure configuration, or null to disable backpressure
     * @param mailboxConfig           The mailbox configuration
     * @param threadPoolFactory       The thread pool factory, or null to use system's default
     * @param mailboxProviderInstance The mailbox provider instance, or null to use system's default
     */
    protected Actor(ActorSystem system,
                    String actorId,
                    BackpressureConfig backpressureConfig,
                    MailboxConfig mailboxConfig,
                    ThreadPoolFactory threadPoolFactory,
                    MailboxProvider<Message> mailboxProviderInstance) {
        this.system = system;
        this.actorId = actorId == null ? generateDefaultActorId() : actorId;
        this.pid = new Pid(this.actorId, system);
        
        // Initialize per-actor logger with actor ID context
        this.actorLogger = LoggerFactory.getLogger(this.getClass().getName() + "." + this.actorId);

        // Use provided instances or fallback to system's defaults if they are null (though system should pass non-null)
        ThreadPoolFactory effectiveTpf = (threadPoolFactory != null) ? threadPoolFactory : system.getThreadPoolFactory();
        MailboxProvider<Message> effectiveMp = (mailboxProviderInstance != null)
                ? mailboxProviderInstance
                : system.getMailboxProvider();
        MailboxConfig effectiveMailboxConfig = (mailboxConfig != null) ? mailboxConfig : system.getMailboxConfig();

        this.actorMailboxMaxCapacity = effectiveMailboxConfig.getMaxCapacity(); // Initialize actorMailboxMaxCapacity

        // Determine workload type hint
        ThreadPoolFactory.WorkloadType workloadTypeHint = (effectiveTpf != null)
                ? effectiveTpf.getInferredWorkloadType() 
                : ThreadPoolFactory.WorkloadType.IO_BOUND; // Changed GENERAL to IO_BOUND

        // Convert MailboxConfig to the mailbox module's MailboxConfig
        com.cajunsystems.mailbox.config.MailboxConfig mailboxModuleConfig =
                new com.cajunsystems.mailbox.config.MailboxConfig()
                .setInitialCapacity(effectiveMailboxConfig.getInitialCapacity())
                .setMaxCapacity(effectiveMailboxConfig.getMaxCapacity())
                .setResizeThreshold(effectiveMailboxConfig.getResizeThreshold())
                .setResizeFactor(effectiveMailboxConfig.getResizeFactor());

        // Get mailbox configuration values (maxCapacity is used for backpressure)
        this.mailbox = effectiveMp.createMailbox(mailboxModuleConfig, workloadTypeHint); // Pass MailboxConfig and WorkloadType

        // Initialize BackpressureManager only if backpressureConfig is provided
        if (backpressureConfig != null) {
            this.backpressureManager = new BackpressureManager<>(this, backpressureConfig); // Removed system.getSystemBackpressureMonitor()
        } else {
            this.backpressureManager = null;
        }

        // Use configuration from explicitly provided thread pool factory, otherwise use defaults
        int configuredBatchSize = DEFAULT_BATCH_SIZE;
        if (effectiveTpf != null) {
            this.shutdownTimeoutSeconds = effectiveTpf.getActorShutdownTimeoutSeconds();
            configuredBatchSize = effectiveTpf.getActorBatchSize();
        } else {
            this.shutdownTimeoutSeconds = DEFAULT_SHUTDOWN_TIMEOUT_SECONDS;
        }

        this.mailboxProcessor = new MailboxProcessor<Message>(
                this.actorId, // Use this.actorId which is now definitely set
                mailbox,
                configuredBatchSize,
                this::handleException,
                new ActorLifecycle<Message>() {
                    @Override
                    public void preStart() {
                        Actor.this.preStart();
                    }

                    @Override
                    public void receive(Message message) {
                        // Check if this is a MessageWithSender wrapper
                        if (message instanceof ActorSystem.MessageWithSender<?>(Object message1, String sender)) {
                            // Set sender context
                            setSender(sender);
                            try {
                                @SuppressWarnings("unchecked")
                                Message unwrapped = (Message) message1;
                                Actor.this.receive(unwrapped);
                            } finally {
                                // Clear sender context
                                clearSender();
                            }
                        } else {
                            Actor.this.receive(message);
                        }
                        lastProcessingTimestamp.set(System.currentTimeMillis());
                    }

                    @Override
                    public void postStop() {
                        Actor.this.postStop();
                    }
                },
                effectiveTpf // Pass the effective ThreadPoolFactory to MailboxProcessor
        );
        logger.debug("Actor {} created with batch size {}", this.actorId, configuredBatchSize);
    }

    /**
     * Processes a received message.
     * This method must be implemented by concrete actor classes to define message handling behavior.
     *
     * @param message the message to process
     */
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
        logger.info("Actor {} stopping.", actorId);
        // Stop all children
        for (Actor<?> child : children.values()) {
            child.stop();
        }
        children.clear();

        // Shutdown backpressure manager if it exists and is enabled
        if (backpressureManager != null) {
            logger.debug("Shutting down backpressure manager for actor {}.", actorId);
            backpressureManager.shutdown();
        }

        // Additional cleanup specific to the actor can be done here by overriding this method
        // For example, releasing resources, closing connections, etc.
    }

    /**
     * Called when an exception occurs during message processing.
     * Override to provide custom error handling.
     *
     * @param message   The message that caused the exception
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
        if (backpressureManager != null) {
            int currentSize = mailboxProcessor.getCurrentSize();
            int capacity = getCapacity(); // This will now correctly use actorMailboxMaxCapacity
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
        if (backpressureManager != null) {
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
        mailboxProcessor.start();
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

    /**
     * Gets the actor system this actor belongs to.
     *
     * @return The actor system
     */
    public ActorSystem getSystem() {
        return system;
    }

    public void tell(Message message) {
        mailboxProcessor.tell(message);

        // Update metrics if backpressure is enabled
        if (backpressureManager != null) {
            updateMetrics();
        }
    }
    
    /**
     * Sets the sender context for the current message.
     * Used internally by the ask pattern to track the reply-to actor.
     * 
     * @param senderActorId The actor ID of the sender
     */
    void setSender(String senderActorId) {
        senderContext.set(senderActorId);
    }
    
    /**
     * Clears the sender context after message processing.
     * Used internally by the ask pattern.
     */
    void clearSender() {
        senderContext.remove();
    }
    
    /**
     * Gets the sender of the current message being processed.
     * Returns an empty Optional if there is no sender context (e.g., for messages sent via tell without ask).
     * 
     * @return An Optional containing the PID of the sender, or empty if no sender context
     */
    public Optional<Pid> getSender() {
        String senderActorId = senderContext.get();
        return Optional.ofNullable(senderActorId).map(id -> new Pid(id, system));
    }
    
    /**
     * Gets the sender actor ID from the current thread context.
     * Package-private for use by Pid.tell() to propagate sender context.
     * 
     * @return The sender actor ID, or null if no sender context
     */
    String getCurrentSenderActorId() {
        return senderContext.get();
    }
    
    /**
     * Forwards a message to another actor, preserving the original sender context.
     * This is useful when an actor acts as an intermediary and wants the final recipient
     * to know about the original sender (e.g., for ask pattern replies).
     * 
     * @param <T> The type of the message
     * @param target The target actor PID
     * @param message The message to forward
     */
    public <T> void forward(Pid target, T message) {
        String originalSender = senderContext.get();
        if (originalSender != null) {
            // Wrap the message with the original sender context
            ActorSystem.MessageWithSender<T> wrapped = new ActorSystem.MessageWithSender<>(message, originalSender);
            system.routeMessage(target.actorId(), wrapped);
        } else {
            // No sender context, just send normally
            target.tell(message);
        }
    }
    
    /**
     * Gets a logger for this actor with the actor ID as context.
     * This provides consistent logging output across all actors.
     * 
     * @return A logger instance configured for this actor
     */
    public Logger getLogger() {
        return actorLogger;
    }

    public boolean isRunning() {
        return mailboxProcessor.isRunning();
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
        if (!mailboxProcessor.isRunning()) {
            return;
        }
        logger.debug("Stopping actor {}", actorId);

        // Stop all children first
        for (Actor<?> child : new ConcurrentHashMap<>(children).values()) {
            logger.debug("Stopping child actor {} of parent {}", child.getActorId(), actorId);
            child.stop();
        }

        // Stop mailbox processing
        mailboxProcessor.stop();

        // Clean up actor hierarchy and system
        children.clear();
        if (parent != null) {
            parent.removeChild(actorId);
        }
        system.shutdown(actorId);
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
     * Gets the supervision strategy for this actor.
     *
     * @return The current supervision strategy
     */
    public SupervisionStrategy getSupervisionStrategy() {
        return supervisionStrategy;
    }

    /**
     * Sets the parent of this actor.
     *
     * @param parent The parent actor
     */
    public void setParent(Actor<?> parent) {
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
    public void addChild(Actor<?> child) {
        children.put(child.getActorId(), child);
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
     * @param <T>        The type of the child actor
     * @param actorClass The class of the child actor
     * @param childId    The ID for the child actor
     * @return The PID of the created child actor
     */
    public <T extends Actor<?>> Pid createChild(Class<T> actorClass, String childId) {
        return system.registerChild(actorClass, childId, this);
    }

    /**
     * Creates and registers a child actor of the specified class with an auto-generated ID.
     *
     * @param <T>        The type of the child actor
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
     * Delegates to {@link Supervisor}.
     *
     * @param message   The message that caused the exception
     * @param exception The exception that was thrown
     */
    protected void handleException(Message message, Throwable exception) {
        Supervisor.handleException(this, message, exception);
    }

    /**
     * Handles an error from a child actor according to the current supervision strategy.
     * Delegates to {@link Supervisor}.
     *
     * @param child     The child actor that experienced an error
     * @param exception The exception that was thrown
     */
    void handleChildError(Actor<?> child, Throwable exception) {
        Supervisor.handleChildError(this, child, exception);
    }

    /**
     * Drops the oldest message from the mailbox to make room for new messages.
     * This is used by the backpressure system when using the DROP_OLDEST strategy.
     *
     * @return true if a message was successfully dropped, false otherwise
     */
    public boolean dropOldestMessage() {
        return mailboxProcessor.dropOldestMessage();
    }

    /**
     * Gets the BackpressureManager for this actor, if configured.
     *
     * @return The BackpressureManager instance, or null if backpressure is not configured.
     */
    public BackpressureManager<Message> getBackpressureManager() {
        return this.backpressureManager;
    }
}
