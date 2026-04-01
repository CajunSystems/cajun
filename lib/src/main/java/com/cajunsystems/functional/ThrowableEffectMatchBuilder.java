package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;
import com.cajunsystems.functional.internal.Trampoline;

import java.util.HashMap;
import java.util.Map;

/**
 * Builder for creating pattern-matching effects with {@link ThrowableEffect}.
 * 
 * <p>This builder allows you to define handlers for different message types
 * and combines them into a single effect that dispatches based on message type.
 * 
 * <p>Example:
 * <pre>{@code
 * ThrowableEffect<BankState, Void> behavior = ThrowableEffect.<BankState>match()
 *     .when(Deposit.class, (state, msg, ctx) -> 
 *         ThrowableEffect.modify(s -> new BankState(s.balance() + msg.amount()))
 *     )
 *     .when(Withdraw.class, (state, msg, ctx) -> 
 *         ThrowableEffect.<BankState, Void>modify(s -> 
 *             new BankState(s.balance() - msg.amount())
 *         )
 *         .filterOrElse(
 *             s -> s.balance() >= 0,
 *             ThrowableEffect.identity()
 *         )
 *     )
 *     .build();
 * }</pre>
 *
 * @param <S> The state type
 */
public class ThrowableEffectMatchBuilder<S> {
    
    private final Map<Class<?>, MessageHandler<S, ?, ?>> handlers = new HashMap<>();
    
    /**
     * Adds a handler for a specific message type.
     *
     * @param messageClass The class of the message to handle
     * @param handler The handler function that produces an effect
     * @param <M> The message type
     * @param <R> The result type
     * @return This builder for chaining
     */
    public <M, R> ThrowableEffectMatchBuilder<S> when(
            Class<M> messageClass,
            TriFunction<S, M, ActorContext, ThrowableEffect<S, R>> handler) {
        handlers.put(messageClass, new MessageHandler<>(messageClass, handler));
        return this;
    }
    
    /**
     * Builds the final effect that dispatches to the appropriate handler.
     *
     * @param <R> The result type (typically Void for behaviors)
     * @return A ThrowableEffect that pattern-matches on message type
     */
    @SuppressWarnings("unchecked")
    public <R> ThrowableEffect<S, R> build() {
        return (state, message, context) -> {
            Class<?> messageClass = message.getClass();
            
            // Find handler for this message type
            MessageHandler<S, ?, ?> handler = handlers.get(messageClass);
            
            if (handler == null) {
                // No handler found - return identity effect
                return Trampoline.done(EffectResult.noResult(state));
            }
            
            // Cast and invoke the handler
            MessageHandler<S, Object, R> typedHandler = (MessageHandler<S, Object, R>) handler;
            ThrowableEffect<S, R> effect = typedHandler.handler.apply(state, message, context);
            
            return effect.runT(state, message, context);
        };
    }
    
    /**
     * Internal record to store message handlers.
     */
    private record MessageHandler<S, M, R>(
            Class<M> messageClass,
            TriFunction<S, M, ActorContext, ThrowableEffect<S, R>> handler
    ) {}
    
    /**
     * Functional interface for functions that take three arguments.
     */
    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }
}
