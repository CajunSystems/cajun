package systems.cajun.handler;

import systems.cajun.ActorContext;

/**
 * Interface for handling messages in a stateless actor.
 * This provides a clean separation between the actor implementation and message handling logic.
 *
 * @param <Message> The type of messages this handler processes
 */
public interface Handler<Message> {
    
    /**
     * Processes a message.
     *
     * @param message The message to process
     * @param context The actor context providing access to actor functionality
     */
    void receive(Message message, ActorContext context);
    
    /**
     * Called before the actor starts processing messages.
     * Override to perform initialization logic.
     *
     * @param context The actor context providing access to actor functionality
     */
    default void preStart(ActorContext context) {
        // Default implementation does nothing
    }
    
    /**
     * Called after the actor has stopped processing messages.
     * Override to perform cleanup logic.
     *
     * @param context The actor context providing access to actor functionality
     */
    default void postStop(ActorContext context) {
        // Default implementation does nothing
    }
    
    /**
     * Called when an exception occurs during message processing.
     * Override to provide custom error handling.
     *
     * @param message   The message that caused the exception
     * @param exception The exception that was thrown
     * @param context   The actor context providing access to actor functionality
     * @return true if the message should be reprocessed, false otherwise
     */
    default boolean onError(Message message, Throwable exception, ActorContext context) {
        // Default implementation doesn't reprocess
        return false;
    }
}
