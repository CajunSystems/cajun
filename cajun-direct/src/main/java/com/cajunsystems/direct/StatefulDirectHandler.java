package com.cajunsystems.direct;

/**
 * Interface for handling messages in a direct-style, stateful actor.
 *
 * <p>Combines the direct-call semantics of {@link DirectHandler} with the managed-state
 * semantics of {@link com.cajunsystems.handler.StatefulHandler}. Each invocation receives
 * the current state and returns a {@link DirectResult} containing the updated state and
 * the reply value.
 *
 * <p>State mutations must be expressed by returning a new state value — the framework stores
 * and threads the state automatically between messages (immutable state pattern).
 *
 * <h2>Example</h2>
 * <pre>{@code
 * public sealed interface CounterMsg {
 *     record Increment() implements CounterMsg {}
 *     record Decrement() implements CounterMsg {}
 *     record GetValue() implements CounterMsg {}
 * }
 *
 * public class CounterHandler implements StatefulDirectHandler<Integer, CounterMsg, Integer> {
 *
 *     @Override
 *     public DirectResult<Integer, Integer> handle(CounterMsg msg, Integer state, DirectContext ctx) {
 *         return switch (msg) {
 *             case Increment _ -> DirectResult.of(state + 1, state + 1);
 *             case Decrement _ -> DirectResult.of(state - 1, state - 1);
 *             case GetValue _  -> DirectResult.of(state, state);
 *         };
 *     }
 * }
 *
 * // Usage:
 * ActorRef<CounterMsg, Integer> counter = system.statefulActorOf(new CounterHandler(), 0)
 *     .withId("counter")
 *     .spawn();
 *
 * counter.call(new CounterMsg.Increment());  // returns 1
 * counter.call(new CounterMsg.Increment());  // returns 2
 * int value = counter.call(new CounterMsg.GetValue());  // returns 2
 * }</pre>
 *
 * @param <State>   The type of the actor's state
 * @param <Message> The type of messages this handler processes
 * @param <Reply>   The type of the reply this handler returns
 */
public interface StatefulDirectHandler<State, Message, Reply> {

    /**
     * Handles an incoming message, potentially updating state, and returns a reply.
     *
     * @param message The incoming message
     * @param state   The current state of the actor
     * @param context The actor context providing access to actor functionality
     * @return A {@link DirectResult} containing the new state and the reply value
     */
    DirectResult<State, Reply> handle(Message message, State state, DirectContext context);

    /**
     * Called once before the actor starts processing messages.
     * Override to modify the initial state before message processing begins.
     *
     * @param initialState The initial state supplied at actor creation
     * @param context      The actor context
     * @return The state to use (may be a modified version of initialState)
     */
    default State preStart(State initialState, DirectContext context) {
        return initialState;
    }

    /**
     * Called once after the actor has stopped processing messages.
     * Override to perform cleanup using the final state.
     *
     * @param state   The final state of the actor
     * @param context The actor context
     */
    default void postStop(State state, DirectContext context) {
    }

    /**
     * Called when an exception is thrown during {@link #handle}.
     *
     * <p>Override to provide a fallback state and reply. Throwing a
     * {@link DirectActorException} (or any unchecked exception) will propagate the
     * error to the caller of {@link ActorRef#call}.
     *
     * @param message   The message that caused the error
     * @param state     The current state when the error occurred
     * @param exception The exception thrown by {@link #handle}
     * @param context   The actor context
     * @return A DirectResult with the (potentially recovered) new state and a fallback reply
     * @throws DirectActorException to propagate the error to the caller
     */
    default DirectResult<State, Reply> onError(
            Message message, State state, Throwable exception, DirectContext context) {
        throw new DirectActorException("Error handling message in " +
                getClass().getSimpleName() + ": " + message, exception);
    }
}
