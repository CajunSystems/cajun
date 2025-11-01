package com.cajunsystems;

/**
 * A marker interface for messages that require a reply.
 * This standardizes the replyTo pattern and provides strong contracts for reply semantics.
 * 
 * <p>Messages implementing this interface must provide a {@link Pid} to which replies should be sent.
 * This is particularly useful for request-response patterns where the sender expects a response.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * public record GetUserRequest(String userId, Pid replyTo) implements ReplyingMessage {}
 * 
 * // In the actor:
 * public void receive(Object message, ActorContext context) {
 *     if (message instanceof GetUserRequest req) {
 *         User user = fetchUser(req.userId());
 *         context.tell(req.replyTo(), user);
 *     }
 * }
 * }</pre>
 * 
 * @see Pid
 */
public interface ReplyingMessage {
    
    /**
     * Gets the PID to which the reply should be sent.
     * 
     * @return The PID of the actor that should receive the reply
     */
    Pid replyTo();
}
