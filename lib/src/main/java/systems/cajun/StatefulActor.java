package systems.cajun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.cajun.persistence.BatchedMessageJournal;
import systems.cajun.persistence.SnapshotStore;
import systems.cajun.persistence.JournalEntry;
import systems.cajun.persistence.SnapshotEntry;
import systems.cajun.runtime.persistence.PersistenceFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

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
    
    // Dedicated thread pool for persistence operations
    private final ExecutorService persistenceExecutor;
    
    // Adaptive snapshot configuration
    private static final long DEFAULT_SNAPSHOT_INTERVAL_MS = 15000; // 15 seconds default
    private static final int DEFAULT_CHANGES_BEFORE_SNAPSHOT = 100; // Default number of changes before snapshot
    private long snapshotIntervalMs = DEFAULT_SNAPSHOT_INTERVAL_MS;
    private int changesBeforeSnapshot = DEFAULT_CHANGES_BEFORE_SNAPSHOT;
    
    // Configuration for the persistence thread pool
    private static final int DEFAULT_PERSISTENCE_THREAD_POOL_SIZE = 2;

    /**
     * Creates a new StatefulActor with default persistence.
     *
     * @param system The actor system
     * @param initialState The initial state of the actor
     */
    public StatefulActor(ActorSystem system, State initialState) {
        this(system, initialState,
             PersistenceFactory.createBatchedFileMessageJournal(), 
             PersistenceFactory.createFileSnapshotStore(),
             DEFAULT_PERSISTENCE_THREAD_POOL_SIZE);
    }

    /**
     * Creates a new StatefulActor with a custom message journal.
     *
     * @param system The actor system
     * @param initialState The initial state of the actor
     * @param messageJournal The message journal to use for message persistence
     */
    public StatefulActor(ActorSystem system, State initialState, BatchedMessageJournal<Message> messageJournal) {
        this(system, initialState, messageJournal, PersistenceFactory.createFileSnapshotStore(), DEFAULT_PERSISTENCE_THREAD_POOL_SIZE);
    }

    /**
     * Creates a new StatefulActor with a custom actor ID and default persistence.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     */
    public StatefulActor(ActorSystem system, String actorId, State initialState) {
        this(system, actorId, initialState,
             PersistenceFactory.createBatchedFileMessageJournal(),
             PersistenceFactory.createFileSnapshotStore(),
             DEFAULT_PERSISTENCE_THREAD_POOL_SIZE);
    }

    /**
     * Creates a new StatefulActor with a custom actor ID and message journal.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     * @param messageJournal The message journal to use for message persistence
     */
    public StatefulActor(ActorSystem system, String actorId, State initialState, BatchedMessageJournal<Message> messageJournal) {
        this(system, actorId, initialState, messageJournal, PersistenceFactory.createFileSnapshotStore(), DEFAULT_PERSISTENCE_THREAD_POOL_SIZE);
    }
    
    /**
     * Creates a new StatefulActor with full persistence configuration.
     *
     * @param system The actor system
     * @param initialState The initial state of the actor
     * @param messageJournal The message journal to use for message persistence
     * @param snapshotStore The snapshot store to use for state snapshots
     */
    public StatefulActor(ActorSystem system, State initialState, 
                         BatchedMessageJournal<Message> messageJournal,
                         SnapshotStore<State> snapshotStore) {
        this(system, initialState, messageJournal, snapshotStore, DEFAULT_PERSISTENCE_THREAD_POOL_SIZE);
    }
    
    /**
     * Creates a new StatefulActor with full persistence configuration and custom thread pool size.
     *
     * @param system The actor system
     * @param initialState The initial state of the actor
     * @param messageJournal The message journal to use for message persistence
     * @param snapshotStore The snapshot store to use for state snapshots
     * @param persistenceThreadPoolSize The size of the thread pool for persistence operations
     */
    public StatefulActor(ActorSystem system, State initialState, 
                         BatchedMessageJournal<Message> messageJournal,
                         SnapshotStore<State> snapshotStore,
                         int persistenceThreadPoolSize) {
        super(system);
        this.initialState = initialState;
        this.messageJournal = messageJournal;
        this.snapshotStore = snapshotStore;
        this.actorId = getActorId();
        this.persistenceExecutor = createPersistenceExecutor(persistenceThreadPoolSize);
    }

    /**
     * Creates a new StatefulActor with a custom actor ID and full persistence configuration.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     * @param messageJournal The message journal to use for message persistence
     * @param snapshotStore The snapshot store to use for state snapshots
     */
    public StatefulActor(ActorSystem system, String actorId, State initialState, 
                         BatchedMessageJournal<Message> messageJournal,
                         SnapshotStore<State> snapshotStore) {
        this(system, actorId, initialState, messageJournal, snapshotStore, DEFAULT_PERSISTENCE_THREAD_POOL_SIZE);
    }
    
    /**
     * Creates a new StatefulActor with a custom actor ID, full persistence configuration, and custom thread pool size.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     * @param messageJournal The message journal to use for message persistence
     * @param snapshotStore The snapshot store to use for state snapshots
     * @param persistenceThreadPoolSize The size of the thread pool for persistence operations
     */
    public StatefulActor(ActorSystem system, String actorId, State initialState, 
                         BatchedMessageJournal<Message> messageJournal,
                         SnapshotStore<State> snapshotStore,
                         int persistenceThreadPoolSize) {
        super(system, actorId);
        this.initialState = initialState;
        this.messageJournal = messageJournal;
        this.snapshotStore = snapshotStore;
        this.actorId = actorId;
        this.persistenceExecutor = createPersistenceExecutor(persistenceThreadPoolSize);
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
        // Initialize state asynchronously to avoid blocking the actor thread
        initializeState().thenRun(() -> {
            logger.debug("Actor {} state initialization completed", getActorId());
            stateInitializationFuture.complete(null);
        }).exceptionally(ex -> {
            logger.error("Actor {} state initialization failed", getActorId(), ex);
            stateInitializationFuture.completeExceptionally(ex);
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

    @Override
    protected void postStop() {
        super.postStop();
        // Ensure the final state is persisted before stopping
        if (stateInitialized && currentState.get() != null) {
            if (stateChanged) {
                // Take a final snapshot
                takeSnapshot().join();
                // Reset the changes counter
                changesSinceLastSnapshot = 0;
            }
        }
        
        // Shutdown the persistence executor
        persistenceExecutor.shutdown();
        try {
            if (!persistenceExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                logger.warn("Persistence executor for actor {} did not terminate in time", actorId);
                persistenceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for persistence executor to terminate", e);
            Thread.currentThread().interrupt();
        }
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
                handleException(message, ex);
                return null;
            });
            return;
        }

        // First, journal the message
        messageJournal.append(actorId, message)
            .thenAccept(sequenceNumber -> {
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
                        
                        // Take snapshots using adaptive strategy
                        long now = System.currentTimeMillis();
                        if (now - lastSnapshotTime > snapshotIntervalMs || 
                            changesSinceLastSnapshot >= changesBeforeSnapshot) {
                            takeSnapshot().thenRun(() -> {
                                stateChanged = false;
                                changesSinceLastSnapshot = 0;
                            });
                            lastSnapshotTime = now;
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error processing message in actor {}", actorId, e);
                    handleException(message, e);
                }
            })
            .exceptionally(e -> {
                logger.error("Error journaling message for actor {}", actorId, e);
                handleException(message, new ActorException("Failed to journal message", e));
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
    protected State getState() {
        return currentState.get();
    }

    /**
     * Updates the state with a new value and takes a snapshot.
     *
     * @param newState The new state
     * @return A CompletableFuture that completes when the state has been snapshotted
     */
    protected CompletableFuture<Void> updateState(State newState) {
        currentState.set(newState);
        stateChanged = true;
        // Run snapshot asynchronously on the persistence thread pool
        return runAsync(() -> takeSnapshot());
    }

    /**
     * Updates the state by applying a function to the current state and takes a snapshot.
     *
     * @param updateFunction A function that takes the current state and returns a new state
     * @return A CompletableFuture that completes when the state has been snapshotted
     */
    protected CompletableFuture<Void> updateState(Function<State, State> updateFunction) {
        State newState = updateFunction.apply(currentState.get());
        currentState.set(newState);
        stateChanged = true;
        // Run snapshot asynchronously on the persistence thread pool
        return runAsync(() -> takeSnapshot());
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
                    
                    for (JournalEntry<Message> entry : entries) {
                        try {
                            State currentStateValue = currentState.get();
                            State newState = processMessage(currentStateValue, entry.getMessage());
                            
                            // Only update if state has changed
                            if (newState != currentStateValue && (newState == null || !newState.equals(currentStateValue))) {
                                currentState.set(newState);
                                stateWasChanged = true;
                            }
                            
                            lastSeq = entry.getSequenceNumber();
                        } catch (Exception e) {
                            logger.error("Error replaying message with sequence {} for actor {}", 
                                    entry.getSequenceNumber(), actorId, e);
                            // Continue with next message
                        }
                    }
                    
                    if (lastSeq >= 0) {
                        lastProcessedSequence.set(lastSeq);
                    }
                    
                    // If state changed during replay, take a snapshot
                    if (stateWasChanged) {
                        stateChanged = true;
                        lastSnapshotTime = System.currentTimeMillis();
                        changesSinceLastSnapshot = 0; // Reset changes counter after snapshot
                        return takeSnapshot();
                    }
                    
                    logger.debug("Completed replaying messages for actor {} up to sequence {}", 
                            actorId, lastSeq);
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
    
    private CompletableFuture<Void> takeSnapshot() {
        State state = currentState.get();
        long sequence = lastProcessedSequence.get();
        
        if (state == null) {
            logger.debug("Not taking snapshot for actor {} as state is null", actorId);
            return CompletableFuture.completedFuture(null);
        }
        
        logger.debug("Taking snapshot for actor {} at sequence {}", actorId, sequence);
        
        // Use the persistence thread pool for the snapshot operation
        return runAsync(() -> snapshotStore.saveSnapshot(actorId, state, sequence)
                .thenCompose(v -> {
                    // Truncate the journal to remove messages that are included in the snapshot
                    // We keep the last message to ensure continuity
                    if (sequence > 0) {
                        return messageJournal.truncateBefore(actorId, sequence);
                    } else {
                        return CompletableFuture.completedFuture(null);
                    }
                })
                .exceptionally(e -> {
                    logger.error("Error taking snapshot for actor {}", actorId, e);
                    stateChanged = true; // Mark as changed so we'll try again later
                    return null;
                }));
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
}
