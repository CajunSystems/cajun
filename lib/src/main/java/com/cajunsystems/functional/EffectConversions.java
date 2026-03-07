package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;
import com.cajunsystems.functional.internal.Trampoline;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Utility class for converting between different actor programming styles.
 * Provides conversions between:
 * - Traditional BiFunction state transitions
 * - Cajun's internal Effect monad (Effect&lt;State, E, Result&gt;)
 * - Roux's Effect monad (com.cajunsystems.roux.Effect&lt;E, State&gt;)
 * - StatefulHandler interface
 *
 * <p>This enables gradual migration from the old API to the new Effect-based API.
 *
 * <p><strong>Note on the two Effect types:</strong> Cajun has its own internal
 * {@link Effect Effect&lt;State, E, Result&gt;} for composing stateful computations within the
 * actor system. Roux's {@code com.cajunsystems.roux.Effect&lt;E, A&gt;} is the external contract
 * returned by {@link StatefulHandler#receive}. Where both types appear in a single method the
 * Roux type is referenced by its fully-qualified name to avoid ambiguity.
 */
public final class EffectConversions {

    private EffectConversions() {
        // Utility class, no instantiation
    }

    // ============================================================================
    // BiFunction to Effect Conversions
    // ============================================================================

    /**
     * Creates a no-op effect that does nothing and returns the state unchanged.
     *
     * @return An Effect that performs no operation
     */
    public static <State, E> Effect<State, E, Void> noOp() {
        return (state, message, context) -> Trampoline.done(EffectResult.noResult(state));
    }

    /**
     * Converts a BiFunction state transition to a Cajun Effect.
     *
     * @param transition The state transition function
     * @return A Cajun Effect that applies the transition
     */
    public static <State, Message, E> Effect<State, E, Void> fromBiFunction(
            BiFunction<State, Message, State> transition) {
        return fromBiFunctionWithErrorHandling(transition, null);
    }

    /**
     * Converts a BiFunction with error handling to a Cajun Effect.
     *
     * @param transition   The state transition function
     * @param errorHandler Optional error handler for side effects (logging, etc.)
     * @return A Cajun Effect that applies the transition with error handling
     */
    @SuppressWarnings("unchecked")
    public static <State, Message, E> Effect<State, E, Void> fromBiFunctionWithErrorHandling(
            BiFunction<State, Message, State> transition,
            BiConsumer<State, Exception> errorHandler) {
        return (state, message, context) -> {
            try {
                State newState = transition.apply(state, (Message) message);
                return Trampoline.done(EffectResult.noResult(newState));
            } catch (Exception e) {
                if (errorHandler != null) {
                    errorHandler.accept(state, e);
                }
                return Trampoline.done(EffectResult.failure(state, e));
            }
        };
    }

    // ============================================================================
    // Effect to BiFunction Conversions
    // ============================================================================

    /**
     * Converts a Cajun Effect to a BiFunction.
     *
     * @param effect The Cajun effect to convert
     * @return A BiFunction that executes the effect
     */
    public static <State, Message, Result, E> BiFunction<State, Message, State> toBiFunction(
            Effect<State, E, Result> effect) {
        return (state, message) -> {
            EffectResult<State, Result> result = effect.run(state, message, null);
            return result.state();
        };
    }

    // ============================================================================
    // Cajun Effect to StatefulHandler Conversions
    // ============================================================================

    /**
     * Converts a Cajun {@link Effect} to a {@link StatefulHandler}.
     *
     * <p>The Cajun Effect is executed synchronously; its resulting state is wrapped in a Roux
     * {@code Effect.succeed()} for return by the handler. Failures are logged but the actor
     * state is preserved.
     *
     * @param effect The Cajun effect to convert
     * @return A StatefulHandler that executes the Cajun effect
     */
    public static <State, Message, Result, E> StatefulHandler<RuntimeException, State, Message> toStatefulHandler(
            Effect<State, E, Result> effect) {
        return new StatefulHandler<RuntimeException, State, Message>() {
            @Override
            public com.cajunsystems.roux.Effect<RuntimeException, State> receive(
                    Message message, State state, ActorContext context) {
                EffectResult<State, Result> result = effect.run(state, message, context);
                if (result.isFailure()) {
                    result.error().ifPresent(error ->
                            context.getLogger().error("Cajun Effect execution failed", error));
                }
                return com.cajunsystems.roux.Effect.succeed(result.state());
            }
        };
    }

    /**
     * Converts a Cajun {@link Effect} to a {@link StatefulHandler} with custom error handling.
     *
     * @param effect  The Cajun effect to convert
     * @param onError Custom error handler (receives state and error)
     * @return A StatefulHandler that executes the Cajun effect with custom error handling
     */
    public static <State, Message, Result, E> StatefulHandler<RuntimeException, State, Message> toStatefulHandlerWithErrorHandling(
            Effect<State, E, Result> effect,
            BiConsumer<State, Throwable> onError) {
        return new StatefulHandler<RuntimeException, State, Message>() {
            @Override
            public com.cajunsystems.roux.Effect<RuntimeException, State> receive(
                    Message message, State state, ActorContext context) {
                EffectResult<State, Result> result = effect.run(state, message, context);
                if (result.isFailure()) {
                    result.error().ifPresent(error -> {
                        context.getLogger().error("Cajun Effect execution failed", error);
                        onError.accept(state, error);
                    });
                }
                return com.cajunsystems.roux.Effect.succeed(result.state());
            }
        };
    }

    // ============================================================================
    // StatefulHandler to Cajun Effect Conversions
    // ============================================================================

    /**
     * Converts a {@link StatefulHandler} to a Cajun {@link Effect}.
     *
     * <p>The handler's {@code receive()} method is called to obtain a Roux Effect, which is then
     * executed synchronously via a transient {@link DefaultEffectRuntime} to yield the new state.
     * The resulting state is wrapped in a Cajun {@code EffectResult.noResult}.
     *
     * @param handler The handler to convert
     * @return A Cajun Effect that delegates to the handler
     */
    @SuppressWarnings("unchecked")
    public static <State, Message, EH extends Throwable, E> Effect<State, E, Void> fromStatefulHandler(
            StatefulHandler<EH, State, Message> handler) {
        return (state, message, context) -> {
            try {
                com.cajunsystems.roux.Effect<EH, State> rouxEffect =
                        handler.receive((Message) message, state, context);
                try (DefaultEffectRuntime runtime = DefaultEffectRuntime.create()) {
                    State newState = runtime.unsafeRun(rouxEffect);
                    return Trampoline.done(EffectResult.noResult(newState));
                }
            } catch (Throwable t) {
                return Trampoline.done(EffectResult.failure(state, t));
            }
        };
    }

    // ============================================================================
    // Lifting Operations
    // ============================================================================

    /**
     * Lifts a pure function (State -&gt; State) into a Cajun Effect.
     *
     * @param f The state modification function
     * @return A Cajun Effect that applies the function
     */
    public static <State, Message> Effect<State, Message, Void> liftStateFunction(
            Function<State, State> f) {
        return Effect.modify(f);
    }

    /**
     * Lifts a value into a Cajun Effect that returns that value without changing state.
     *
     * @param value The value to lift
     * @return A Cajun Effect that returns the value
     */
    public static <State, Message, Result> Effect<State, Message, Result> liftValue(Result value) {
        return Effect.of(value);
    }

    /**
     * Lifts a side effect (Consumer) into a Cajun Effect.
     *
     * @param sideEffect The side effect to perform
     * @return A Cajun Effect that performs the side effect
     */
    public static <State, E> Effect<State, E, Void> liftSideEffect(
            Consumer<State> sideEffect) {
        return (state, message, context) -> {
            sideEffect.accept(state);
            return Trampoline.done(EffectResult.noResult(state));
        };
    }
}
