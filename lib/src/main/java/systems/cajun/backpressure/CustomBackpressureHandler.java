package systems.cajun.backpressure;

/**
 * Interface for implementing custom backpressure handling logic.
 * This allows actors to define their own rules for accepting or rejecting
 * messages under backpressure conditions.
 *
 * @param <T> The type of messages being handled
 */
public interface CustomBackpressureHandler<T> {
    
    /**
     * Determines how a message should be handled when the actor is under backpressure.
     *
     * @param message The message to be sent
     * @param event The current backpressure event with metrics
     * @return A BackpressureAction indicating what action to take with the message
     */
    BackpressureAction handleMessage(T message, BackpressureEvent event);
    
    /**
     * Actions that can be taken when handling a message under backpressure.
     */
    enum BackpressureAction {
        /**
         * Accept the message despite backpressure.
         */
        ACCEPT,
        
        /**
         * Reject the message due to backpressure.
         */
        REJECT,
        
        /**
         * Try to send the message with a timeout.
         */
        RETRY_WITH_TIMEOUT,
        
        /**
         * Modify the mailbox to make room (e.g., by dropping old messages).
         */
        MAKE_ROOM
    }
    
    /**
     * Determines if a message should be accepted based on the current mailbox state
     * and the message send options.
     *
     * @param mailboxSize The current size of the mailbox
     * @param capacity The maximum capacity of the mailbox
     * @param options The options for sending the message
     * @return true if the message should be accepted, false otherwise
     */
    boolean shouldAccept(int mailboxSize, int capacity, BackpressureSendOptions options);
}
