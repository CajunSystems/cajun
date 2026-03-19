package com.cajunsystems.direct;

/**
 * Interface for handling messages in a direct-style, stateless actor.
 *
 * <p>Unlike {@link com.cajunsystems.handler.Handler}, which uses fire-and-forget semantics,
 * {@code DirectHandler} allows handlers to return values directly. Callers use
 * {@link ActorRef#call(Object)} to send a message and receive the reply synchronously
 * (blocking safely on a virtual thread), or {@link ActorRef#callAsync(Object)} for
 * non-blocking async composition.
 *
 * <p>Implementations must be stateless. For stateful actors, use {@link StatefulDirectHandler}.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * public class EchoHandler implements DirectHandler<String, String> {
 *
 *     @Override
 *     public String handle(String message, DirectContext context) {
 *         context.getLogger().info("Echoing: {}", message);
 *         return "Echo: " + message;
 *     }
 * }
 *
 * // Usage:
 * ActorRef<String, String> echo = system.actorOf(new EchoHandler())
 *     .withId("echo")
 *     .spawn();
 *
 * String reply = echo.call("hello");  // returns "Echo: hello"
 * }</pre>
 *
 * @param <Message> The type of messages this handler processes
 * @param <Reply>   The type of the reply this handler returns
 */
public interface DirectHandler<Message, Reply> {

    /**
     * Handles an incoming message and returns a reply.
     *
     * <p>When invoked via {@link ActorRef#call(Object)}, the return value is sent back
     * to the caller. When invoked via {@link ActorRef#tell(Object)} (fire-and-forget),
     * the return value is discarded.
     *
     * @param message The incoming message
     * @param context The actor context providing access to actor functionality
     * @return The reply value
     */
    Reply handle(Message message, DirectContext context);

    /**
     * Called once before the actor starts processing messages.
     * Override to perform initialization.
     *
     * @param context The actor context
     */
    default void preStart(DirectContext context) {
    }

    /**
     * Called once after the actor has stopped processing messages.
     * Override to perform cleanup.
     *
     * @param context The actor context
     */
    default void postStop(DirectContext context) {
    }

    /**
     * Called when an exception is thrown during {@link #handle}.
     *
     * <p>Override to provide a fallback reply. Throwing a {@link DirectActorException}
     * (or any unchecked exception) will propagate the error to the caller of
     * {@link ActorRef#call}.
     *
     * @param message   The message that caused the error
     * @param exception The exception thrown by {@link #handle}
     * @param context   The actor context
     * @return A fallback reply value
     * @throws DirectActorException to propagate the error to the caller
     */
    default Reply onError(Message message, Throwable exception, DirectContext context) {
        throw new DirectActorException("Error handling message in " +
                getClass().getSimpleName() + ": " + message, exception);
    }
}
