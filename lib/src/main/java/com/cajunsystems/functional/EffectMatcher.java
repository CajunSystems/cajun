package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;

import java.util.ArrayList;
import java.util.List;

/**
 * A builder for creating pattern-matched effects.
 * Allows matching on message types and providing different effects for each type.
 *
 * <p>The Message type parameter is local to the matcher, keeping the Effect
 * interface cleaner with only State, Error, and Result parameters.
 *
 * <p>Example usage:
 * <pre>{@code
 * Effect<Integer, Throwable, Void> effect = Effect.<Integer, Throwable, Void, CounterMsg>match()
 *     .when(Increment.class, (state, msg, ctx) ->
 *         Effect.modify(s -> s + msg.amount()))
 *     .when(Decrement.class, (state, msg, ctx) ->
 *         Effect.modify(s -> s - msg.amount()))
 *     .otherwise(Effect.log("Unknown message"));
 * }</pre>
 *
 * @param <State> The type of the actor's state
 * @param <Error> The type of errors that can occur
 * @param <Result> The type of result produced by the effects
 * @param <Message> The base type of messages (local to pattern matching)
 */
public class EffectMatcher<State, Error, Result, Message> {
    
    /**
     * Represents a single case in the pattern match.
     *
     * @param <S> State type
     * @param <E> Error type
     * @param <M> Message type
     * @param <R> Result type
     */
    @FunctionalInterface
    public interface EffectCase<S, E, M, R> {
        /**
         * Creates an effect for the given state, message, and context.
         *
         * @param state The current state
         * @param message The message being processed
         * @param context The actor context
         * @return The effect to execute
         */
        Effect<S, E, R> apply(S state, M message, ActorContext context);
    }
    
    /**
     * Represents a single pattern match case.
     */
    private static class MatchCase<State, Error, Message, Result> {
        final Class<?> messageType;
        final EffectCase<State, Error, ?, Result> effectCase;

        MatchCase(Class<?> messageType, EffectCase<State, Error, ?, Result> effectCase) {
            this.messageType = messageType;
            this.effectCase = effectCase;
        }

        boolean matches(Object message) {
            return messageType.isInstance(message);
        }

        @SuppressWarnings("unchecked")
        Effect<State, Error, Result> createEffect(State state, Message message, ActorContext context) {
            return ((EffectCase<State, Error, Message, Result>) effectCase).apply(state, message, context);
        }
    }

    private final List<MatchCase<State, Error, Message, Result>> cases = new ArrayList<>();
    private Effect<State, Error, Result> defaultEffect = null;
    
    /**
     * Adds a case for a specific message type.
     *
     * @param messageType The class of the message type to match
     * @param effectCase The function to create an effect for this message type
     * @return This matcher for chaining
     */
    public <M extends Message> EffectMatcher<State, Error, Result, Message> when(
        Class<M> messageType,
        EffectCase<State, Error, M, Result> effectCase
    ) {
        cases.add(new MatchCase<>(messageType, effectCase));
        return this;
    }

    /**
     * Sets the default effect to use when no cases match.
     *
     * @param defaultEffect The default effect
     * @return The final effect that performs pattern matching
     */
    public Effect<State, Error, Result> otherwise(Effect<State, Error, Result> defaultEffect) {
        this.defaultEffect = defaultEffect;
        return build();
    }

    /**
     * Builds the final effect without a default case.
     * If no cases match, the effect will fail with an IllegalArgumentException.
     *
     * @return The final effect that performs pattern matching
     */
    public Effect<State, Error, Result> build() {
        return (state, message, context) -> {
            // Find the first matching case
            for (MatchCase<State, Error, Message, Result> matchCase : cases) {
                if (matchCase.matches(message)) {
                    @SuppressWarnings("unchecked")
                    Message typedMessage = (Message) message;
                    Effect<State, Error, Result> effect = matchCase.createEffect(state, typedMessage, context);
                    return effect.run(state, message, context);
                }
            }

            // No match found - use default or fail
            if (defaultEffect != null) {
                return defaultEffect.run(state, message, context);
            } else {
                return EffectResult.failure(
                    state,
                    (Error) new IllegalArgumentException("No matching case for message: " + message.getClass().getName())
                );
            }
        };
    }

    /**
     * Creates a matcher that always uses the default effect.
     * Useful for creating a catch-all handler.
     *
     * @param defaultEffect The effect to always use
     * @return An effect that always executes the default
     */
    public static <S, E, R> Effect<S, E, R> always(Effect<S, E, R> defaultEffect) {
        return defaultEffect;
    }
}
