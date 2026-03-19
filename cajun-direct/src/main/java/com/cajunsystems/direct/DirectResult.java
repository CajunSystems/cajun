package com.cajunsystems.direct;

/**
 * The result returned by a {@link StatefulDirectHandler}, containing both the new actor state
 * and the reply value to send back to the caller.
 *
 * <p>Example usage:
 * <pre>{@code
 * public DirectResult<Integer, Integer> handle(CounterMsg msg, Integer state, DirectContext ctx) {
 *     return switch (msg) {
 *         case Increment _ -> DirectResult.of(state + 1, state + 1);
 *         case GetValue _  -> DirectResult.of(state, state);
 *     };
 * }
 * }</pre>
 *
 * @param <State> The type of the actor's state
 * @param <Reply> The type of the reply value
 */
public record DirectResult<State, Reply>(State newState, Reply reply) {

    /**
     * Creates a new DirectResult with the given new state and reply.
     *
     * @param newState The new actor state after processing the message
     * @param reply    The reply value to return to the caller
     * @param <S>      The state type
     * @param <R>      The reply type
     * @return A new DirectResult
     */
    public static <S, R> DirectResult<S, R> of(S newState, R reply) {
        return new DirectResult<>(newState, reply);
    }
}
