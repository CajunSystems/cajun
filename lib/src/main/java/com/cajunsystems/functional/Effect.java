package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;
import com.cajunsystems.Pid;

import java.time.Duration;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A stack-safe, composable effect monad with explicit error handling.
 * 
 * <p>Simplified from the original Effect by:
 * <ul>
 * <li>Using 3 type parameters: State, Error, Result (Message type only at match level)
 * <li>Stack-safe via {@link Trampoline}
 * <li>Explicit Error type instead of just Throwable
 * </ul>
 * 
 * <p>Example:
 * <pre>{@code
 * Effect<BankState, String, Void> behavior = 
 *     Effect.<BankState, String, Void, BankMsg>match()
 *         .when(Deposit.class, (state, msg, ctx) -> 
 *             Effect.modify(s -> new BankState(s.balance() + msg.amount()))
 *         )
 *         .build();
 * }</pre>
 * 
 * @param <S> The state type
 * @param <E> The error type
 * @param <R> The result type
 */
@FunctionalInterface
public interface Effect<S, E, R> {
    
    /**
     * Runs this effect with the given state, message, and context.
     * Returns a {@link Trampoline} for stack-safe evaluation.
     */
    Trampoline<EffectResult<S, R>> runT(S state, Object message, ActorContext context);
    
    /**
     * Convenience method that runs the trampoline immediately.
     */
    default EffectResult<S, R> run(S state, Object message, ActorContext context) {
        return runT(state, message, context).run();
    }
    
    // ============================================================================
    // Factory Methods
    // ============================================================================
    
    static <S, E, R> Effect<S, E, R> of(R value) {
        return (state, message, context) -> 
            Trampoline.done(EffectResult.success(state, value));
    }
    
    static <S, E> Effect<S, E, S> state() {
        return (state, message, context) -> 
            Trampoline.done(EffectResult.success(state, state));
    }
    
    static <S, E> Effect<S, E, Void> modify(Function<S, S> f) {
        return (state, message, context) -> 
            Trampoline.done(EffectResult.noResult(f.apply(state)));
    }
    
    static <S, E> Effect<S, E, Void> setState(S newState) {
        return (state, message, context) -> 
            Trampoline.done(EffectResult.noResult(newState));
    }
    
    static <S, E> Effect<S, E, Void> identity() {
        return (state, message, context) -> 
            Trampoline.done(EffectResult.noResult(state));
    }
    
    static <S, E> Effect<S, E, Void> none() {
        return identity();
    }
    
    static <S, E, R> Effect<S, E, R> fail(E error) {
        return (state, message, context) -> 
            Trampoline.done(EffectResult.failure(state, (Throwable) error));
    }
    
    /**
     * Creates an effect that executes a potentially throwing operation.
     * This allows you to write natural blocking code that may throw checked exceptions.
     * 
     * <p>Example with checked exceptions:
     * <pre>{@code
     * Effect<State, IOException, String> readFile = 
     *     Effect.attempt(() -> Files.readString(Path.of("data.txt")));
     * 
     * Effect<State, SQLException, User> queryDb = 
     *     Effect.attempt(() -> database.findUser(userId));
     * }</pre>
     * 
     * @param supplier the operation that may throw an exception
     * @return an effect that catches exceptions and converts them to failures
     */
    static <S, E, R> Effect<S, E, R> attempt(ThrowingSupplier<R> supplier) {
        return (state, message, context) -> {
            try {
                R result = supplier.get();
                return Trampoline.done(EffectResult.success(state, result));
            } catch (Throwable t) {
                return Trampoline.done(EffectResult.failure(state, t));
            }
        };
    }
    
    /**
     * Functional interface for operations that may throw checked exceptions.
     * This is used by {@link #attempt(ThrowingSupplier)} to allow natural
     * exception handling without wrapping in try-catch blocks.
     */
    @FunctionalInterface
    interface ThrowingSupplier<R> {
        R get() throws Exception;
    }
    
