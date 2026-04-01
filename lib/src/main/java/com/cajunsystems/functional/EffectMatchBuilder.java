package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;
import com.cajunsystems.functional.internal.Trampoline;

import java.util.HashMap;
import java.util.Map;

/**
 * Builder for creating pattern-matching effects with {@link Effect}.
 *
 * @deprecated Part of the deprecated internal {@link Effect} monad.
 *     Use the Roux {@link com.cajunsystems.roux.Effect Effect&lt;E, A&gt;} API with sealed
 *     interfaces and Java pattern matching ({@code switch}) instead.
 *     This class will be removed in a future release.
 *
 * <p>Example (old — deprecated):
 * <pre>{@code
 * Effect<BankState, String, Void> behavior = 
 *     Effect.<BankState, String, Void, BankMsg>match()
 *         .when(Deposit.class, (state, msg, ctx) -> 
 *             Effect.modify(s -> new BankState(s.balance() + msg.amount()))
 *         )
 *         .when(Withdraw.class, (state, msg, ctx) -> 
 *             Effect.<BankState, String, Void>modify(s -> 
 *                 new BankState(s.balance() - msg.amount())
 *             )
 *             .filterOrElse(
 *                 s -> s.balance() >= 0,
 *                 Effect.identity()
 *             )
 *         )
 *         .build();
 * }</pre>
 *
 * @param <S> The state type
 * @param <E> The error type
 * @param <R> The result type
 * @param <M> The base message type (for type safety at match level)
 */
@Deprecated(since = "0.5.0", forRemoval = true)
public class EffectMatchBuilder<S, E, R, M> {
    
    private final Map<Class<?>, MessageHandler<S, E, ?, R>> handlers = new HashMap<>();
    private Effect<S, E, R> defaultEffect = null;
    
    /**
     * Adds a handler for a specific message type.
     *
     * @param messageClass The class of the message to handle
     * @param handler The handler function that produces an effect
     * @param <MSG> The specific message type
     * @return This builder for chaining
     */
    public <MSG extends M> EffectMatchBuilder<S, E, R, M> when(
            Class<MSG> messageClass,
            TriFunction<S, MSG, ActorContext, Effect<S, E, R>> handler) {
        handlers.put(messageClass, new MessageHandler<>(messageClass, handler));
        return this;
    }
    
    /**
     * Sets a default effect to use when no handler matches.
     *
     * @param effect The default effect
     * @return The final effect (terminates the builder chain)
     */
    public Effect<S, E, R> otherwise(Effect<S, E, R> effect) {
        this.defaultEffect = effect;
        return build();
    }
    
    /**
     * Builds the final effect that dispatches to the appropriate handler.
     *
     * @return An Effect that pattern-matches on message type
     */
    @SuppressWarnings("unchecked")
    public Effect<S, E, R> build() {
        return (state, message, context) -> {
            Class<?> messageClass = message.getClass();
            
            // Find handler for this message type
            MessageHandler<S, E, ?, R> handler = handlers.get(messageClass);
            
            if (handler == null) {
                // No handler found - use default or return no result
                if (defaultEffect != null) {
                    return defaultEffect.runT(state, message, context);
                }
                return Trampoline.done(EffectResult.noResult(state));
            }
            
            // Cast and invoke the handler
            MessageHandler<S, E, Object, R> typedHandler = (MessageHandler<S, E, Object, R>) handler;
            Effect<S, E, R> effect = typedHandler.handler.apply(state, message, context);
            
            return effect.runT(state, message, context);
        };
    }
    
    /**
     * Internal record to store message handlers.
     */
    private record MessageHandler<S, E, MSG, R>(
            Class<MSG> messageClass,
            TriFunction<S, MSG, ActorContext, Effect<S, E, R>> handler
    ) {}
    
    /**
     * Functional interface for functions that take three arguments.
     */
    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }
}
