package com.cajunsystems;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.config.MailboxConfig;
import com.cajunsystems.config.MailboxProvider;
import com.cajunsystems.config.ResizableMailboxConfig;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.metrics.ActorMetrics;
import com.cajunsystems.metrics.MetricsRegistry;
import com.cajunsystems.persistence.*;
import com.cajunsystems.persistence.PersistenceProvider;
import com.cajunsystems.persistence.PersistenceProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * An actor that maintains and persists its state.
 * StatefulActor extends the base Actor class by adding state management capabilities.
 * 
 * This implementation supports message persistence and replay for recovery using a hybrid approach:
 * - Messages are logged to a journal before processing
 * - State snapshots are taken periodically
 * - Recovery uses the latest snapshot plus replay of subsequent messages
 *
 * <h2>IMPORTANT SERIALIZATION REQUIREMENTS</h2>
 * <p>For persistence to work correctly, both the State and Message types MUST be serializable:</p>
 * <ul>
 *   <li>Both State and Message classes must implement java.io.Serializable</li>
 *   <li>All fields in these classes must also be serializable, or marked as transient</li>
 *   <li>For non-serializable fields (like lambdas or functional interfaces), use transient and
 *       implement custom serialization with readObject/writeObject methods</li>
 *   <li>Add serialVersionUID to all serializable classes to maintain compatibility</li>
 * </ul>
 * <p>Failure to meet these requirements will result in NotSerializableException during message
 *    journaling or state snapshot operations.</p>
 *
 * @param <State> The type of the actor's state (must implement java.io.Serializable)
 * @param <Message> The type of messages the actor can process (must implement java.io.Serializable)
 */
public abstract class StatefulActor<State, Message> extends Actor<Message> {

    private static final Logger logger = LoggerFactory.getLogger(StatefulActor.class);

    private final BatchedMessageJournal<Message> messageJournal;
    private final SnapshotStore<State> snapshotStore;
    private final AtomicReference<State> currentState = new AtomicReference<>();
    private final AtomicLong lastProcessedSequence = new AtomicLong(-1);
    private final String actorId;
    private final State initialState;
    private boolean stateInitialized = false;
    private boolean stateChanged = false;
    private long lastSnapshotTime = 0;
    private int changesSinceLastSnapshot = 0;
    private final PersistenceProvider persistenceProvider;
    
    // Metrics for this actor
    private final ActorMetrics metrics;
    
    // Dedicated thread pool for persistence operations
    private final ExecutorService persistenceExecutor;
    
    // Adaptive snapshot configuration
    private static final long DEFAULT_SNAPSHOT_INTERVAL_MS = 15000; // 15 seconds default
    private static final int DEFAULT_CHANGES_BEFORE_SNAPSHOT = 100; // Default number of changes before snapshot
    private long snapshotIntervalMs = DEFAULT_SNAPSHOT_INTERVAL_MS;
    private int changesBeforeSnapshot = DEFAULT_CHANGES_BEFORE_SNAPSHOT;
    
    // Configuration for the persistence thread pool
    private static final int DEFAULT_PERSISTENCE_THREAD_POOL_SIZE = 2;
    
    // Retry strategy for error recovery
    private RetryStrategy retryStrategy = new RetryStrategy();
    
    // Error hook for custom error handling
    private Consumer<Throwable> errorHook = ex -> {};

    // Helper method removed as it's no longer needed with the standardized constructor signatures

    /**
     * Creates a new StatefulActor with default persistence.
     *
     * @param system The actor system
     * @param initialState The initial state of the actor
     */
    protected StatefulActor(ActorSystem system, State initialState) {
        this(system, Actor.generateDefaultActorId(), initialState);
    }
    
    /**
     * Creates a new StatefulActor with default persistence and optional backpressure.
     *
     * @param system The actor system
     * @param initialState The initial state of the actor
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     */
    protected StatefulActor(ActorSystem system, State initialState, BackpressureConfig backpressureConfig) {
        this(system, initialState, backpressureConfig, new ResizableMailboxConfig());
    }
    
    /**
     * Creates a new StatefulActor with default persistence, backpressure and mailbox configuration.
     *
     * @param system The actor system
     * @param initialState The initial state of the actor
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @param mailboxConfig The mailbox configuration
     */
    protected StatefulActor(ActorSystem system, State initialState, BackpressureConfig backpressureConfig, ResizableMailboxConfig mailboxConfig) {
        super(system, Actor.generateDefaultActorId(), backpressureConfig, mailboxConfig);
        this.initialState = initialState;
        this.persistenceProvider = PersistenceProviderRegistry.getInstance().getDefaultProvider();
        this.messageJournal = this.persistenceProvider.createBatchedMessageJournal(getActorId());
        this.snapshotStore = this.persistenceProvider.createSnapshotStore(getActorId());
        this.actorId = getActorId();
        this.persistenceExecutor = createPersistenceExecutor(DEFAULT_PERSISTENCE_THREAD_POOL_SIZE);
        this.metrics = new ActorMetrics(actorId);
    }
    