    @SuppressWarnings("unchecked")
    static <S, E, M> Effect<S, E, Void> fromTransition(
            java.util.function.BiFunction<S, M, S> transition) {
        return (state, message, context) -> {
            try {
                S newState = transition.apply(state, (M) message);
                return Trampoline.done(EffectResult.noResult(newState));
            } catch (Exception e) {
                return Trampoline.done(EffectResult.failure(state, e));
            }
        };
    }
    
    static <S, E> Effect<S, E, Void> log(String message) {
        return (state, msg, context) -> {
            context.getLogger().info(message);
            return Trampoline.done(EffectResult.noResult(state));
        };
    }
    
    static <S, E> Effect<S, E, Void> logError(String message) {
        return (state, msg, context) -> {
            context.getLogger().error(message);
            return Trampoline.done(EffectResult.noResult(state));
        };
    }
    
    static <S, E> Effect<S, E, Void> logError(String message, Throwable error) {
        return (state, msg, context) -> {
            context.getLogger().error(message, error);
            return Trampoline.done(EffectResult.noResult(state));
        };
    }
    
    static <S, E> Effect<S, E, Void> logState(java.util.function.Function<S, String> formatter) {
        return (state, msg, context) -> {
            context.getLogger().info(formatter.apply(state));
            return Trampoline.done(EffectResult.noResult(state));
        };
    }
    
    static <S, E, M> Effect<S, E, Void> tell(Pid target, M message) {
        return (state, msg, context) -> {
            target.tell(message);
            return Trampoline.done(EffectResult.noResult(state));
        };
    }
    
    static <S, E, M> Effect<S, E, Void> tellSelf(M message) {
        return (state, msg, context) -> {
            context.self().tell(message);
            return Trampoline.done(EffectResult.noResult(state));
        };
    }
    
    /**
     * Sends a message to an actor and waits for a response (request-response pattern).
     * This is a suspension point - the actor's virtual thread will be suspended
     * (non-blockingly) until the response arrives or the timeout expires.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, Throwable, UserData> getUserData = 
     *     Effect.ask(userService, new GetUser(userId), Duration.ofSeconds(5))
     *         .recover(error -> UserData.empty());
     * }</pre>
     * 
     * @param target the actor to send the message to
     * @param message the message to send
     * @param timeout how long to wait for a response
     * @return an effect that produces the response value
     */
    static <S, E, M, R> Effect<S, E, R> ask(Pid target, M message, Duration timeout) {
        return (state, msg, context) -> {
            try {
                R response = target.<M, R>ask(message, timeout).get();
                return Trampoline.done(EffectResult.success(state, response));
            } catch (Exception e) {
                return Trampoline.done(EffectResult.failure(state, e));
            }
        };
    }
    
    /**
     * Alias for {@link #of(Object)}. Creates an effect that returns a pure value.
     */
    static <S, E, R> Effect<S, E, R> pure(R value) {
        return of(value);
    }
    
    /**
     * Conditional effect execution. Runs the effect if the predicate is true,
     * otherwise runs the fallback effect.
     * 
     * @param predicate the condition to test
     * @param effect the effect to run if predicate is true
     * @param fallback the effect to run if predicate is false
     * @return an effect that conditionally executes based on the predicate
     */
    static <S, E, R> Effect<S, E, R> when(
            Predicate<Object> predicate,
            Effect<S, E, R> effect,
            Effect<S, E, R> fallback) {
        return (state, message, context) -> {
            if (predicate.test(message)) {
                return effect.runT(state, message, context);
            } else {
                return fallback.runT(state, message, context);
            }
        };
    }
    
    /**
     * Conditional effect execution. Runs the effect if the predicate is true,
     * otherwise does nothing.
     * 
     * @param predicate the condition to test
     * @param effect the effect to run if predicate is true
     * @return an effect that conditionally executes based on the predicate
     */
    static <S, E> Effect<S, E, Void> when(
            Predicate<Object> predicate,
            Effect<S, E, Void> effect) {
        return when(predicate, effect, identity());
    }
    
