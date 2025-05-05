package systems.cajun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.cajun.persistence.StateStore;
import systems.cajun.persistence.StateStoreFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * An actor that maintains and persists its state.
 * StatefulActor extends the base Actor class by adding state management capabilities.
 * The state is persisted using a configurable StateStore implementation.
 *
 * @param <State> The type of the actor's state
 * @param <Message> The type of messages the actor can process
 */
public abstract class StatefulActor<State, Message> extends Actor<Message> {

    private static final Logger logger = LoggerFactory.getLogger(StatefulActor.class);

    private final StateStore<String, State> stateStore;
    private final AtomicReference<State> currentState = new AtomicReference<>();
    private final String stateKey;
    private final State initialState;
    private boolean stateInitialized = false;
    private boolean stateChanged = false;
    private long lastPersistTime = 0;
    private static final long PERSIST_INTERVAL_MS = 1000; // 1 second

    /**
     * Creates a new StatefulActor with an in-memory state store.
     *
     * @param system The actor system
     * @param initialState The initial state of the actor
     */
    public StatefulActor(ActorSystem system, State initialState) {
        this(system, initialState, StateStoreFactory.createInMemoryStore());
    }

    /**
     * Creates a new StatefulActor with a specified state store.
     *
     * @param system The actor system
     * @param initialState The initial state of the actor
     * @param stateStore The state store to use for persistence
     */
    public StatefulActor(ActorSystem system, State initialState, StateStore<String, State> stateStore) {
        super(system);
        this.initialState = initialState;
        this.stateStore = stateStore;
        this.stateKey = getActorId();
    }

    /**
     * Creates a new StatefulActor with a custom actor ID and an in-memory state store.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     */
    public StatefulActor(ActorSystem system, String actorId, State initialState) {
        this(system, actorId, initialState, StateStoreFactory.createInMemoryStore());
    }

    /**
     * Creates a new StatefulActor with a custom actor ID and a specified state store.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state of the actor
     * @param stateStore The state store to use for persistence
     */
    public StatefulActor(ActorSystem system, String actorId, State initialState, StateStore<String, State> stateStore) {
        super(system, actorId);
        this.initialState = initialState;
        this.stateStore = stateStore;
        this.stateKey = actorId;
    }

    @Override
    protected void preStart() {
        super.preStart();
        initializeState().join(); // Wait for state initialization to complete
    }

    @Override
    protected void postStop() {
        super.postStop();
        // Ensure the final state is persisted before stopping
        if (stateInitialized && currentState.get() != null) {
            if (stateChanged) {
                persistState().join();
            }
        }
    }

    @Override
    protected final void receive(Message message) {
        if (!stateInitialized) {
            logger.warn("Actor {} received message before state was initialized, delaying processing", getActorId());
            // Re-queue the message to be processed after state initialization
            CompletableFuture.runAsync(() -> {
                try {
                    initializeState().join();
                    tell(message);
                } catch (Exception e) {
                    logger.error("Error initializing state for actor {}", getActorId(), e);
                    handleException(message, e);
                }
            });
            return;
        }

        try {
            // Process the message and update the state
            State currentStateValue = currentState.get();
            State newState = processMessage(currentStateValue, message);

            // Only update and persist if the state has changed
            if (newState != currentStateValue && (newState == null || !newState.equals(currentStateValue))) {
                currentState.set(newState);
                stateChanged = true;
                
                // Only persist periodically or if too many changes accumulated
                long now = System.currentTimeMillis();
                if (now - lastPersistTime > PERSIST_INTERVAL_MS) {
                    persistState();
                    lastPersistTime = now;
                    stateChanged = false;
                }
            }
        } catch (Exception e) {
            logger.error("Error processing message in actor {}", getActorId(), e);
            handleException(message, e);
        }
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
     * Updates the state with a new value and persists it.
     *
     * @param newState The new state
     * @return A CompletableFuture that completes when the state has been persisted
     */
    protected CompletableFuture<Void> updateState(State newState) {
        currentState.set(newState);
        return persistState();
    }

    /**
     * Updates the state by applying a function to the current state and persists it.
     *
     * @param updateFunction A function that takes the current state and returns a new state
     * @return A CompletableFuture that completes when the state has been persisted
     */
    protected CompletableFuture<Void> updateState(Function<State, State> updateFunction) {
        State newState = updateFunction.apply(currentState.get());
        currentState.set(newState);
        return persistState();
    }

    /**
     * Initializes the actor's state from the state store or with the initial state.
     *
     * @return A CompletableFuture that completes when the state has been initialized
     */
    private CompletableFuture<Void> initializeState() {
        if (stateInitialized) {
            return CompletableFuture.completedFuture(null);
        }

        return stateStore.get(stateKey)
                .thenApply(optionalState -> {
                    State state = optionalState.orElse(initialState);
                    currentState.set(state);
                    stateInitialized = true;
                    logger.debug("Actor {} state initialized: {}", getActorId(), 
                                 optionalState.isPresent() ? "from store" : "with initial state");
                    return null;
                })
                .exceptionally(e -> {
                    logger.error("Error loading state for actor {}", getActorId(), e);
                    // Fall back to initial state
                    currentState.set(initialState);
                    stateInitialized = true;
                    return null;
                }).thenApply(v -> null);
    }

    /**
     * Persists the current state to the state store.
     *
     * @return A CompletableFuture that completes when the state has been persisted
     */
    private CompletableFuture<Void> persistState() {
        State state = currentState.get();
        if (state == null) {
            return stateStore.delete(stateKey);
        } else {
            return stateStore.put(stateKey, state)
                    .exceptionally(e -> {
                        logger.error("Error persisting state for actor {}", getActorId(), e);
                        return null;
                    }).thenApply(v -> null);
        }
    }

    /**
     * Clears the actor's state from the state store.
     *
     * @return A CompletableFuture that completes when the state has been cleared
     */
    protected CompletableFuture<Void> clearState() {
        currentState.set(null);
        return stateStore.delete(stateKey);
    }

    /**
     * Resets the actor's state to the initial state and persists it.
     *
     * @return A CompletableFuture that completes when the state has been reset
     */
    protected CompletableFuture<Void> resetState() {
        currentState.set(initialState);
        return persistState();
    }
}