    /**
     * Creates a new StatefulActor with default persistence, backpressure and a regular MailboxConfig.
     * This constructor is provided for backward compatibility with code that uses MailboxConfig instead of ResizableMailboxConfig.
     *
     * @param system The actor system
     * @param initialState The initial state of the actor
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @param mailboxConfig The mailbox configuration (will be converted to ResizableMailboxConfig)
     */
    protected StatefulActor(ActorSystem system, State initialState, BackpressureConfig backpressureConfig, MailboxConfig mailboxConfig) {
        this(system, initialState, backpressureConfig, convertToResizableMailboxConfig(mailboxConfig));
    }
    
    /**
     * Creates a new StatefulActor with a custom actor ID and default persistence.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     */
    protected StatefulActor(ActorSystem system, String actorId, State initialState) {
        this(system, actorId, initialState, null, new ResizableMailboxConfig());
    }
    
    /**
     * Creates a new StatefulActor with a custom actor ID, default persistence, and backpressure support.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     */
    protected StatefulActor(ActorSystem system, String actorId, State initialState, BackpressureConfig backpressureConfig) {
        this(system, actorId, initialState, backpressureConfig, new ResizableMailboxConfig());
    }
    
    /**
     * Creates a new StatefulActor with a custom actor ID, default persistence, backpressure and mailbox configuration.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @param mailboxConfig The mailbox configuration
     */
    protected StatefulActor(ActorSystem system, String actorId, State initialState, BackpressureConfig backpressureConfig, ResizableMailboxConfig mailboxConfig) {
        this(system, actorId, initialState, backpressureConfig, mailboxConfig, null, system.getMailboxProvider());
    }
    
    /**
     * Creates a new StatefulActor with a custom actor ID, default persistence, backpressure, mailbox configuration, and thread pool factory.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @param mailboxConfig The mailbox configuration
     * @param threadPoolFactory The thread pool factory, or null to use default
     */
    protected StatefulActor(ActorSystem system, String actorId, State initialState, BackpressureConfig backpressureConfig, ResizableMailboxConfig mailboxConfig, ThreadPoolFactory threadPoolFactory, MailboxProvider<Message> mailboxProvider) {
        super(system, actorId, backpressureConfig, mailboxConfig, threadPoolFactory, mailboxProvider);
        this.persistenceProvider = PersistenceProviderRegistry.getInstance().getDefaultProvider();
        this.initialState = initialState;
        this.messageJournal = this.persistenceProvider.createBatchedMessageJournal(getActorId());
        this.snapshotStore = this.persistenceProvider.createSnapshotStore(getActorId());
        this.actorId = getActorId();
        this.persistenceExecutor = createPersistenceExecutor(DEFAULT_PERSISTENCE_THREAD_POOL_SIZE);
        this.metrics = new ActorMetrics(actorId);
    }
    
    /**
     * Creates a new StatefulActor with a custom actor ID, default persistence, backpressure and a regular MailboxConfig.
     * This constructor is provided for backward compatibility with code that uses MailboxConfig instead of ResizableMailboxConfig.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @param mailboxConfig The mailbox configuration (will be converted to ResizableMailboxConfig)
     */
    protected StatefulActor(ActorSystem system, String actorId, State initialState, BackpressureConfig backpressureConfig, MailboxConfig mailboxConfig) {
        this(system, actorId, initialState, backpressureConfig, convertToResizableMailboxConfig(mailboxConfig));
    }
    
    /**
     * Creates a new StatefulActor with a custom actor ID, full persistence configuration, backpressure support, and a regular MailboxConfig.
     * This constructor is provided for backward compatibility with code that uses MailboxConfig instead of ResizableMailboxConfig.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     * @param messageJournal The message journal to use for message persistence
     * @param snapshotStore The snapshot store to use for state snapshots
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @param mailboxConfig The mailbox configuration (will be converted to ResizableMailboxConfig)
     */
    protected StatefulActor(ActorSystem system, String actorId, State initialState, 
                         BatchedMessageJournal<Message> messageJournal,
                         SnapshotStore<State> snapshotStore,
                         BackpressureConfig backpressureConfig,
                         MailboxConfig mailboxConfig) {
        this(system, actorId, initialState, messageJournal, snapshotStore, 
             DEFAULT_PERSISTENCE_THREAD_POOL_SIZE, backpressureConfig, convertToResizableMailboxConfig(mailboxConfig));
    }
    
