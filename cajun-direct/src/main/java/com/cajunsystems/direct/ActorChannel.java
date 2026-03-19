package com.cajunsystems.direct;

import java.util.function.Function;

/**
 * A channel for generator-style actors — lets you write actor logic as a simple loop.
 *
 * <p>An {@code ActorChannel} is obtained by passing a loop body to
 * {@link ActorScope#actor(java.util.function.Consumer)}. The framework starts the loop in a
 * virtual thread and bridges it to the Cajun actor mailbox. Each incoming message is delivered
 * to the loop via {@link #handle}, which blocks until a message is available and then invokes
 * the supplied function to produce a reply.
 *
 * <h2>Pattern</h2>
 * <pre>{@code
 * DirectPid<CalcMsg, Double> calc = scope.actor(channel -> {
 *     while (channel.isOpen()) {
 *         channel.handle(msg -> switch (msg) {
 *             case CalcMsg.Add(double a, double b)      -> a + b;
 *             case CalcMsg.Multiply(double a, double b) -> a * b;
 *         });
 *     }
 * });
 * }</pre>
 *
 * <p>Or using the return value of {@code handle}:
 * <pre>{@code
 * DirectPid<Msg, Reply> pid = scope.actor(channel -> {
 *     while (channel.handle(msg -> process(msg))) {
 *         // loop continues until channel is closed
 *     }
 * });
 * }</pre>
 *
 * @param <Message> The type of messages the actor receives
 * @param <Reply>   The type of replies the actor produces
 */
public interface ActorChannel<Message, Reply> {

    /**
     * Waits for the next incoming message, applies {@code fn} to produce a reply, and sends
     * the reply back to the caller. Blocks the calling thread until a message is available.
     *
     * <p>If the channel has been closed (the actor is being stopped), this method returns
     * {@code false} without invoking {@code fn}.
     *
     * <p>If {@code fn} throws, the exception is propagated back to the caller as a
     * {@link DirectActorException}.
     *
     * @param fn The function that processes a message and returns a reply
     * @return {@code true} if a message was processed; {@code false} if the channel is closed
     */
    boolean handle(Function<Message, Reply> fn);

    /**
     * Returns {@code true} as long as the channel is open and ready to receive messages.
     * Becomes {@code false} once the actor is being stopped (e.g. when the enclosing
     * {@link ActorScope} is closed).
     *
     * @return {@code true} if the channel is still open
     */
    boolean isOpen();
}
