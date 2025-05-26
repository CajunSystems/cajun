package systems.cajun.handler;

import systems.cajun.ActorContext;

/**
 * Interface for handling messages in a stateful actor.
 * This provides a clean separation between the actor implementation and message handling logic.
 *
 * @param <State> The type of the actor's state
 * @param <Message> The type of messages this handler processes
 */
public interface StatefulHandler<State, Message> {
    
    /**
     * Processes a message and potentially updates the state.
     *
     * @param message The message to process
     * @param state The current state of the actor
     * @param context The actor context providing access to actor functionality
     * @return The new state (or the same state if no change is needed)
     */
    State receive(Message message, State state, ActorContext context);
    
    /**
     * Called before the actor starts processing messages.
     * Override to perform initialization logic.
     *
     * @param state The initial state of the actor
     * @param context The actor context providing access to actor functionality
     * @return The potentially modified initial state
     */
    default State preStart(State state, ActorContext context) {
        // Default implementation returns the state unchanged
        return state;
    }
    
    /**
     * Called after the actor has stopped processing messages.
     * Override to perform cleanup logic.
     *
     * @param state The final state of the actor
     * @param context The actor context providing access to actor functionality
     */
    default void postStop(State state, ActorContext context) {
        // Default implementation does nothing
    }
    
    /**
     * Called when an exception occurs during message processing.
     * Override to provide custom error handling.
     *
     * @param message The message that caused the exception
     * @param state The current state when the exception occurred
     * @param exception The exception that was thrown
     * @param context The actor context providing access to actor functionality
     * @return true if the message should be reprocessed, false otherwise
     */
    default boolean onError(Message message, State state, Throwable exception, ActorContext context) {
        // Default implementation doesn't reprocess
        return false;
    }
}