    /**
     * Creates a new StatefulActor with a custom actor ID, full persistence configuration, backpressure and mailbox support.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     * @param messageJournal The message journal to use for message persistence
     * @param snapshotStore The snapshot store to use for state snapshots
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @param mailboxConfig The mailbox configuration
     */
    protected StatefulActor(ActorSystem system, String actorId, State initialState, 
                         BatchedMessageJournal<Message> messageJournal,
                         SnapshotStore<State> snapshotStore,
                         BackpressureConfig backpressureConfig,
                         ResizableMailboxConfig mailboxConfig) {
        this(system, actorId, initialState, messageJournal, snapshotStore, backpressureConfig, mailboxConfig, null, system.getMailboxProvider());
    }
    
    /**
     * Creates a new StatefulActor with a custom actor ID, full persistence configuration, backpressure, mailbox support, and thread pool factory.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     * @param messageJournal The message journal to use for message persistence
     * @param snapshotStore The snapshot store to use for state snapshots
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @param mailboxConfig The mailbox configuration
     * @param threadPoolFactory The thread pool factory, or null to use default
     */
    protected StatefulActor(ActorSystem system, String actorId, State initialState, 
                         BatchedMessageJournal<Message> messageJournal,
                         SnapshotStore<State> snapshotStore,
                         BackpressureConfig backpressureConfig,
                         ResizableMailboxConfig mailboxConfig,
                         ThreadPoolFactory threadPoolFactory,
                         MailboxProvider<Message> mailboxProvider) {
        super(system, actorId, backpressureConfig, mailboxConfig, threadPoolFactory, mailboxProvider);
        this.persistenceProvider = null; // Correct: For custom persistence, the provider field is null
        this.initialState = initialState;
        this.messageJournal = messageJournal; // Use the provided custom journal
        this.snapshotStore = snapshotStore;   // Use the provided custom snapshot store
        this.actorId = getActorId(); // Ensure actorId field is aligned with superclass, or use getActorId() directly elsewhere
        this.persistenceExecutor = createPersistenceExecutor(DEFAULT_PERSISTENCE_THREAD_POOL_SIZE);
        this.metrics = new ActorMetrics(getActorId()); // Use getActorId() for consistency
    }
    
    /**
     * Creates a new StatefulActor with a custom actor ID, full persistence configuration, custom thread pool size,
     * and backpressure support.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     * @param messageJournal The message journal to use for message persistence
     * @param snapshotStore The snapshot store to use for state snapshots
     * @param persistenceThreadPoolSize The thread pool size for persistence operations
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @param mailboxConfig The mailbox configuration
     */
    protected StatefulActor(ActorSystem system, String actorId, State initialState, 
                         BatchedMessageJournal<Message> messageJournal,
                         SnapshotStore<State> snapshotStore,
                         int persistenceThreadPoolSize,
                         BackpressureConfig backpressureConfig,
                         ResizableMailboxConfig mailboxConfig) {
        super(system, actorId, backpressureConfig, mailboxConfig);
        this.persistenceProvider = PersistenceProviderRegistry.getInstance().getDefaultProvider();
        this.initialState = initialState;
        this.messageJournal = messageJournal;
        this.snapshotStore = snapshotStore;
        this.actorId = actorId;
        this.persistenceExecutor = createPersistenceExecutor(persistenceThreadPoolSize);
        this.metrics = new ActorMetrics(actorId);
    }
    
    /**
     * Creates a new StatefulActor with a custom actor ID, message journal, and snapshot store.
     * This constructor is specifically needed by the FunctionalStatefulActor factory method.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     * @param messageJournal The message journal to use for message persistence
     * @param snapshotStore The snapshot store to use for state persistence
     */
    protected StatefulActor(ActorSystem system, String actorId, State initialState, 
                         BatchedMessageJournal<Message> messageJournal,
                         SnapshotStore<State> snapshotStore) {
        super(system, actorId, null, new ResizableMailboxConfig());
        this.persistenceProvider = PersistenceProviderRegistry.getInstance().getDefaultProvider();
        this.initialState = initialState;
        this.messageJournal = messageJournal;
        this.snapshotStore = snapshotStore;
        this.actorId = actorId;
        this.persistenceExecutor = createPersistenceExecutor(DEFAULT_PERSISTENCE_THREAD_POOL_SIZE);
        this.metrics = new ActorMetrics(actorId);
    }
    