    /**
     * Creates an effect that suspends execution for the specified duration.
     * Uses virtual thread blocking, which is safe and efficient.
     * 
     * @param duration how long to suspend
     * @return an effect that completes after the delay
     */
    static <S, E> Effect<S, E, Void> delay(Duration duration) {
        return (state, message, context) -> {
            try {
                Thread.sleep(duration.toMillis());
                return Trampoline.done(EffectResult.noResult(state));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Trampoline.done(EffectResult.failure(state, e));
            }
        };
    }
    
    /**
     * Creates an effect from a lazy computation that will be evaluated when run.
     * The computation is suspended until the effect is executed.
     * 
     * @param computation the suspended computation
     * @return an effect that evaluates the computation
     */
    static <S, E, R> Effect<S, E, R> suspend(ThrowingSupplier<R> computation) {
        return attempt(computation);
    }
    
    /**
     * Resource management with automatic cleanup. Ensures the release effect
     * runs even if the use effect fails.
     * 
     * <pre>{@code
     * Effect.bracket(
     *     Effect.attempt(() -> openConnection()),
     *     conn -> Effect.attempt(() -> conn.query(sql)),
     *     conn -> Effect.attempt(() -> conn.close())
     * )
     * }</pre>
     * 
     * @param acquire effect to acquire the resource
     * @param use effect to use the resource
     * @param release effect to release the resource
     * @return an effect that manages the resource lifecycle
     */
    static <S, E, R, Resource> Effect<S, E, R> bracket(
            Effect<S, E, Resource> acquire,
            Function<Resource, Effect<S, E, R>> use,
            Function<Resource, Effect<S, E, Void>> release) {
        return (state, message, context) -> {
            return acquire.runT(state, message, context).flatMap(acquireResult -> {
                if (acquireResult.isFailure()) {
                    return Trampoline.done(EffectResult.failure(acquireResult.state(), acquireResult.error().get()));
                }
                
                Resource resource = acquireResult.value().orElseThrow();
                S acquireState = acquireResult.state();
                
                return use.apply(resource).runT(acquireState, message, context).flatMap(useResult -> {
                    // Always run release, even if use failed
                    return release.apply(resource).runT(useResult.state(), message, context).map(releaseResult -> {
                        // If use succeeded, return its result; if use failed, preserve the failure
                        if (useResult.isFailure()) {
                            return EffectResult.failure(releaseResult.state(), useResult.error().get());
                        } else {
                            return EffectResult.success(releaseResult.state(), useResult.value().orElse(null));
                        }
                    });
                });
            });
        };
    }
    
    /**
     * Creates an effect from a CompletableFuture. The effect will block
     * (safely on virtual threads) until the future completes.
     * 
     * @param future the future to convert
     * @return an effect that produces the future's result
     */
    static <S, E, R> Effect<S, E, R> fromFuture(java.util.concurrent.CompletableFuture<R> future) {
        return (state, message, context) -> {
            try {
                R result = future.get();
                return Trampoline.done(EffectResult.success(state, result));
            } catch (Exception e) {
                return Trampoline.done(EffectResult.failure(state, e));
            }
        };
    }
    
    /**
     * Traverses a collection, applying an effect to each element in parallel,
     * and collecting the results.
     * 
     * @param items the collection to traverse
     * @param f function to create an effect for each item
     * @return an effect that produces a list of results
     */
    static <S, E, A, B> Effect<S, E, java.util.List<B>> parTraverse(
            java.util.Collection<A> items,
            Function<A, Effect<S, E, B>> f) {
        return (state, message, context) -> {
            java.util.List<java.util.concurrent.CompletableFuture<EffectResult<S, B>>> futures = 
                new java.util.ArrayList<>();
            
            for (A item : items) {
                futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> 
                    f.apply(item).run(state, message, context)
                ));
            }
            
