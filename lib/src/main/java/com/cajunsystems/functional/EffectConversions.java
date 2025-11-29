package com.cajunsystems.functional;

import com.cajunsystems.handler.StatefulHandler;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * Utility class for converting between different actor programming styles.
 * Provides conversions between:
 * - Traditional BiFunction state transitions
 * - Effect monad
 * - StatefulHandler interface
 * 
 * <p>This enables gradual migration from the old API to the new Effect-based API.
 */
public final class EffectConversions {
    
    private EffectConversions() {
        // Utility class, no instantiation
    }
    
    // ============================================================================
    // BiFunction to Effect Conversions
    // ============================================================================
    
    /**
     * Converts a BiFunction state transition to an Effect.
     * This is the simplest conversion for pure state transitions.
     * 
     * <p>Example:
     * <pre>{@code
     * BiFunction<Integer, Increment, Integer> oldStyle = (state, msg) -> state + msg.amount();
     * Effect<Integer, Increment, Void> newStyle = EffectConversions.fromBiFunction(oldStyle);
     * }</pre>
     * 
     * @param transition The state transition function
     * @return An Effect that applies the transition
     */
    public static <State, Message> Effect<State, Message, Void> fromBiFunction(
        BiFunction<State, Message, State> transition
    ) {
        return Effect.fromTransition(transition);
    }
    
    /**
     * Converts a BiFunction with error handling to an Effect.
     * Errors are caught and converted to Effect failures.
     * 
     * @param transition The state transition function
     * @param errorHandler Optional error handler for side effects (logging, etc.)
     * @return An Effect that applies the transition with error handling
     */
    public static <State, Message> Effect<State, Message, Void> fromBiFunctionWithErrorHandling(
        BiFunction<State, Message, State> transition,
        BiConsumer<State, Exception> errorHandler
    ) {
        return (state, message, context) -> {
            try {
                State newState = transition.apply(state, message);
                return EffectResult.noResult(newState);
            } catch (Exception e) {
                if (errorHandler != null) {
                    errorHandler.accept(state, e);
                }
                return EffectResult.failure(state, e);
            }
        };
    }
    
    // ============================================================================
    // Effect to BiFunction Conversions
    // ============================================================================
    
    /**
     * Converts an Effect to a BiFunction.
     * This is useful when you need to use an Effect in code that expects a BiFunction.
     * 
     * <p>Note: This conversion loses some information:
     * - The ActorContext is not available (null is passed)
     * - Effect failures result in the original state being returned
     * - Result values are discarded
     * 
     * @param effect The effect to convert
     * @return A BiFunction that executes the effect
     */
    public static <State, Message, Result> BiFunction<State, Message, State> toBiFunction(
        Effect<State, Message, Result> effect
    ) {
        return (state, message) -> {
            EffectResult<State, Result> result = effect.run(state, message, null);
            return result.state();
        };
    }
    
    // ============================================================================
    // Effect to StatefulHandler Conversions
    // ============================================================================
    
    /**
     * Converts an Effect to a StatefulHandler.
     * This is the primary way to integrate Effects with the actor system.
     * 
     * <p>Failures are logged but don't crash the actor - the state remains unchanged.
     * 
     * @param effect The effect to convert
     * @return A StatefulHandler that executes the effect
     */
    public static <State, Message, Result> StatefulHandler<State, Message> toStatefulHandler(
        Effect<State, Message, Result> effect
    ) {
        return new StatefulHandler<State, Message>() {
            @Override
            public State receive(Message message, State state, com.cajunsystems.ActorContext context) {
                EffectResult<State, Result> result = effect.run(state, message, context);
                
                // Log failures
                if (result.isFailure()) {
                    result.error().ifPresent(error -> 
                        context.getLogger().error("Effect execution failed", error)
                    );
                }
                
                return result.state();
            }
        };
    }
    
    /**
     * Converts an Effect to a StatefulHandler with custom error handling.
     * 
     * @param effect The effect to convert
     * @param onError Custom error handler (receives state and error)
     * @return A StatefulHandler that executes the effect with custom error handling
     */
    public static <State, Message, Result> StatefulHandler<State, Message> toStatefulHandlerWithErrorHandling(
        Effect<State, Message, Result> effect,
        BiConsumer<State, Throwable> onError
    ) {
        return new StatefulHandler<State, Message>() {
            @Override
            public State receive(Message message, State state, com.cajunsystems.ActorContext context) {
                EffectResult<State, Result> result = effect.run(state, message, context);
                
                // Handle failures
                if (result.isFailure()) {
                    result.error().ifPresent(error -> {
                        context.getLogger().error("Effect execution failed", error);
                        onError.accept(state, error);
                    });
                }
                
                return result.state();
            }
        };
    }
    
    // ============================================================================
    // StatefulHandler to Effect Conversions
    // ============================================================================
    
    /**
     * Converts a StatefulHandler to an Effect.
     * This allows using existing StatefulHandlers in Effect-based compositions.
     * 
     * @param handler The handler to convert
     * @return An Effect that delegates to the handler
     */
    public static <State, Message> Effect<State, Message, Void> fromStatefulHandler(
        StatefulHandler<State, Message> handler
    ) {
        return (state, message, context) -> {
            try {
                State newState = handler.receive(message, state, context);
                return EffectResult.noResult(newState);
            } catch (Exception e) {
                return EffectResult.failure(state, e);
            }
        };
    }
    
    // ============================================================================
    // Lifting Operations
    // ============================================================================
    
    /**
     * Lifts a pure function (State -> State) into an Effect.
     * Useful for simple state modifications.
     * 
     * @param f The state modification function
     * @return An Effect that applies the function
     */
    public static <State, Message> Effect<State, Message, Void> liftStateFunction(
        java.util.function.Function<State, State> f
    ) {
        return Effect.modify(f);
    }
    
    /**
     * Lifts a value into an Effect that returns that value without changing state.
     * 
     * @param value The value to lift
     * @return An Effect that returns the value
     */
    public static <State, Message, Result> Effect<State, Message, Result> liftValue(Result value) {
        return Effect.of(value);
    }
    
    /**
     * Lifts a side effect (Consumer) into an Effect.
     * The side effect is performed but doesn't affect the state or result.
     * 
     * @param sideEffect The side effect to perform
     * @return An Effect that performs the side effect
     */
    public static <State, Message> Effect<State, Message, Void> liftSideEffect(
        java.util.function.Consumer<State> sideEffect
    ) {
        return (state, message, context) -> {
            sideEffect.accept(state);
            return EffectResult.noResult(state);
        };
    }
}
