package com.cajunsystems.handler;

import com.cajunsystems.ActorContext;
import com.cajunsystems.roux.Effect;

/**
 * Interface for handling messages in a stateful actor.
 * This provides a clean separation between the actor implementation and message handling logic.
 *
 * <p>The {@link #receive} method returns a Roux {@link Effect}{@code <E, State>} — a lazy,
 * composable description of the state transition. Cajun's runtime executes the effect via
 * {@code EffectRuntime.unsafeRun()} and uses the resulting state value as the new actor state.
 *
 * <p>Example usage:
 * <pre>{@code
 * public class CounterHandler implements StatefulHandler<RuntimeException, Integer, CounterMsg> {
 *
 *     @Override
 *     public Effect<RuntimeException, Integer> receive(CounterMsg msg, Integer count, ActorContext ctx) {
 *         return switch (msg) {
 *             case Increment() -> Effect.succeed(count + 1);
 *             case Reset()     -> Effect.succeed(0);
 *             case GetCount(var replyTo) -> Effect.suspend(() -> {
 *                 ctx.tell(replyTo, count);
 *                 return count;
 *             });
 *         };
 *     }
 * }
 * }</pre>
 *
 * @param <E>       the error type (must extend {@link Throwable})
 * @param <State>   the type of the actor's state
 * @param <Message> the type of messages this handler processes
 */
public interface StatefulHandler<E extends Throwable, State, Message> {

    /**
     * Processes a message and returns an effect describing the resulting state transition.
     *
     * <p>The returned {@link Effect} is executed by Cajun's runtime immediately after this
     * method returns. On success the effect's value becomes the new actor state; on failure
     * the exception is propagated to {@link #onError}.
     *
     * @param message the message to process
     * @param state   the current state of the actor
     * @param context the actor context providing access to actor functionality
     * @return an effect whose success value is the new state
     */
    Effect<E, State> receive(Message message, State state, ActorContext context);

    /**
     * Called before the actor starts processing messages.
     * Override to perform initialization logic.
     *
     * @param state   the initial state of the actor
     * @param context the actor context providing access to actor functionality
     * @return the potentially modified initial state
     */
    default State preStart(State state, ActorContext context) {
        return state;
    }

    /**
     * Called after the actor has stopped processing messages.
     * Override to perform cleanup logic.
     *
     * @param state   the final state of the actor
     * @param context the actor context providing access to actor functionality
     */
    default void postStop(State state, ActorContext context) {
    }

    /**
     * Called when an exception occurs during message processing.
     * Override to provide custom error handling.
     *
     * @param message   the message that caused the exception
     * @param state     the current state when the exception occurred
     * @param exception the exception that was thrown
     * @param context   the actor context providing access to actor functionality
     * @return {@code true} if the message should be reprocessed, {@code false} otherwise
     */
    default boolean onError(Message message, State state, Throwable exception, ActorContext context) {
        return false;
    }
}
