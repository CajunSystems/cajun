package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;
import com.cajunsystems.functional.internal.Trampoline;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A stack-safe, composable effect monad with built-in error handling.
 * 
 * <p>Simplified from {@link Effect} by removing the Message type parameter,
 * making it less verbose while maintaining full functionality. The Message type
 * is only specified at the {@link #match()} level where it's actually needed.
 * 
 * <p><strong>Key Differences from Effect:</strong>
 * <ul>
 * <li><strong>Less Verbose</strong> - Only 2 type parameters (State, Result) instead of 3
 * <li><strong>Stack-Safe</strong> - Uses {@link Trampoline} to prevent StackOverflowError
 * <li><strong>Built-in Error Channel</strong> - Throwable handling is part of the type
 * <li><strong>Message Type at Match</strong> - Type constraint only where needed
 * </ul>
 * 
 * <p>Example:
 * <pre>{@code
 * // Old Effect - verbose
 * Effect<BankState, BankMsg, Void> behavior = Effect.<BankState, BankMsg, Void>match()
 *     .when(Deposit.class, (state, msg, ctx) -> ...)
 *     .build();
 * 
 * // New ThrowableEffect - concise
 * ThrowableEffect<BankState, Void> behavior = ThrowableEffect.<BankState>match()
 *     .when(Deposit.class, (state, msg, ctx) -> ...)
 *     .build();
 * }</pre>
 * 
 * @param <S> The state type
 * @param <R> The result type
 */
@FunctionalInterface
public interface ThrowableEffect<S, R> {
    
    /**
     * Runs this effect with the given state, message, and context.
     * Returns a {@link Trampoline} for stack-safe evaluation.
     * 
     * @param state The current state
     * @param message The message being processed
     * @param context The actor context
     * @return A trampoline that will produce the effect result
     */
    Trampoline<EffectResult<S, R>> runT(S state, Object message, ActorContext context);
    
    /**
     * Runs this effect and evaluates the trampoline immediately.
     * Convenience method for {@code runT(state, message, context).run()}.
     */
    default EffectResult<S, R> run(S state, Object message, ActorContext context) {
        return runT(state, message, context).run();
    }
    
    // ============================================================================
    // Factory Methods
    // ============================================================================
    
    /**
     * Creates an effect that returns a constant value.
     */
    static <S, R> ThrowableEffect<S, R> of(R value) {
        return (state, message, context) -> 
            Trampoline.done(EffectResult.success(state, value));
    }
    
    /**
     * Creates an effect that returns the current state as the result.
     */
    static <S> ThrowableEffect<S, S> state() {
        return (state, message, context) -> 
            Trampoline.done(EffectResult.success(state, state));
    }
    
    /**
     * Creates an effect that modifies the state.
     */
    static <S> ThrowableEffect<S, Void> modify(Function<S, S> f) {
        return (state, message, context) -> 
            Trampoline.done(EffectResult.noResult(f.apply(state)));
    }
    
    /**
     * Creates an effect that sets the state to a specific value.
     */
    static <S> ThrowableEffect<S, Void> setState(S newState) {
        return (state, message, context) -> 
            Trampoline.done(EffectResult.noResult(newState));
    }
    
    /**
     * Creates an effect that keeps the state unchanged (identity function).
     */
    static <S> ThrowableEffect<S, Void> identity() {
        return (state, message, context) -> 
            Trampoline.done(EffectResult.noResult(state));
    }
    
    /**
     * Creates an effect that fails with the given error.
     */
    static <S, R> ThrowableEffect<S, R> fail(Throwable error) {
        return (state, message, context) -> 
            Trampoline.done(EffectResult.failure(state, error));
    }
    
    // ============================================================================
    // Monadic Operations - Stack-Safe
    // ============================================================================
    
    /**
     * Maps the result value. Stack-safe via trampoline.
     */
    default <R2> ThrowableEffect<S, R2> map(Function<R, R2> f) {
        return (state, message, context) -> 
            runT(state, message, context).map(result -> result.map(f));
    }
    
    /**
     * FlatMaps the result value. Stack-safe via trampoline.
     */
    default <R2> ThrowableEffect<S, R2> flatMap(Function<R, ThrowableEffect<S, R2>> f) {
        return (state, message, context) -> 
            runT(state, message, context).flatMap(result -> 
                result.value()
                    .map(v -> f.apply(v).runT(result.state(), message, context))
                    .orElse(Trampoline.done(EffectResult.noResult(result.state())))
            );
    }
    
    /**
     * Sequences two effects, running this one first then the other.
     * Stack-safe via trampoline.
     */
    default <R2> ThrowableEffect<S, R2> andThen(ThrowableEffect<S, R2> next) {
        return (state, message, context) -> 
            runT(state, message, context).flatMap(result -> 
                next.runT(result.state(), message, context)
            );
    }
    
    // ============================================================================
    // Error Channel - Built-in Throwable Handling
    // ============================================================================
    
    /**
     * Catches any exceptions thrown by this effect and converts them to failures.
     */
    default ThrowableEffect<S, R> attempt() {
        return (state, message, context) -> {
            try {
                return runT(state, message, context);
            } catch (Throwable t) {
                return Trampoline.done(EffectResult.failure(state, t));
            }
        };
    }
    
    /**
     * Handles errors by providing a recovery effect.
     */
    default ThrowableEffect<S, R> handleErrorWith(
            QuadFunction<Throwable, S, Object, ActorContext, ThrowableEffect<S, R>> handler) {
        return (state, message, context) -> 
            runT(state, message, context).flatMap(result -> {
                if (result instanceof EffectResult.Failure<S, R> failure) {
                    return handler.apply(failure.errorValue(), state, message, context)
                        .runT(state, message, context);
                }
                return Trampoline.done(result);
            });
    }
    
    /**
     * Handles errors by recovering the state.
     */
    default ThrowableEffect<S, R> handleError(
            QuadFunction<Throwable, S, Object, ActorContext, S> handler) {
        return handleErrorWith((err, s, m, c) -> 
            (st, ms, ct) -> Trampoline.done(EffectResult.noResult(handler.apply(err, s, m, c)))
        );
    }
    
    /**
     * Performs a side effect when an error occurs, without changing the result.
     */
    default ThrowableEffect<S, R> tapError(Consumer<Throwable> action) {
        return (state, message, context) -> 
            runT(state, message, context).map(result -> {
                if (result instanceof EffectResult.Failure<S, R> failure) {
                    action.accept(failure.errorValue());
                }
                return result;
            });
    }
    
    // ============================================================================
    // Validation
    // ============================================================================
    
    /**
     * Validates the state with a predicate, providing a custom fallback effect.
     */
    default ThrowableEffect<S, R> filterOrElse(
            Predicate<S> predicate,
            ThrowableEffect<S, R> fallback) {
        return (state, message, context) -> 
            runT(state, message, context).flatMap(result -> {
                // Check predicate for both Success and NoResult
                if (result instanceof EffectResult.Success<S, R> success) {
                    if (predicate.test(success.state())) {
                        return Trampoline.done(result);
                    } else {
                        return fallback.runT(success.state(), message, context);
                    }
                } else if (result instanceof EffectResult.NoResult<S, R> noResult) {
                    if (predicate.test(noResult.state())) {
                        return Trampoline.done(result);
                    } else {
                        return fallback.runT(noResult.state(), message, context);
                    }
                }
                // Failure - pass through
                return Trampoline.done(result);
            });
    }
    
    // ============================================================================
    // Side Effects
    // ============================================================================
    
    /**
     * Performs a side effect with the result value.
     */
    default ThrowableEffect<S, R> tap(Consumer<R> action) {
        return (state, message, context) -> 
            runT(state, message, context).map(result -> {
                result.value().ifPresent(action);
                return result;
            });
    }
    
    /**
     * Performs a side effect with the state.
     */
    default ThrowableEffect<S, R> tapState(Consumer<S> action) {
        return (state, message, context) -> 
            runT(state, message, context).map(result -> {
                action.accept(result.state());
                return result;
            });
    }
    
    // ============================================================================
    // Parallel Execution - Stack-Safe
    // ============================================================================
    
    /**
     * Runs two effects in parallel and combines their results.
     */
    default <R2, R3> ThrowableEffect<S, R3> parZip(
            ThrowableEffect<S, R2> other,
            BiFunction<R, R2, R3> combiner) {
        return (state, message, context) -> Trampoline.delay(() -> {
            java.util.concurrent.CompletableFuture<EffectResult<S, R>> future1 = 
                java.util.concurrent.CompletableFuture.supplyAsync(() -> 
                    this.run(state, message, context)
                );
            
            java.util.concurrent.CompletableFuture<EffectResult<S, R2>> future2 = 
                java.util.concurrent.CompletableFuture.supplyAsync(() -> 
                    other.run(state, message, context)
                );
            
            try {
                EffectResult<S, R> result1 = future1.get();
                EffectResult<S, R2> result2 = future2.get();
                
                if (result1 instanceof EffectResult.Failure<S, R> f1) {
                    return EffectResult.failure(f1.state(), f1.errorValue());
                }
                if (result2 instanceof EffectResult.Failure<S, R2> f2) {
                    return EffectResult.failure(f2.state(), f2.errorValue());
                }
                
                java.util.Optional<R> val1 = result1.value();
                java.util.Optional<R2> val2 = result2.value();
                
                if (val1.isPresent() && val2.isPresent()) {
                    R3 combined = combiner.apply(val1.get(), val2.get());
                    return EffectResult.success(result1.state(), combined);
                }
                
                return EffectResult.noResult(result1.state());
            } catch (Exception e) {
                return EffectResult.failure(state, e);
            }
        });
    }
    
    /**
     * Runs a list of effects in parallel and collects all results.
     */
    static <S, R> ThrowableEffect<S, java.util.List<R>> parSequence(
            java.util.List<ThrowableEffect<S, R>> effects) {
        return (state, message, context) -> Trampoline.delay(() -> {
            java.util.List<java.util.concurrent.CompletableFuture<EffectResult<S, R>>> futures = 
                effects.stream()
                    .map(effect -> java.util.concurrent.CompletableFuture.supplyAsync(() -> 
                        effect.run(state, message, context)
                    ))
                    .toList();
            
            try {
                java.util.concurrent.CompletableFuture.allOf(
                    futures.toArray(new java.util.concurrent.CompletableFuture[0])
                ).get();
                
                java.util.List<R> results = new java.util.ArrayList<>();
                S finalState = state;
                
                for (java.util.concurrent.CompletableFuture<EffectResult<S, R>> future : futures) {
                    EffectResult<S, R> result = future.get();
                    
                    if (result instanceof EffectResult.Failure<S, R> failure) {
                        return EffectResult.failure(failure.state(), failure.errorValue());
                    }
                    
                    result.value().ifPresent(results::add);
                    finalState = result.state();
                }
                
                return EffectResult.success(finalState, results);
            } catch (Exception e) {
                return EffectResult.failure(state, e);
            }
        });
    }
    
    /**
     * Runs a list of effects sequentially, threading state through each.
     */
    static <S, R> ThrowableEffect<S, java.util.List<R>> sequence(
            java.util.List<ThrowableEffect<S, R>> effects) {
        return (state, message, context) -> {
            java.util.List<Trampoline<EffectResult<S, R>>> trampolines = 
                new java.util.ArrayList<>();
            
            S currentState = state;
            for (ThrowableEffect<S, R> effect : effects) {
                final S capturedState = currentState;
                trampolines.add(effect.runT(capturedState, message, context));
                // Note: We need to evaluate each to get the next state
                // This is done in the flatMap chain below
            }
            
            // Build a chain of trampolines that threads state through
            Trampoline<EffectResult<S, java.util.List<R>>> result = 
                Trampoline.done(EffectResult.success(state, new java.util.ArrayList<R>()));
            
            for (ThrowableEffect<S, R> effect : effects) {
                result = result.flatMap(listResult -> 
                    effect.runT(listResult.state(), message, context).map(itemResult -> {
                        if (itemResult instanceof EffectResult.Failure<S, R> failure) {
                            return EffectResult.failure(failure.state(), failure.errorValue());
                        }
                        
                        java.util.List<R> newList = new java.util.ArrayList<>(listResult.value().orElse(java.util.List.of()));
                        itemResult.value().ifPresent(newList::add);
                        return EffectResult.success(itemResult.state(), newList);
                    })
                );
            }
            
            return result;
        };
    }
    
    // ============================================================================
    // Match Builder
    // ============================================================================
    
    /**
     * Creates a match builder for pattern matching on message types.
     * This is where the Message type constraint is applied.
     */
    static <S> ThrowableEffectMatchBuilder<S> match() {
        return new ThrowableEffectMatchBuilder<>();
    }
    
    /**
     * Functional interface for functions that take four arguments.
     */
    @FunctionalInterface
    interface QuadFunction<A, B, C, D, R> {
        R apply(A a, B b, C c, D d);
    }
}
