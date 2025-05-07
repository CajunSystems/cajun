package systems.cajun.persistence;

import java.io.Serializable;

/**
 * Adapter that wraps regular messages into OperationAwareMessage instances.
 * This allows stateless actors to send regular messages to stateful actors without
 * requiring the original messages to implement OperationAwareMessage.
 *
 * @param <T> The type of the original message
 */
public class MessageAdapter<T extends Serializable> implements OperationAwareMessage {
    private static final long serialVersionUID = 1L;
    
    private final T originalMessage;
    private final boolean isReadOnly;
    
    /**
     * Creates a new MessageAdapter wrapping the original message.
     *
     * @param originalMessage The original message to wrap
     * @param isReadOnly Whether this message is a read-only operation
     */
    public MessageAdapter(T originalMessage, boolean isReadOnly) {
        this.originalMessage = originalMessage;
        this.isReadOnly = isReadOnly;
    }
    
    /**
     * Gets the original message that was wrapped.
     *
     * @return The original message
     */
    public T getOriginalMessage() {
        return originalMessage;
    }
    
    @Override
    public boolean isReadOnly() {
        return isReadOnly;
    }
    
    /**
     * Creates a read-only message adapter for the given message.
     *
     * @param <T> The type of the original message
     * @param message The message to wrap
     * @return A new MessageAdapter instance marked as read-only
     */
    public static <T extends Serializable> MessageAdapter<T> readOnly(T message) {
        return new MessageAdapter<>(message, true);
    }
    
    /**
     * Creates a write operation message adapter for the given message.
     *
     * @param <T> The type of the original message
     * @param message The message to wrap
     * @return A new MessageAdapter instance marked as a write operation
     */
    public static <T extends Serializable> MessageAdapter<T> writeOp(T message) {
        return new MessageAdapter<>(message, false);
    }
    
    /**
     * Unwraps the original message if it's a MessageAdapter, otherwise returns the original message.
     * This is useful when you want to get the original message regardless of whether it's wrapped.
     *
     * @param <T> The expected type of the original message
     * @param message The message that might be wrapped
     * @return The unwrapped message
     * @throws ClassCastException if the message is not of the expected type
     */
    @SuppressWarnings("unchecked")
    public static <T> T unwrap(Object message) {
        if (message instanceof MessageAdapter) {
            return (T) ((MessageAdapter<?>) message).getOriginalMessage();
        }
        return (T) message;
    }
    
    @Override
    public String toString() {
        return "MessageAdapter[" + originalMessage + ", readOnly=" + isReadOnly + "]";
    }
}