            try {
                java.util.List<B> results = new java.util.ArrayList<>();
                S finalState = state;
                
                for (var future : futures) {
                    EffectResult<S, B> result = future.get();
                    if (result.isFailure()) {
                        return Trampoline.done(EffectResult.failure(result.state(), result.error().get()));
                    }
                    results.add(result.value().orElseThrow());
                    finalState = result.state();
                }
                
                return Trampoline.done(EffectResult.success(finalState, results));
            } catch (Exception e) {
                return Trampoline.done(EffectResult.failure(state, e));
            }
        };
    }
    
    /**
     * Ensures a finalizer effect runs after this effect completes,
     * regardless of success or failure.
     * 
     * @param finalizer the effect to run after completion
     * @return an effect that guarantees the finalizer runs
     */
    default Effect<S, E, R> ensure(Effect<S, E, Void> finalizer) {
        return (state, message, context) -> {
            return runT(state, message, context).flatMap(result -> {
                return finalizer.runT(result.state(), message, context).map(finalizerResult -> {
                    // Preserve original result, but use finalizer's state
                    if (result.isSuccess()) {
                        return EffectResult.success(finalizerResult.state(), result.value().orElse(null));
                    } else {
                        return EffectResult.failure(finalizerResult.state(), result.error().get());
                    }
                });
            });
        };
    }
    
    /**
     * Retries this effect up to maxAttempts times with exponential backoff.
     * 
     * @param maxAttempts maximum number of retry attempts
     * @param initialDelay initial delay between retries
     * @return an effect that retries on failure
     */
    default Effect<S, E, R> retry(int maxAttempts, Duration initialDelay) {
        return (state, message, context) -> {
            EffectResult<S, R> result = run(state, message, context);
            int attempts = 1;
            long delayMillis = initialDelay.toMillis();
            
            while (result.isFailure() && attempts < maxAttempts) {
                try {
                    Thread.sleep(delayMillis);
                    delayMillis *= 2; // Exponential backoff
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Trampoline.done(EffectResult.failure(state, e));
                }
                
                result = run(result.state(), message, context);
                attempts++;
            }
            
            return Trampoline.done(result);
        };
    }
    
    // ============================================================================
    // Monadic Operations - Stack-Safe
    // ============================================================================
    
    default <R2> Effect<S, E, R2> map(Function<R, R2> f) {
        return (state, message, context) -> 
            runT(state, message, context).map(result -> result.map(f));
    }
    
    default <R2> Effect<S, E, R2> flatMap(Function<R, Effect<S, E, R2>> f) {
        return (state, message, context) -> 
            runT(state, message, context).flatMap(result -> 
                result.value()
                    .map(v -> f.apply(v).runT(result.state(), message, context))
                    .orElse(Trampoline.done(EffectResult.noResult(result.state())))
            );
    }
    
    default <R2> Effect<S, E, R2> andThen(Effect<S, E, R2> next) {
        return (state, message, context) -> 
            runT(state, message, context).flatMap(result -> 
                next.runT(result.state(), message, context)
            );
    }
    
    // ============================================================================
    // Error Channel
    // ============================================================================
    
    default Effect<S, E, R> attempt() {
        return (state, message, context) -> {
            try {
                return runT(state, message, context);
            } catch (Throwable t) {
                return Trampoline.done(EffectResult.failure(state, t));
            }
        };
    }
    
    default Effect<S, E, R> handleErrorWith(
            QuadFunction<Throwable, S, Object, ActorContext, Effect<S, E, R>> handler) {
        return (state, message, context) -> 
            runT(state, message, context).flatMap(result -> {
                if (result instanceof EffectResult.Failure<S, R> failure) {
                    return handler.apply(failure.errorValue(), state, message, context)
                        .runT(state, message, context);
                }
                return Trampoline.done(result);
            });
    }
    
    default Effect<S, E, R> handleError(
            QuadFunction<Throwable, S, Object, ActorContext, S> handler) {
        return handleErrorWith((err, s, m, c) -> 
            (st, ms, ct) -> Trampoline.done(EffectResult.noResult(handler.apply(err, s, m, c)))
        );
    }
    
    default Effect<S, E, R> tapError(Consumer<Throwable> action) {
        return (state, message, context) -> 
            runT(state, message, context).map(result -> {
                if (result instanceof EffectResult.Failure<S, R> failure) {
                    action.accept(failure.errorValue());
                }
                return result;
            });
    }
    
    default Effect<S, E, R> orElse(Effect<S, E, R> fallback) {
        return (state, message, context) -> 
            runT(state, message, context).flatMap(result -> {
                if (result instanceof EffectResult.Failure<S, R>) {
                    return fallback.runT(state, message, context);
                }
                return Trampoline.done(result);
            });
    }
    
    default Effect<S, E, R> recover(Function<Throwable, R> recovery) {
        return (state, message, context) -> 
            runT(state, message, context).map(result -> {
                if (result instanceof EffectResult.Failure<S, R> failure) {
                    try {
                        R recovered = recovery.apply(failure.errorValue());
                        return EffectResult.success(failure.state(), recovered);
                    } catch (Exception e) {
                        return EffectResult.failure(failure.state(), e);
                    }
                }
                return result;
            });
    }
    
    default Effect<S, E, R> recoverWith(Function<Throwable, Effect<S, E, R>> recovery) {
        return handleErrorWith((err, s, m, c) -> recovery.apply(err));
    }
    
    default Effect<S, E, R> onError(Consumer<Throwable> action) {
        return tapError(action);
    }
    
    default <R2, R3> Effect<S, E, R3> zip(
            Effect<S, E, R2> other,
            BiFunction<R, R2, R3> combiner) {
        return (state, message, context) -> 
            runT(state, message, context).flatMap(result1 -> {
                if (result1 instanceof EffectResult.Failure<S, R> f) {
                    return Trampoline.done(EffectResult.failure(f.state(), f.errorValue()));
                }
                
                return other.runT(result1.state(), message, context).map(result2 -> {
                    if (result2 instanceof EffectResult.Failure<S, R2> f) {
                        return EffectResult.failure(f.state(), f.errorValue());
                    }
                    
                    java.util.Optional<R> val1 = result1.value();
                    java.util.Optional<R2> val2 = result2.value();
                    
                    if (val1.isPresent() && val2.isPresent()) {
                        R3 combined = combiner.apply(val1.get(), val2.get());
                        return EffectResult.success(result2.state(), combined);
                    }
                    
                    return EffectResult.noResult(result2.state());
                });
            });
    }
    
    // ============================================================================
    // Validation
    // ============================================================================
    
    /**
     * Filters the result value based on a predicate. If the predicate fails,
     * the effect fails with an error created by the error factory.
     * 
     * <p>The error factory receives the value that failed validation, allowing
     * you to create rich, context-aware error messages.
     * 
     * <p>Example with custom exception:
     * <pre>{@code
     * record ValidationError(String field, Object value, String reason) extends Exception {
     *     ValidationError(String field, Object value, String reason) {
     *         super(String.format("%s validation failed: %s (value: %s)", field, reason, value));
     *     }
     * }
     * 
     * Effect<State, ValidationError, Integer> validated = 
     *     Effect.of(value)
     *         .filter(v -> v > 0, 
     *                 v -> new ValidationError("amount", v, "must be positive"));
     * }</pre>
     * 
     * @param predicate the predicate to test the result value
     * @param errorFactory function that creates an error from the failed value
     * @return an effect that fails if the predicate is not satisfied
     */
    default Effect<S, E, R> filter(
            Predicate<R> predicate,
            Function<R, ? extends E> errorFactory) {
        return (state, message, context) -> 
            runT(state, message, context).map(result -> {
                if (result instanceof EffectResult.Success<S, R> success) {
                    R value = success.value().orElseThrow();
                    if (predicate.test(value)) {
                        return result;
                    } else {
                        E error = errorFactory.apply(value);
                        // Cast to Throwable - E must extend Throwable for Effect to work
                        return EffectResult.failure(success.state(), (Throwable) error);
                    }
                }
                return result;
            });
    }
    
    /**
     * Filters the state based on a predicate. If the predicate fails,
     * falls back to an alternative effect.
     * 
     * @param predicate the predicate to test the state
     * @param fallback the effect to run if predicate fails
     * @return an effect that uses fallback if the predicate is not satisfied
     */
    default Effect<S, E, R> filterOrElse(
            Predicate<S> predicate,
            Effect<S, E, R> fallback) {
        return (state, message, context) -> 
            runT(state, message, context).flatMap(result -> {
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
                return Trampoline.done(result);
            });
    }
    
    // ============================================================================
    // Side Effects
    // ============================================================================
    
    default Effect<S, E, R> tap(Consumer<R> action) {
        return (state, message, context) -> 
            runT(state, message, context).map(result -> {
                result.value().ifPresent(action);
                return result;
            });
    }
    
    default Effect<S, E, R> tapState(Consumer<S> action) {
        return (state, message, context) -> 
            runT(state, message, context).map(result -> {
                action.accept(result.state());
                return result;
            });
    }
    
    // ============================================================================
    // Parallel Execution - Stack-Safe
    // ============================================================================
    
    default <R2, R3> Effect<S, E, R3> parZip(
            Effect<S, E, R2> other,
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
    
    static <S, E, R> Effect<S, E, java.util.List<R>> parSequence(
            java.util.List<Effect<S, E, R>> effects) {
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
    
    static <S, E, R> Effect<S, E, java.util.List<R>> sequence(
            java.util.List<Effect<S, E, R>> effects) {
        return (state, message, context) -> {
            Trampoline<EffectResult<S, java.util.List<R>>> result = 
                Trampoline.done(EffectResult.success(state, new java.util.ArrayList<R>()));
            
            for (Effect<S, E, R> effect : effects) {
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
    // Race and Timeout
    // ============================================================================
    
    default Effect<S, E, R> race(Effect<S, E, R> other) {
        return (state, message, context) -> Trampoline.delay(() -> {
            java.util.concurrent.CompletableFuture<EffectResult<S, R>> future1 = 
                java.util.concurrent.CompletableFuture.supplyAsync(() -> 
                    this.run(state, message, context)
                );
            
            java.util.concurrent.CompletableFuture<EffectResult<S, R>> future2 = 
                java.util.concurrent.CompletableFuture.supplyAsync(() -> 
                    other.run(state, message, context)
                );
            
            try {
                return (EffectResult<S, R>) java.util.concurrent.CompletableFuture.anyOf(future1, future2).get();
            } catch (Exception e) {
                return EffectResult.failure(state, e);
            }
        });
    }
    
    default Effect<S, E, R> withTimeout(Duration timeout) {
        return (state, message, context) -> Trampoline.delay(() -> {
            java.util.concurrent.CompletableFuture<EffectResult<S, R>> future = 
                java.util.concurrent.CompletableFuture.supplyAsync(() -> 
                    this.run(state, message, context)
                );
            
            try {
                return future.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                return EffectResult.failure(state, e);
            } catch (Exception e) {
                return EffectResult.failure(state, e);
            }
        });
    }
    
    // ============================================================================
    // Match Builder
    // ============================================================================
    
    static <S, E, R, M> EffectMatchBuilder<S, E, R, M> match() {
        return new EffectMatchBuilder<>();
    }
    
    /**
     * Functional interface for functions that take four arguments.
     */
    @FunctionalInterface
    interface QuadFunction<A, B, C, D, R> {
        R apply(A a, B b, C c, D d);
    }
}
