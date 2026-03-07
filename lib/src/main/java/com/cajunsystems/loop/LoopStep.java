package com.cajunsystems.loop;

/**
 * Represents the outcome of a single actor message-processing iteration.
 *
 * <p>A handler's {@link ActorBehavior} returns an {@link com.cajunsystems.roux.Effect} whose
 * success value is one of three {@code LoopStep} variants:
 * <ul>
 *   <li>{@link Continue} — keep running with the new state</li>
 *   <li>{@link Stop} — gracefully stop the actor after this message</li>
 *   <li>{@link Restart} — restart the actor, replaying from the given state</li>
 * </ul>
 *
 * <p>Example in a handler:
 * <pre>{@code
 * public Effect<RuntimeException, LoopStep<Integer>> step(
 *         Msg msg, Integer count, ActorContext ctx) {
 *     return switch (msg) {
 *         case Increment()  -> Effect.succeed(LoopStep.continue_(count + 1));
 *         case Shutdown()   -> Effect.succeed(LoopStep.stop(count));
 *         case Reset()      -> Effect.succeed(LoopStep.restart(0));
 *     };
 * }
 * }</pre>
 *
 * @param <State> the actor's state type
 */
public sealed interface LoopStep<State> permits LoopStep.Continue, LoopStep.Stop, LoopStep.Restart {

    /**
     * The state to carry into the next step (or as the final state for Stop/Restart).
     */
    State state();

    // -------------------------------------------------------------------------
    // Variants
    // -------------------------------------------------------------------------

    /** Continue processing messages with the given state. */
    record Continue<State>(State state) implements LoopStep<State> {}

    /** Stop the actor gracefully after this message with the given final state. */
    record Stop<State>(State state) implements LoopStep<State> {}

    /**
     * Restart the actor after this message.  The {@code state} is the initial
     * state the restarted actor will begin with.
     */
    record Restart<State>(State state) implements LoopStep<State> {}

    // -------------------------------------------------------------------------
    // Factory helpers
    // -------------------------------------------------------------------------

    /** Returns a {@link Continue} step carrying {@code state}. */
    static <State> LoopStep<State> continue_(State state) {
        return new Continue<>(state);
    }

    /** Returns a {@link Stop} step with the given final state. */
    static <State> LoopStep<State> stop(State state) {
        return new Stop<>(state);
    }

    /**
     * Returns a {@link Restart} step.  The actor will restart using
     * {@code initialState} as its new starting state.
     */
    static <State> LoopStep<State> restart(State initialState) {
        return new Restart<>(initialState);
    }

    // -------------------------------------------------------------------------
    // Predicates
    // -------------------------------------------------------------------------

    default boolean isContinue() { return this instanceof Continue<State>; }
    default boolean isStop()     { return this instanceof Stop<State>; }
    default boolean isRestart()  { return this instanceof Restart<State>; }
}
