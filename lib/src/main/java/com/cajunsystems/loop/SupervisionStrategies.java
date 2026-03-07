package com.cajunsystems.loop;

import com.cajunsystems.roux.Effect;

/**
 * Factory methods that wrap an {@link ActorBehavior} with declarative supervision
 * expressed as Roux {@link Effect} combinators.
 *
 * <p>These replace the imperative switch in {@link com.cajunsystems.Supervisor} for
 * handler-based actors, making supervision a composable, testable effect.
 *
 * <p>Usage:
 * <pre>{@code
 * // Resume on any error (ignore and keep the current state)
 * ActorBehavior<RuntimeException, State, Msg> resilient =
 *     SupervisionStrategies.withResume(baseBehavior);
 *
 * // Restart from initial state on RuntimeException
 * ActorBehavior<RuntimeException, State, Msg> selfHealing =
 *     SupervisionStrategies.withRestart(baseBehavior, initialState);
 *
 * // Stop on any error
 * ActorBehavior<RuntimeException, State, Msg> strict =
 *     SupervisionStrategies.withStop(baseBehavior);
 *
 * // Custom per-exception decision
 * ActorBehavior<Exception, State, Msg> custom =
 *     SupervisionStrategies.withDecision(baseBehavior, (err, state) ->
 *         err instanceof TransientException
 *             ? LoopStep.restart(initialState)
 *             : LoopStep.stop(state));
 * }</pre>
 */
public final class SupervisionStrategies {

    private SupervisionStrategies() {}

    // -------------------------------------------------------------------------
    // Resume — continue with the current state, discarding the error
    // -------------------------------------------------------------------------

    /**
     * On any error from {@code behavior}, resume processing by continuing with
     * the state that was in effect when the error occurred.
     *
     * <p>This corresponds to {@link com.cajunsystems.SupervisionStrategy#RESUME}.
     */
    public static <E extends Throwable, State, Message>
    ActorBehavior<E, State, Message> withResume(ActorBehavior<E, State, Message> behavior) {
        return (msg, state, ctx) ->
            behavior.step(msg, state, ctx)
                    .catchAll(err -> Effect.succeed(LoopStep.continue_(state)));
    }

    // -------------------------------------------------------------------------
    // Restart — signal a restart with the given initial state
    // -------------------------------------------------------------------------

    /**
     * On any error from {@code behavior}, signal a {@link LoopStep#restart} using
     * {@code initialState}.
     *
     * <p>This corresponds to {@link com.cajunsystems.SupervisionStrategy#RESTART}.
     *
     * @param initialState the state to use when the actor restarts
     */
    public static <E extends Throwable, State, Message>
    ActorBehavior<E, State, Message> withRestart(
            ActorBehavior<E, State, Message> behavior,
            State initialState) {
        return (msg, state, ctx) ->
            behavior.step(msg, state, ctx)
                    .catchAll(err -> Effect.succeed(LoopStep.restart(initialState)));
    }

    // -------------------------------------------------------------------------
    // Stop — signal a graceful stop
    // -------------------------------------------------------------------------

    /**
     * On any error from {@code behavior}, signal a {@link LoopStep#stop} with the
     * current state.
     *
     * <p>This corresponds to {@link com.cajunsystems.SupervisionStrategy#STOP}.
     */
    public static <E extends Throwable, State, Message>
    ActorBehavior<E, State, Message> withStop(ActorBehavior<E, State, Message> behavior) {
        return (msg, state, ctx) ->
            behavior.step(msg, state, ctx)
                    .catchAll(err -> Effect.succeed(LoopStep.stop(state)));
    }

    // -------------------------------------------------------------------------
    // Custom — user-defined decision function
    // -------------------------------------------------------------------------

    /**
     * On any error from {@code behavior}, call {@code decider} with the error and
     * the current state to produce a custom {@link LoopStep}.
     *
     * <p>Example — retry transient errors, stop on permanent ones:
     * <pre>{@code
     * ActorBehavior<Exception, State, Msg> smart = SupervisionStrategies.withDecision(
     *     base,
     *     (err, state) -> err instanceof TransientException
     *         ? LoopStep.restart(initialState)
     *         : LoopStep.stop(state));
     * }</pre>
     *
     * @param decider a function from (error, currentState) to a {@link LoopStep}
     */
    public static <E extends Throwable, State, Message>
    ActorBehavior<E, State, Message> withDecision(
            ActorBehavior<E, State, Message> behavior,
            java.util.function.BiFunction<E, State, LoopStep<State>> decider) {
        return (msg, state, ctx) ->
            behavior.step(msg, state, ctx)
                    .catchAll(err -> Effect.succeed(decider.apply(err, state)));
    }
}