    /**
     * Creates a thread pool for persistence operations.
     * 
     * @param poolSize The size of the thread pool
     * @return The created executor service
     */
    private ExecutorService createPersistenceExecutor(int poolSize) {
        if (poolSize <= 0) {
            poolSize = DEFAULT_PERSISTENCE_THREAD_POOL_SIZE;
        }
        logger.debug("Creating persistence thread pool with size {} for actor {}", poolSize, actorId);
        return Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "persistence-" + actorId + "-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    // CompletableFuture that completes when state initialization is done
    private final CompletableFuture<Void> stateInitializationFuture = new CompletableFuture<>();
    
    @Override
    protected void preStart() {
        super.preStart();
        
        // Register metrics
        metrics.register();
        MetricsRegistry.registerActorMetrics(actorId, metrics);
        
        // Initialize state asynchronously to avoid blocking the actor thread
        initializeState().thenRun(() -> {
            logger.debug("Actor {} state initialization completed", getActorId());
            stateInitializationFuture.complete(null);
        }).exceptionally(ex -> {
            logger.error("Actor {} state initialization failed", getActorId(), ex);
            stateInitializationFuture.completeExceptionally(ex);
            metrics.errorOccurred();
            return null;
        });
    }
    
    /**
     * Waits for the actor's state to be initialized before processing messages.
     * This is primarily useful for testing to ensure deterministic behavior.
     * 
     * @param timeoutMs The maximum time to wait in milliseconds
     * @return true if the state was initialized within the timeout, false otherwise
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean waitForStateInitialization(long timeoutMs) throws InterruptedException {
        try {
            stateInitializationFuture.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            logger.warn("Timed out waiting for actor {} state initialization", getActorId());
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            logger.error("Error during actor {} state initialization", getActorId(), e.getCause());
            return false;
        }
    }

    // CompletableFuture that completes when this actor's persistence executor has shut down
    private final CompletableFuture<Void> persistenceShutdownFuture = new CompletableFuture<>();
    
    @Override
    protected void postStop() {
        super.postStop();
        
        // Unregister metrics
        metrics.unregister();
        MetricsRegistry.unregisterActorMetrics(actorId);
        
        // Ensure the final state is persisted before stopping
        if (stateInitialized && currentState.get() != null) {
            if (stateChanged) {
                // Take a final snapshot and wait for it to complete
                logger.debug("Taking final snapshot for actor {} before shutdown", actorId);
                try {
                    takeSnapshot().join();
                } catch (Exception e) {
                    logger.error("Error taking snapshot for actor {}", actorId, e);
                    metrics.errorOccurred();
                }
            }
        }
        
        // Shutdown the persistence executor
        logger.debug("Shutting down persistence executor for actor {}", actorId);
        persistenceExecutor.shutdown();
        
        // Create a non-daemon thread for proper shutdown handling
        // This is important for ensuring resources are properly cleaned up
        Thread shutdownMonitor = new Thread(() -> {
            try {
                // Wait for persistence executor to terminate with timeout
                if (!persistenceExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.warn("Persistence executor for actor {} did not terminate in time, forcing shutdown", actorId);
                    
                    // Get list of running tasks before shutdown for diagnostic purposes
                    List<Runnable> pendingTasks = persistenceExecutor.shutdownNow();
                    if (!pendingTasks.isEmpty()) {
                        logger.warn("Actor {} had {} pending persistence tasks at shutdown", 
                                actorId, pendingTasks.size());
                    }
                    
                    // Final termination attempt with shorter timeout
                    if (!persistenceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.error("Persistence executor for actor {} could not be terminated after forced shutdown", 
                                actorId);
                    }
                }
                
                logger.debug("Persistence executor for actor {} has terminated", actorId);
                persistenceShutdownFuture.complete(null);
            } catch (InterruptedException e) {
                logger.warn("Interrupted while waiting for persistence executor to terminate", e);
                Thread.currentThread().interrupt();
                persistenceExecutor.shutdownNow();
                persistenceShutdownFuture.completeExceptionally(e);
            } catch (Exception e) {
                logger.error("Error during persistence executor shutdown for actor {}", actorId, e);
                persistenceExecutor.shutdownNow();
                persistenceShutdownFuture.completeExceptionally(e);
            }
        }, "shutdown-monitor-" + actorId);
        
        // Make it a daemon thread to avoid preventing JVM shutdown in extreme cases
        // This is a safety measure, but the thread should complete normally in most cases
        shutdownMonitor.setDaemon(true);
        
        // Set higher priority to ensure shutdown completes promptly
        shutdownMonitor.setPriority(Thread.NORM_PRIORITY + 1);
        
        // Start the shutdown monitor thread
        shutdownMonitor.start();
        
        // As an additional safety measure, register a JVM shutdown hook to ensure
        // the executor is terminated if the JVM is shutting down
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!persistenceExecutor.isTerminated()) {
                logger.warn("Forcing termination of persistence executor for actor {} during JVM shutdown", actorId);
                persistenceExecutor.shutdownNow();
            }
        }, "shutdown-hook-" + actorId));
    }
    
    /**
     * Gets a CompletableFuture that completes when this actor's persistence executor has shut down.
     * This is useful for the ActorSystem to wait for all persistence operations to complete during shutdown.
     * 
     * @return A CompletableFuture that completes when the persistence executor has shut down
     */
    public CompletableFuture<Void> getPersistenceShutdownFuture() {
        return persistenceShutdownFuture;
    }

    @Override
    protected void handleException(Message message, Throwable exception) {
        errorHook.accept(exception);
        super.handleException(message, exception);
    }

    @Override
    protected final void receive(Message message) {
        if (!stateInitialized) {
            logger.debug("Actor {} received message before state was initialized, delaying processing", getActorId());
            // Wait for state initialization to complete, then process the message
            stateInitializationFuture.thenRun(() -> {
                logger.debug("Actor {} processing delayed message after state initialization", getActorId());
                // Re-deliver the message now that state is initialized
                tell(message);
            }).exceptionally(ex -> {
                logger.error("Error initializing state for actor {}", getActorId(), ex);
                errorHook.accept(ex);
                handleException(message, ex);
                metrics.errorOccurred();
                return null;
            });
            return;
        }

        // Record that a message was received
        metrics.messageReceived();
        long startTime = System.nanoTime();

        // First, journal the message
        messageJournal.append(actorId, message)
            .thenAccept(sequenceNumber -> {
                // Use retry strategy for message processing
                executeWithRetry(() -> {
                    CompletableFuture<Void> future = new CompletableFuture<>();
                    try {
                        // Process the message and update the state
                        State currentStateValue = currentState.get();
                        State newState = processMessage(currentStateValue, message);

                        // Update the last processed sequence number
                        lastProcessedSequence.set(sequenceNumber);

                        // Only update and persist if the state has changed
                        if (newState != currentStateValue && (newState == null || !newState.equals(currentStateValue))) {
                            currentState.set(newState);
                            stateChanged = true;
                            changesSinceLastSnapshot++;
                            
                            // Record state change in metrics
                            metrics.stateChanged();
                            
                            // Take snapshots using adaptive strategy
                            long now = System.currentTimeMillis();
                            if (now - lastSnapshotTime > snapshotIntervalMs || 
                                changesSinceLastSnapshot >= changesBeforeSnapshot) {
                                takeSnapshot().thenRun(() -> {
                                    stateChanged = false;
                                    changesSinceLastSnapshot = 0;
                                    
                                    // Record snapshot in metrics
                                    metrics.snapshotTaken();
                                });
                                lastSnapshotTime = now;
                            }
                        }
                        
                        future.complete(null);
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                    return future;
                }).exceptionally(e -> {
                    logger.error("Error processing message in actor {} after retries", actorId, e);
                    errorHook.accept(e);
                    handleException(message, e);
                    metrics.errorOccurred();
                    return null;
                });
                
                // Record message processing time
                long processingTime = System.nanoTime() - startTime;
                metrics.messageProcessed(processingTime);
            })
            .exceptionally(e -> {
                logger.error("Error journaling message for actor {}", actorId, e);
                errorHook.accept(e);
                handleException(message, new ActorException("Failed to journal message", e));
                metrics.errorOccurred();
                return null;
            });
    }

    /**
     * Process a message and return the new state.
     * This method must be implemented by subclasses to define the actor's behavior.
     *
     * @param state The current state
     * @param message The message to process
     * @return The new state after processing the message
     */
    protected abstract State processMessage(State state, Message message);

    /**
     * Gets the current state of the actor.
     *
     * @return The current state
     */
    public State getState() {
        return currentState.get();
    }

    /**
     * Updates the state with a new value and takes a snapshot.
     *
     * @param newState The new state
     * @return A CompletableFuture that completes when the state has been snapshotted
     */
    protected CompletableFuture<Void> updateState(State newState) {
        State oldState = currentState.get();
        if (newState != oldState && (newState == null || !newState.equals(oldState))) {
            currentState.set(newState);
            stateChanged = true;
            changesSinceLastSnapshot++;
            
            // Record state change in metrics
            metrics.stateChanged();
            
            return takeSnapshot().thenRun(() -> {
                stateChanged = false;
                changesSinceLastSnapshot = 0;
                
                // Record snapshot in metrics
                metrics.snapshotTaken();
            });
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Initializes the actor's state by:
     * 1. Trying to load the latest snapshot
     * 2. If a snapshot exists, loading it and setting the current state
     * 3. Replaying any messages that were received after the snapshot
     * 4. If no snapshot exists, using the initial state
     *
     * @return A CompletableFuture that completes when the state has been initialized
     */
    protected CompletableFuture<Void> initializeState() {
        if (stateInitialized) {
            logger.debug("Actor {} state already initialized, skipping", actorId);
            return CompletableFuture.completedFuture(null);
        }

        logger.debug("Actor {} initializing state", actorId);
        
        // Try to recover from snapshot + message replay
        // Run state initialization on the persistence thread pool
        return runAsync(() -> recoverFromSnapshotAndJournal()
                .thenCompose(recovered -> {
                    if (recovered) {
                        // Successfully recovered from snapshot and journal
                        logger.debug("Actor {} state recovered from snapshot and journal", actorId);
                        stateInitialized = true;
                        return CompletableFuture.completedFuture(null);
                    } else {
                        // Use initial state
                        logger.debug("Actor {} using initial state", actorId);
                        if (initialState != null) {
                            currentState.set(initialState);
                            logger.debug("Actor {} state initialized with provided initial state", actorId);
                            // Take an initial snapshot with the initial state
                            lastSnapshotTime = System.currentTimeMillis(); // Record snapshot time
                            return takeSnapshot();
                        } else {
                            logger.debug("Actor {} state initialized with null state", actorId);
                            // Leave currentState as null
                            stateInitialized = true;
                            return CompletableFuture.completedFuture(null);
                        }
                    }
                })
                .thenRun(() -> {
                    // Ensure state is marked as initialized
                    stateInitialized = true;
                })
                .thenApply(v -> null));
    }

    /**
     * Attempts to recover the actor's state from the latest snapshot and by replaying messages from the journal.
     *
     * @return A CompletableFuture that completes with true if recovery was successful, false otherwise
     */
    private CompletableFuture<Boolean> recoverFromSnapshotAndJournal() {
        return snapshotStore.getLatestSnapshot(actorId)
                .thenCompose(snapshotOpt -> {
                    if (snapshotOpt.isPresent()) {
                        SnapshotEntry<State> snapshot = snapshotOpt.get();
                        long snapshotSequence = snapshot.getSequenceNumber();
                        
                        // Set the state from the snapshot
                        currentState.set(snapshot.getState());
                        lastProcessedSequence.set(snapshotSequence);
                        lastSnapshotTime = System.currentTimeMillis(); // Record snapshot time
                        
                        logger.debug("Actor {} recovered state from snapshot at sequence {}", 
                                actorId, snapshotSequence);
                        
                        // Replay messages after the snapshot sequence number
                        return replayMessages(snapshotSequence + 1)
                                .thenApply(v -> true);
                    } else {
                        // No snapshot found, try to replay all messages
                        return messageJournal.getHighestSequenceNumber(actorId)
                                .thenCompose(highestSeq -> {
                                    if (highestSeq >= 0) {
                                        // There are messages in the journal, start with initial state and replay all
                                        if (initialState != null) {
                                            currentState.set(initialState);
                                            return replayMessages(0)
                                                    .thenApply(v -> true);
                                        } else {
                                            // Cannot replay messages without an initial state
                                            logger.warn("Actor {} cannot replay messages without an initial state", actorId);
                                            return CompletableFuture.completedFuture(false);
                                        }
                                    } else {
                                        // No messages in journal either
                                        return CompletableFuture.completedFuture(false);
                                    }
                                });
                    }
                })
                .exceptionally(e -> {
                    logger.error("Error recovering state for actor {}", actorId, e);
                    return false;
                });
    }

    /**
     * Replays messages from the journal starting from the specified sequence number.
     *
     * @param fromSequence The sequence number to start replaying from
     * @return A CompletableFuture that completes when all messages have been replayed
     */
    private CompletableFuture<Void> replayMessages(long fromSequence) {
        return messageJournal.readFrom(actorId, fromSequence)
                .thenCompose(entries -> {
                    if (entries.isEmpty()) {
                        logger.debug("No messages to replay for actor {} from sequence {}", 
                                actorId, fromSequence);
                        return CompletableFuture.completedFuture(null);
                    }
                    
                    logger.debug("Replaying {} messages for actor {} from sequence {}", 
                            entries.size(), actorId, fromSequence);
                    
                    // Process each message in sequence
                    long lastSeq = -1;
                    boolean stateWasChanged = false;
                    int processedCount = 0;
                    int errorCount = 0;
                    
                    for (JournalEntry<Message> entry : entries) {
                        try {
                            State currentStateValue = currentState.get();
                            long seqNum = entry.getSequenceNumber();
                            Message msg = entry.getMessage();
                            
                            logger.debug("Replaying message with sequence {} for actor {}: {}", 
                                    seqNum, actorId, msg.getClass().getSimpleName());
                            
                            State newState = processMessage(currentStateValue, msg);
                            
                            // Only update if state has changed
                            if (newState != currentStateValue && (newState == null || !newState.equals(currentStateValue))) {
                                currentState.set(newState);
                                stateWasChanged = true;
                                changesSinceLastSnapshot++;
                                logger.debug("State changed during replay for actor {} at sequence {}", 
                                        actorId, seqNum);
                            }
                            
                            lastSeq = seqNum;
                            processedCount++;
                        } catch (Exception e) {
                            errorCount++;
                            logger.error("Error replaying message with sequence {} for actor {}", 
                                    entry.getSequenceNumber(), actorId, e);
                            // Continue with next message
                        }
                    }
                    
                    if (lastSeq >= 0) {
                        lastProcessedSequence.set(lastSeq);
                        logger.debug("Updated last processed sequence to {} for actor {}", 
                                lastSeq, actorId);
                    }
                    
                    logger.debug("Completed replaying {} messages for actor {} (processed: {}, errors: {}, state changed: {})", 
                            entries.size(), actorId, processedCount, errorCount, stateWasChanged);
                    
                    // If state changed during replay, take a snapshot
                    if (stateWasChanged) {
                        stateChanged = true;
                        lastSnapshotTime = System.currentTimeMillis();
                        
                        logger.debug("Taking snapshot after message replay for actor {}", actorId);
                        return takeSnapshot().thenApply(v -> {
                            changesSinceLastSnapshot = 0; // Reset changes counter after snapshot
                            return null;
                        });
                    }
                    
                    return CompletableFuture.completedFuture(null);
                });
    }

    /**
     * Forces initialization of the actor's state and waits for it to complete.
     * This is primarily useful for testing to ensure deterministic behavior.
     * 
     * @return A CompletableFuture that completes when the state has been initialized
     */
    public CompletableFuture<Void> forceInitializeState() {
        return stateInitializationFuture;
    }

    /**
     * Takes a snapshot of the current state and persists it to the snapshot store.
     * Also truncates the message journal to remove messages that are included in the snapshot.
     *
     * @return A CompletableFuture that completes when the snapshot has been taken
     */
    private CompletableFuture<Void> takeSnapshot() {
        if (!stateInitialized) {
            return CompletableFuture.completedFuture(null);
        }
        
        State state = currentState.get();
        if (state == null) {
            logger.debug("Not taking snapshot for actor {} because state is null", actorId);
            return CompletableFuture.completedFuture(null);
        }
        
        long sequence = lastProcessedSequence.get();
        logger.debug("Taking snapshot for actor {} at sequence {}", actorId, sequence);
        
        return snapshotStore.saveSnapshot(actorId, state, sequence)
                .thenRun(() -> {
                    logger.debug("Snapshot taken for actor {} at sequence {}", actorId, sequence);
                    metrics.snapshotTaken();
                })
                .exceptionally(e -> {
                    logger.error("Error taking snapshot for actor {}", actorId, e);
                    metrics.errorOccurred();
                    return null;
                });
    }

    /**
     * Clears the actor's state and deletes all snapshots and journal entries.
     *
     * @return A CompletableFuture that completes when the state has been cleared
     */
    protected CompletableFuture<Void> clearState() {
        currentState.set(null);
        lastProcessedSequence.set(-1);
        
        // Run state clearing on the persistence thread pool
        return runAsync(() -> CompletableFuture.allOf(
                snapshotStore.deleteSnapshots(actorId),
                messageJournal.truncateBefore(actorId, Long.MAX_VALUE)
        ));
    }

    /**
     * Resets the actor's state to the initial state and takes a new snapshot.
     * Also clears the message journal.
     *
     * @return A CompletableFuture that completes when the state has been reset
     */
    protected CompletableFuture<Void> resetState() {
        currentState.set(initialState);
        lastProcessedSequence.set(-1);
        
        // Run state reset on the persistence thread pool
        return runAsync(() -> snapshotStore.deleteSnapshots(actorId)
                .thenCompose(v -> messageJournal.truncateBefore(actorId, Long.MAX_VALUE))
                .thenCompose(v -> {
                    // Take a new snapshot with the initial state
                    return snapshotStore.saveSnapshot(actorId, initialState, -1);
                }));
    }

    /**
     * Gets the sequence number of the last processed message.
     *
     * @return The sequence number
     */
    protected long getLastProcessedSequence() {
        return lastProcessedSequence.get();
    }

    /**
     * Forces a snapshot to be taken immediately.
     *
     * @return A CompletableFuture that completes when the snapshot has been taken
     */
    protected CompletableFuture<Void> forceSnapshot() {
        CompletableFuture<Void> result = takeSnapshot();
        lastSnapshotTime = System.currentTimeMillis();
        stateChanged = false;
        changesSinceLastSnapshot = 0; // Reset changes counter
        return result;
    }

    /**
     * Gets the persistence executor service used for asynchronous state operations.
     *
     * @return The persistence executor service
     */
    protected ExecutorService getPersistenceExecutor() {
        return persistenceExecutor;
    }

    /**
     * Configures the adaptive snapshot strategy.
     *
     * @param intervalMs Time interval in milliseconds between snapshots
     * @param changesThreshold Number of state changes before taking a snapshot
     */
    protected void configureSnapshotStrategy(long intervalMs, int changesThreshold) {
        if (intervalMs > 0) {
            this.snapshotIntervalMs = intervalMs;
        }
        if (changesThreshold > 0) {
            this.changesBeforeSnapshot = changesThreshold;
        }
        logger.debug("Actor {} snapshot strategy configured: interval={}ms, changesThreshold={}", 
                actorId, snapshotIntervalMs, changesBeforeSnapshot);
    }

    /**
     * Gets the current snapshot interval in milliseconds.
     *
     * @return The snapshot interval in milliseconds
     */
    protected long getSnapshotIntervalMs() {
        return snapshotIntervalMs;
    }

    /**
     * Gets the current changes threshold before taking a snapshot.
     *
     * @return The number of changes before taking a snapshot
     */
    protected int getChangesBeforeSnapshot() {
        return changesBeforeSnapshot;
    }

    /**
     * Gets the number of state changes since the last snapshot.
     *
     * @return The number of changes since the last snapshot
     */
    protected int getChangesSinceLastSnapshot() {
        return changesSinceLastSnapshot;
    }

    /**
     * Gets the metrics for this actor.
     *
     * @return The actor metrics
     */
    public ActorMetrics getMetrics() {
        return metrics;
    }
    
    /**
     * Sets a custom retry strategy for this actor.
     *
     * @param retryStrategy The retry strategy to use
     * @return This actor instance for method chaining
     */
    public StatefulActor<State, Message> withRetryStrategy(RetryStrategy retryStrategy) {
        this.retryStrategy = retryStrategy;
        return this;
    }
    
    /**
     * Sets an error hook to be notified when exceptions occur.
     *
     * @param errorHook The error hook to invoke with exceptions
     * @return This actor instance for method chaining
     */
    public StatefulActor<State, Message> withErrorHook(Consumer<Throwable> errorHook) {
        this.errorHook = errorHook;
        return this;
    }
    
    /**
     * Executes an operation with retry logic.
     *
     * @param operation The operation to execute
     * @param <T> The return type of the operation
     * @return A CompletableFuture that completes with the result of the operation or exceptionally if all retries fail
     */
    protected <T> CompletableFuture<T> executeWithRetry(Supplier<CompletableFuture<T>> operation) {
        return retryStrategy.executeWithRetry(operation, getPersistenceExecutor());
    }

    /**
     * Helper method to run a task asynchronously on the persistence thread pool.
     * 
     * @param supplier The task to run
     * @return A CompletableFuture that completes when the task completes
     */
    private <T> CompletableFuture<T> runAsync(Supplier<CompletableFuture<T>> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                supplier.get().thenAccept(future::complete)
                       .exceptionally(e -> {
                           future.completeExceptionally(e);
                           return null;
                       });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }, persistenceExecutor);
        return future;
    }
    
    /**
     * Helper method to convert a MailboxConfig to a ResizableMailboxConfig.
     * 
     * @param mailboxConfig The mailbox configuration to convert
     * @return A ResizableMailboxConfig with the same settings
     */
    private static ResizableMailboxConfig convertToResizableMailboxConfig(MailboxConfig mailboxConfig) {
        if (mailboxConfig instanceof ResizableMailboxConfig) {
            return (ResizableMailboxConfig) mailboxConfig;
        }
        
        ResizableMailboxConfig config = new ResizableMailboxConfig();
        config.setInitialCapacity(mailboxConfig.getInitialCapacity());
        config.setMaxCapacity(mailboxConfig.getMaxCapacity());
        
        // These methods might not be available in the base MailboxConfig class
        // so we'll use try-catch to handle potential errors
        try {
            config.setResizeThreshold(mailboxConfig.getResizeThreshold());
        } catch (Exception e) {
            // Use default value if method not available
        }
        
        try {
            config.setResizeFactor(mailboxConfig.getResizeFactor());
        } catch (Exception e) {
            // Use default value if method not available
        }
        
        return config;
    }
}
