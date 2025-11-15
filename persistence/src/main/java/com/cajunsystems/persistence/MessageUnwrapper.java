package com.cajunsystems.persistence;

/**
 * Utility class for unwrapping messages that have been adapted using MessageAdapter.
 * This is useful in the processMessage method of a stateful actor to get the original
 * message regardless of whether it was sent directly or via the tellStateful/tellReadOnly methods.
 */
public class MessageUnwrapper {
    
    /**
     * Unwraps a message if it's a MessageAdapter, otherwise returns the original message.
     * This is useful in the processMessage method of a stateful actor to get the original
     * message regardless of whether it was sent directly or via the tellStateful/tellReadOnly methods.
     *
     * @param <T> The expected type of the original message
     * @param message The message that might be wrapped
     * @return The unwrapped message
     */
    @SuppressWarnings("unchecked")
    public static <T> T unwrap(Object message) {
        if (message instanceof MessageAdapter) {
            return (T) ((MessageAdapter<?>) message).originalMessage();
        }
        return (T) message;
    }
    
    /**
     * Checks if the given message is of the expected type, unwrapping it first if it's a MessageAdapter.
     * This is a convenient way to check the type of a message in a stateful actor's processMessage method.
     *
     * @param <T> The expected type
     * @param message The message to check
     * @param expectedType The expected class of the message
     * @return true if the message (or its unwrapped content) is of the expected type, false otherwise
     */
    public static <T> boolean isMessageOfType(Object message, Class<T> expectedType) {
        if (message instanceof MessageAdapter) {
            Object originalMessage = ((MessageAdapter<?>) message).originalMessage();
            return expectedType.isInstance(originalMessage);
        }
        return expectedType.isInstance(message);
    }
    
    /**
     * Safely casts a message to the expected type, unwrapping it first if it's a MessageAdapter.
     * This is a convenient way to cast a message in a stateful actor's processMessage method.
     *
     * @param <T> The expected type
     * @param message The message to cast
     * @param expectedType The expected class of the message
     * @return The message cast to the expected type, or null if the message is not of the expected type
     */
    @SuppressWarnings("unchecked")
    public static <T> T castMessage(Object message, Class<T> expectedType) {
        Object unwrapped = message;
        if (message instanceof MessageAdapter) {
            unwrapped = ((MessageAdapter<?>) message).originalMessage();
        }
        
        if (expectedType.isInstance(unwrapped)) {
            return (T) unwrapped;
        }
        return null;
    }
}
