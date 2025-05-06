package systems.cajun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.cajun.persistence.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * An actor that maintains and persists its state.
 * StatefulActor extends the base Actor class by adding state management capabilities.
 * 
 * This implementation supports message persistence and replay for recovery using a hybrid approach:
 * - Messages are logged to a journal before processing
 * - State snapshots are taken periodically
 * - Recovery uses the latest snapshot plus replay of subsequent messages
 *
 * @param <State> The type of the actor's state
 * @param <Message> The type of messages the actor can process
 */
public abstract class StatefulActor<State, Message> extends Actor<Message> {

    private static final Logger logger = LoggerFactory.getLogger(StatefulActor.class);

    private final MessageJournal<Message> messageJournal;
    private final SnapshotStore<State> snapshotStore;
    private final AtomicReference<State> currentState = new AtomicReference<>();
    private final AtomicLong lastProcessedSequence = new AtomicLong(-1);
    private final String actorId;
    private final State initialState;
    private boolean stateInitialized = false;
    private boolean stateChanged = false;
    private long lastSnapshotTime = 0;
    private static final long SNAPSHOT_INTERVAL_MS = 15000; // 15 seconds (increased frequency from 1 minute)

    /**
     * Creates a new StatefulActor with default persistence.
     *
     * @param system The actor system
     * @param initialState The initial state of the actor
     */
    public StatefulActor(ActorSystem system, State initialState) {
        this(system, initialState,
             PersistenceFactory.createFileMessageJournal(), 
             PersistenceFactory.createFileSnapshotStore());
    }

    /**
     * Creates a new StatefulActor with a custom message journal.
     *
     * @param system The actor system
     * @param initialState The initial state of the actor
     * @param messageJournal The message journal to use for message persistence
     */
    public StatefulActor(ActorSystem system, State initialState, MessageJournal<Message> messageJournal) {
        this(system, initialState, messageJournal, PersistenceFactory.createFileSnapshotStore());
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
             PersistenceFactory.createFileMessageJournal(),
             PersistenceFactory.createFileSnapshotStore());
    }

    /**
     * Creates a new StatefulActor with a custom actor ID and message journal.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     * @param messageJournal The message journal to use for message persistence
     */
    public StatefulActor(ActorSystem system, String actorId, State initialState, MessageJournal<Message> messageJournal) {
        this(system, actorId, initialState, messageJournal, PersistenceFactory.createFileSnapshotStore());
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
                         MessageJournal<Message> messageJournal,
                         SnapshotStore<State> snapshotStore) {
        super(system);
        this.initialState = initialState;
        this.messageJournal = messageJournal;
        this.snapshotStore = snapshotStore;
        this.actorId = getActorId();
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
                         MessageJournal<Message> messageJournal,
                         SnapshotStore<State> snapshotStore) {
        super(system, actorId);
        this.initialState = initialState;
        this.messageJournal = messageJournal;
        this.snapshotStore = snapshotStore;
        this.actorId = actorId;
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
            }
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
                        
                        // Take snapshots periodically
                        long now = System.currentTimeMillis();
                        if (now - lastSnapshotTime > SNAPSHOT_INTERVAL_MS) {
                            takeSnapshot();
                            lastSnapshotTime = now;
                            stateChanged = false;
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
        return takeSnapshot();
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
        return takeSnapshot();
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
        return recoverFromSnapshotAndJournal()
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
                        } else {
                            logger.debug("Actor {} state initialized with null state", actorId);
                            // Leave currentState as null
                        }
                        stateInitialized = true;
                        
                        // Take an initial snapshot with the initial state if it's not null
                        if (initialState != null) {
                            return takeSnapshot();
                        } else {
                            return CompletableFuture.completedFuture(null);
                        }
                    }
                })
                .thenApply(v -> null);
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
                        
                        logger.debug("Actor {} recovered state from snapshot at sequence {}", 
                                actorId, snapshotSequence);
                        
                        // Replay messages after the snapshot sequence number
                        return replayMessages(snapshotSequence + 1)
                                .thenApply(v -> {
                                    stateInitialized = true;
                                    return true;
                                });
                    } else {
                        // No snapshot found, try to replay all messages
                        return messageJournal.getHighestSequenceNumber(actorId)
                                .thenCompose(highestSeq -> {
                                    if (highestSeq >= 0) {
                                        // There are messages in the journal, start with initial state and replay all
                                        currentState.set(initialState);
                                        return replayMessages(0)
                                                .thenApply(v -> {
                                                    stateInitialized = true;
                                                    return true;
                                                });
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
                    for (JournalEntry<Message> entry : entries) {
                        try {
                            State currentStateValue = currentState.get();
                            State newState = processMessage(currentStateValue, entry.getMessage());
                            currentState.set(newState);
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
    private CompletableFuture<Void> takeSnapshot() {
        State state = currentState.get();
        long sequence = lastProcessedSequence.get();
        
        if (state == null) {
            logger.debug("Not taking snapshot for actor {} as state is null", actorId);
            return CompletableFuture.completedFuture(null);
        }
        
        logger.debug("Taking snapshot for actor {} at sequence {}", actorId, sequence);
        
        return snapshotStore.saveSnapshot(actorId, state, sequence)
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
        
        return CompletableFuture.allOf(
                snapshotStore.deleteSnapshots(actorId),
                messageJournal.truncateBefore(actorId, Long.MAX_VALUE)
        );
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
        
        return snapshotStore.deleteSnapshots(actorId)
                .thenCompose(v -> messageJournal.truncateBefore(actorId, Long.MAX_VALUE))
                .thenCompose(v -> {
                    // Take a new snapshot with the initial state
                    return snapshotStore.saveSnapshot(actorId, initialState, -1);
                });
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
        return takeSnapshot();
    }
}
