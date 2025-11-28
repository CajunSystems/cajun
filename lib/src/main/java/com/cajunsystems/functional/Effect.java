package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;
import com.cajunsystems.Pid;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Represents a computation that:
 * - Takes a state and message
 * - Produces a new state
 * - May produce a result
 * - May perform side effects (logging, sending messages, etc.)
 * - May fail with an error
 * 
 * <p>The Effect monad provides a composable, type-safe way to build actor behaviors
 * using functional programming patterns. It integrates seamlessly with Java's Stream API
 * and reactive libraries.
 * 
 * <p>Example usage:
 * <pre>{@code
 * Effect<Integer, CounterMsg, Void> counterEffect = Effect.match()
 *     .when(Increment.class, (state, msg, ctx) ->
 *         Effect.modify(s -> s + msg.amount())
 *             .andThen(Effect.logState(s -> "Count: " + s))
 *     )
 *     .when(GetCount.class, (state, msg, ctx) ->
 *         Effect.of(state)
 *             .tap(count -> ctx.tell(msg.replyTo(), count))
 *     );
 * }</pre>
 * 
 * @param <State> The type of the actor's state
 * @param <Message> The type of messages the actor processes
 * @param <Result> The type of result produced by the effect
 */
@FunctionalInterface
public interface Effect<State, Message, Result> {
    
    /**
     * Execute the effect with the given state, message, and context.
     * Returns an EffectResult containing the new state and optional result.
     * 
     * @param state The current state
     * @param message The message being processed
     * @param context The actor context for side effects
     * @return The result of executing the effect
     */
    EffectResult<State, Result> run(State state, Message message, ActorContext context);
    
    // ============================================================================
    // Factory Methods - Creating Effects
    // ============================================================================
    
    /**
     * Creates an effect that returns a value without changing state.
     * This is the monadic "return" or "pure" operation, named idiomatically for Java.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<Integer, Msg, String> effect = Effect.of("success");
     * }</pre>
     * 
     * @param value The value to return
     * @return An effect that produces the given value
     */
    static <S, M, R> Effect<S, M, R> of(R value) {
        return (state, message, context) -> EffectResult.success(state, value);
    }
    
    /**
     * Creates an effect that returns the current state as the result without modification.
     * Useful for query operations that need to return the state.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<Integer, GetCount, Integer> effect = Effect.state();
     * }</pre>
     * 
     * @return An effect that returns the current state
     */
    static <S, M> Effect<S, M, S> state() {
        return (state, message, context) -> EffectResult.success(state, state);
    }
    
    /**
     * Creates an effect that modifies the state using the given function.
     * The effect produces no result value.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<Integer, Msg, Void> effect = Effect.modify(s -> s + 1);
     * }</pre>
     * 
     * @param f The function to apply to the state
     * @return An effect that modifies the state
     */
    static <S, M> Effect<S, M, Void> modify(Function<S, S> f) {
        return (state, message, context) -> EffectResult.noResult(f.apply(state));
    }
    
    /**
     * Creates an effect that returns the state unchanged (identity function).
     * This is a cleaner alternative to {@code Effect.modify(s -> s)}.
     * 
     * <p>Use this when you need to handle a message but don't need to change the state,
     * such as when forwarding messages to other actors or performing side effects only.
     * 
     * <p>Example:
     * <pre>{@code
     * .when(QueryKey.class, (state, msg, ctx) -> {
     *     otherActor.tell(new Query(msg.key()));
     *     return Effect.identity();
     * })
     * }</pre>
     * 
     * @return An effect that returns the state unchanged
     */
    static <S, M> Effect<S, M, Void> identity() {
        return (state, message, context) -> EffectResult.noResult(state);
    }
    
    /**
     * Creates an effect that sets the state to a specific value.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<Integer, Reset, Void> effect = Effect.setState(0);
     * }</pre>
     * 
     * @param newState The new state value
     * @return An effect that sets the state
     */
    static <S, M> Effect<S, M, Void> setState(S newState) {
        return (state, message, context) -> EffectResult.noResult(newState);
    }
    
    /**
     * Creates an effect from a state transition function that takes both state and message.
     * This is a bridge to the traditional actor model approach.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<Integer, Increment, Void> effect = 
     *     Effect.fromTransition((s, msg) -> s + msg.amount());
     * }</pre>
     * 
     * @param f The state transition function
     * @return An effect that applies the transition
     */
    static <S, M> Effect<S, M, Void> fromTransition(BiFunction<S, M, S> f) {
        return (state, message, context) -> EffectResult.noResult(f.apply(state, message));
    }
    
    /**
     * Creates an effect that always fails with the given error.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, Msg, Result> effect = 
     *     Effect.fail(new IllegalStateException("Invalid state"));
     * }</pre>
     * 
     * @param error The error to fail with
     * @return An effect that fails
     */
    static <S, M, R> Effect<S, M, R> fail(Throwable error) {
        return (state, message, context) -> EffectResult.failure(state, error);
    }
    
    /**
     * Creates an effect that does nothing (identity effect).
     * Useful as a no-op or default case.
     * 
     * @return An effect that leaves state unchanged and produces no result
     */
    static <S, M> Effect<S, M, Void> none() {
        return (state, message, context) -> EffectResult.noResult(state);
    }
    
    // ============================================================================
    // Monadic Operations - Transforming and Composing Effects
    // ============================================================================
    
    /**
     * Transforms the result of this effect using the given function.
     * This is the functor "map" operation.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, Msg, Integer> countEffect = ...;
     * Effect<State, Msg, String> stringEffect = 
     *     countEffect.map(count -> "Count: " + count);
     * }</pre>
     * 
     * @param f The function to transform the result
     * @return A new effect with the transformed result
     */
    default <R2> Effect<State, Message, R2> map(Function<Result, R2> f) {
        return (state, message, context) -> {
            EffectResult<State, Result> result = this.run(state, message, context);
            return switch (result) {
                case EffectResult.Success<State, Result> success -> 
                    EffectResult.success(success.state(), f.apply(success.resultValue()));
                case EffectResult.NoResult<State, Result> noResult -> 
                    EffectResult.noResult(noResult.state());
                case EffectResult.Failure<State, Result> failure -> 
                    EffectResult.failure(failure.state(), failure.errorValue());
            };
        };
    }
    
    /**
     * Chains this effect with another effect that depends on this effect's result.
     * This is the monadic "bind" or "flatMap" operation.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, Msg, User> userEffect = ...;
     * Effect<State, Msg, Order> orderEffect = 
     *     userEffect.flatMap(user -> Effect.of(user.getLastOrder()));
     * }</pre>
     * 
     * @param f The function that produces the next effect based on this effect's result
     * @return A new effect that chains the two effects
     */
    default <R2> Effect<State, Message, R2> flatMap(Function<Result, Effect<State, Message, R2>> f) {
        return (state, message, context) -> {
            EffectResult<State, Result> result = this.run(state, message, context);
            return switch (result) {
                case EffectResult.Success<State, Result> success -> 
                    f.apply(success.resultValue()).run(success.state(), message, context);
                case EffectResult.NoResult<State, Result> noResult -> 
                    EffectResult.noResult(noResult.state());
                case EffectResult.Failure<State, Result> failure -> 
                    EffectResult.failure(failure.state(), failure.errorValue());
            };
        };
    }
    
    /**
     * Sequences this effect with another effect, discarding this effect's result.
     * The next effect runs with the state produced by this effect.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, Msg, Void> combined = 
     *     Effect.modify(s -> s.increment())
     *         .andThen(Effect.logState(s -> "New state: " + s));
     * }</pre>
     * 
     * @param next The effect to run after this one
     * @return A new effect that runs both effects in sequence
     */
    default <R2> Effect<State, Message, R2> andThen(Effect<State, Message, R2> next) {
        return (state, message, context) -> {
            EffectResult<State, Result> result = this.run(state, message, context);
            return switch (result) {
                case EffectResult.Success<State, Result> success -> 
                    next.run(success.state(), message, context);
                case EffectResult.NoResult<State, Result> noResult -> 
                    next.run(noResult.state(), message, context);
                case EffectResult.Failure<State, Result> failure -> 
                    EffectResult.failure(failure.state(), failure.errorValue());
            };
        };
    }
    
    /**
     * Provides a fallback effect if this effect fails.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, Msg, Result> robust = 
     *     riskyEffect.orElse(Effect.of(defaultValue));
     * }</pre>
     * 
     * @param fallback The effect to run if this effect fails
     * @return A new effect with fallback behavior
     */
    default Effect<State, Message, Result> orElse(Effect<State, Message, Result> fallback) {
        return (state, message, context) -> {
            EffectResult<State, Result> result = this.run(state, message, context);
            return switch (result) {
                case EffectResult.Failure<State, Result> failure -> 
                    fallback.run(failure.state(), message, context);
                default -> result;
            };
        };
    }
    
    /**
     * Recovers from a failure by transforming the error into a result.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, Msg, String> safe = 
     *     riskyEffect.recover(error -> "Error: " + error.getMessage());
     * }</pre>
     * 
     * @param f The function to transform errors into results
     * @return A new effect that cannot fail
     */
    default Effect<State, Message, Result> recover(Function<Throwable, Result> f) {
        return (state, message, context) -> {
            EffectResult<State, Result> result = this.run(state, message, context);
            return switch (result) {
                case EffectResult.Failure<State, Result> failure -> 
                    EffectResult.success(failure.state(), f.apply(failure.errorValue()));
                default -> result;
            };
        };
    }
    
    /**
     * Recovers from a failure by running another effect.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, Msg, Result> safe = 
     *     riskyEffect.recoverWith(error -> Effect.of(defaultValue));
     * }</pre>
     * 
     * @param f The function that produces a recovery effect from the error
     * @return A new effect with recovery behavior
     */
    default Effect<State, Message, Result> recoverWith(Function<Throwable, Effect<State, Message, Result>> f) {
        return (state, message, context) -> {
            EffectResult<State, Result> result = this.run(state, message, context);
            return switch (result) {
                case EffectResult.Failure<State, Result> failure -> 
                    f.apply(failure.errorValue()).run(failure.state(), message, context);
                default -> result;
            };
        };
    }
    
    /**
     * Filters the result of this effect using a predicate.
     * If the predicate fails, the effect fails with an IllegalStateException.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, Msg, Integer> validated = 
     *     effect.filter(count -> count > 0, "Count must be positive");
     * }</pre>
     * 
     * @param predicate The predicate to test the result
     * @param errorMsg The error message if the predicate fails
     * @return A new effect with validation
     */
    default Effect<State, Message, Result> filter(Predicate<Result> predicate, String errorMsg) {
        return (state, message, context) -> {
            EffectResult<State, Result> result = this.run(state, message, context);
            return switch (result) {
                case EffectResult.Success<State, Result> success -> 
                    predicate.test(success.resultValue()) 
                        ? result 
                        : EffectResult.failure(success.state(), new IllegalStateException(errorMsg));
                default -> result;
            };
        };
    }
    
    /**
     * Filters the result of this effect using a predicate.
     * If the predicate fails, executes a fallback effect instead (e.g., send error reply, keep state).
     * 
     * <p>This is more flexible than {@link #filter} as it allows custom error handling
     * without failing the actor. Common use cases:
     * <ul>
     *   <li>Send an error response to the sender</li>
     *   <li>Log validation failures</li>
     *   <li>Keep the state unchanged (no-reply scenario)</li>
     * </ul>
     * 
     * <p>Example - Send error reply on validation failure:
     * <pre>{@code
     * .when(Withdraw.class, (state, msg, ctx) -> {
     *     return Effect.<BankState, Withdraw, Void>modify(s -> 
     *         new BankState(s.balance() - msg.amount())
     *     )
     *     .filterOrElse(
     *         s -> s.balance() >= 0,
     *         (s, m, c) -> {
     *             m.replyTo().tell(new Error("Insufficient funds"));
     *             return Effect.identity();
     *         }
     *     );
     * })
     * }</pre>
     * 
     * <p>Example - No reply on validation failure:
     * <pre>{@code
     * effect.filterOrElse(
     *     result -> result.isValid(),
     *     (state, msg, ctx) -> Effect.identity()  // Keep state, no reply
     * )
     * }</pre>
     * 
     * @param predicate The predicate to test the state after this effect
     * @param fallback The effect to execute if the predicate fails
     * @return A new effect with conditional execution
     */
    default Effect<State, Message, Result> filterOrElse(
            Predicate<State> predicate, 
            Effect<State, Message, Result> fallback) {
        return (state, message, context) -> {
            EffectResult<State, Result> result = this.run(state, message, context);
            return switch (result) {
                case EffectResult.Success<State, Result> success -> 
                    predicate.test(success.state()) 
                        ? result 
                        : fallback.run(state, message, context);  // Use original state for fallback
                default -> result;  // Propagate failures
            };
        };
    }
    
    /**
     * Performs a side effect with the result without changing it.
     * Useful for logging, notifications, etc.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, Msg, Result> logged = 
     *     effect.tap(result -> System.out.println("Result: " + result));
     * }</pre>
     * 
     * @param action The side effect to perform
     * @return A new effect with the side effect
     */
    default Effect<State, Message, Result> tap(Consumer<Result> action) {
        return (state, message, context) -> {
            EffectResult<State, Result> result = this.run(state, message, context);
            result.value().ifPresent(action);
            return result;
        };
    }
    
    /**
     * Performs a side effect with the state without changing it.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, Msg, Result> logged = 
     *     effect.tapState(state -> System.out.println("State: " + state));
     * }</pre>
     * 
     * @param action The side effect to perform with the state
     * @return A new effect with the side effect
     */
    default Effect<State, Message, Result> tapState(Consumer<State> action) {
        return (state, message, context) -> {
            EffectResult<State, Result> result = this.run(state, message, context);
            action.accept(result.state());
            return result;
        };
    }
    
    /**
     * Performs a side effect with both state and result without changing them.
     * 
     * @param action The side effect to perform
     * @return A new effect with the side effect
     */
    default Effect<State, Message, Result> tapBoth(java.util.function.BiConsumer<State, Optional<Result>> action) {
        return (state, message, context) -> {
            EffectResult<State, Result> result = this.run(state, message, context);
            action.accept(result.state(), result.value());
            return result;
        };
    }
    
    // ============================================================================
    // Error Channel - Exception Handling
    // ============================================================================
    
    /**
     * Handles errors by transforming them into a fallback effect.
     * This allows graceful error recovery without crashing the actor.
     * 
     * <p>Use this for checked exceptions or when you want to send error replies to clients.
     * 
     * <p>Example - Send error reply on exception:
     * <pre>{@code
     * .when(LoadFile.class, (state, msg, ctx) -> {
     *     return Effect.<State, LoadFile, Void>modify(s -> {
     *         String content = Files.readString(Path.of(msg.filename()));  // May throw IOException
     *         return new State(content);
     *     })
     *     .handleErrorWith((error, s, m, c) -> {
     *         m.replyTo().tell(new ErrorResponse(error.getMessage()));
     *         return Effect.identity();  // Keep state unchanged
     *     });
     * })
     * }</pre>
     * 
     * @param handler Function that takes (error, state, message, context) and returns a fallback effect
     * @return A new effect with error handling
     */
    default Effect<State, Message, Result> handleErrorWith(
            QuadFunction<Throwable, State, Message, com.cajunsystems.ActorContext, Effect<State, Message, Result>> handler) {
        return (state, message, context) -> {
            try {
                EffectResult<State, Result> result = this.run(state, message, context);
                return switch (result) {
                    case EffectResult.Failure<State, Result> failure -> 
                        handler.apply(failure.errorValue(), state, message, context).run(state, message, context);
                    default -> result;
                };
            } catch (Exception e) {
                // Catch any uncaught exceptions and route through error handler
                return handler.apply(e, state, message, context).run(state, message, context);
            }
        };
    }
    
    /**
     * Handles errors by providing a recovery function that produces a new state.
     * Simpler version of {@link #handleErrorWith} when you just need to recover the state.
     * 
     * <p>Example - Recover with default state:
     * <pre>{@code
     * effect.handleError((error, state, msg, ctx) -> {
     *     ctx.getLogger().error("Operation failed", error);
     *     return state;  // Keep current state
     * })
     * }</pre>
     * 
     * @param handler Function that takes (error, state, message, context) and returns a recovery state
     * @return A new effect with error handling
     */
    default Effect<State, Message, Result> handleError(
            QuadFunction<Throwable, State, Message, com.cajunsystems.ActorContext, State> handler) {
        return handleErrorWith((error, state, msg, ctx) -> {
            State recoveredState = handler.apply(error, state, msg, ctx);
            return (s, m, c) -> EffectResult.noResult(recoveredState);
        });
    }
    
    /**
     * Wraps this effect to catch all exceptions and convert them to Failure results.
     * This is useful for effects that may throw checked exceptions.
     * 
     * <p>Unlike {@link #handleErrorWith}, this doesn't provide recovery - it just
     * ensures exceptions are captured in the error channel rather than propagating.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, LoadFile, String> safeLoad = Effect.<State, LoadFile, String>modify(s -> {
     *     return Files.readString(Path.of(msg.filename()));  // May throw IOException
     * }).attempt();
     * 
     * // Later, check for errors
     * safeLoad.tapError(error -> 
     *     context.getLogger().error("File load failed", error)
     * );
     * }</pre>
     * 
     * @return A new effect that captures all exceptions
     */
    default Effect<State, Message, Result> attempt() {
        return (state, message, context) -> {
            try {
                return this.run(state, message, context);
            } catch (Exception e) {
                return EffectResult.failure(state, e);
            }
        };
    }
    
    /**
     * Performs a side effect when an error occurs, without changing the error.
     * Useful for logging or sending notifications about errors.
     * 
     * <p>Example:
     * <pre>{@code
     * effect
     *     .attempt()
     *     .tapError(error -> 
     *         context.getLogger().error("Operation failed", error)
     *     )
     *     .handleErrorWith((err, s, m, c) -> {
     *         m.replyTo().tell(new ErrorResponse(err.getMessage()));
     *         return Effect.identity();
     *     });
     * }</pre>
     * 
     * @param action The side effect to perform with the error
     * @return A new effect with the error tap
     */
    default Effect<State, Message, Result> tapError(Consumer<Throwable> action) {
        return (state, message, context) -> {
            EffectResult<State, Result> result = this.run(state, message, context);
            if (result instanceof EffectResult.Failure<State, Result> failure) {
                action.accept(failure.errorValue());
            }
            return result;
        };
    }
    
    /**
     * Functional interface for functions that take four arguments.
     */
    @FunctionalInterface
    interface QuadFunction<A, B, C, D, R> {
        R apply(A a, B b, C c, D d);
    }
    
    // ============================================================================
    // Parallel Execution - Concurrent Effects
    // ============================================================================
    
    /**
     * Executes two effects in parallel and combines their results.
     * Both effects run concurrently with the same initial state.
     * 
     * <p>Example - Query multiple services in parallel:
     * <pre>{@code
     * Effect<State, Msg, UserData> getUserData = 
     *     Effect.ask(profileService, new GetProfile(userId), Duration.ofSeconds(5));
     * 
     * Effect<State, Msg, List<Order>> getOrders = 
     *     Effect.ask(orderService, new GetOrders(userId), Duration.ofSeconds(5));
     * 
     * Effect<State, Msg, UserView> combined = getUserData.parZip(getOrders,
     *     (profile, orders) -> new UserView(profile, orders)
     * );
     * }</pre>
     * 
     * @param other The other effect to run in parallel
     * @param combiner Function to combine both results
     * @return A new effect that runs both in parallel and combines results
     */
    default <R2, R3> Effect<State, Message, R3> parZip(
            Effect<State, Message, R2> other,
            java.util.function.BiFunction<Result, R2, R3> combiner) {
        return (state, message, context) -> {
            java.util.concurrent.CompletableFuture<EffectResult<State, Result>> future1 = 
                java.util.concurrent.CompletableFuture.supplyAsync(() -> 
                    this.run(state, message, context)
                );
            
            java.util.concurrent.CompletableFuture<EffectResult<State, R2>> future2 = 
                java.util.concurrent.CompletableFuture.supplyAsync(() -> 
                    other.run(state, message, context)
                );
            
            try {
                EffectResult<State, Result> result1 = future1.get();
                EffectResult<State, R2> result2 = future2.get();
                
                // If either failed, return failure
                if (result1 instanceof EffectResult.Failure<State, Result> f1) {
                    return EffectResult.failure(f1.state(), f1.errorValue());
                }
                if (result2 instanceof EffectResult.Failure<State, R2> f2) {
                    return EffectResult.failure(f2.state(), f2.errorValue());
                }
                
                // Combine successful results
                java.util.Optional<Result> val1 = result1.value();
                java.util.Optional<R2> val2 = result2.value();
                
                if (val1.isPresent() && val2.isPresent()) {
                    R3 combined = combiner.apply(val1.get(), val2.get());
                    return EffectResult.success(result1.state(), combined);
                }
                
                return EffectResult.noResult(result1.state());
            } catch (Exception e) {
                return EffectResult.failure(state, e);
            }
        };
    }
    
    /**
     * Executes a list of effects sequentially, threading state through each.
     * Each effect sees the updated state from the previous effect.
     * 
     * <p>Example - Processing pipeline:
     * <pre>{@code
     * List<Effect<State, Msg, Void>> steps = List.of(
     *     Effect.modify(s -> s.validate()),
     *     Effect.modify(s -> s.transform()),
     *     Effect.modify(s -> s.persist())
     * );
     * 
     * Effect<State, Msg, List<Void>> pipeline = Effect.sequence(steps);
     * }</pre>
     * 
     * @param effects List of effects to run sequentially
     * @return A new effect that runs all effects sequentially and collects results
     */
    static <S, M, R> Effect<S, M, java.util.List<R>> sequence(java.util.List<Effect<S, M, R>> effects) {
        return (state, message, context) -> {
            java.util.List<R> results = new java.util.ArrayList<>();
            S currentState = state;
            
            for (Effect<S, M, R> effect : effects) {
                EffectResult<S, R> result = effect.run(currentState, message, context);
                
                // If any failed, return failure immediately
                if (result instanceof EffectResult.Failure<S, R> failure) {
                    return EffectResult.failure(currentState, failure.errorValue());
                }
                
                // Thread state through
                currentState = result.state();
                result.value().ifPresent(results::add);
            }
            
            return EffectResult.success(currentState, results);
        };
    }
    
    /**
     * Executes a list of effects in parallel and collects all results.
     * All effects run concurrently with the same initial state.
     * 
     * <p>Example - Query multiple data sources:
     * <pre>{@code
     * List<Effect<State, Msg, Data>> queries = List.of(
     *     Effect.ask(service1, new Query("data1"), Duration.ofSeconds(5)),
     *     Effect.ask(service2, new Query("data2"), Duration.ofSeconds(5)),
     *     Effect.ask(service3, new Query("data3"), Duration.ofSeconds(5))
     * );
     * 
     * Effect<State, Msg, List<Data>> allData = Effect.parSequence(queries);
     * }</pre>
     * 
     * @param effects List of effects to run in parallel
     * @return A new effect that runs all effects in parallel and collects results
     */
    static <S, M, R> Effect<S, M, java.util.List<R>> parSequence(java.util.List<Effect<S, M, R>> effects) {
        return (state, message, context) -> {
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
                    
                    // If any failed, return failure
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
        };
    }
    
    /**
     * Races multiple effects - returns the result of whichever completes first.
     * Useful for timeout patterns or fallback strategies.
     * 
     * <p>Example - Primary service with fallback:
     * <pre>{@code
     * Effect<State, Msg, Data> primary = 
     *     Effect.ask(primaryService, new Query(id), Duration.ofSeconds(2));
     * 
     * Effect<State, Msg, Data> fallback = 
     *     Effect.ask(cacheService, new GetCached(id), Duration.ofSeconds(5));
     * 
     * Effect<State, Msg, Data> fastest = primary.race(fallback);
     * }</pre>
     * 
     * @param other The other effect to race against
     * @return A new effect that returns the result of whichever completes first
     */
    default Effect<State, Message, Result> race(Effect<State, Message, Result> other) {
        return (state, message, context) -> {
            java.util.concurrent.CompletableFuture<EffectResult<State, Result>> future1 = 
                java.util.concurrent.CompletableFuture.supplyAsync(() -> 
                    this.run(state, message, context)
                );
            
            java.util.concurrent.CompletableFuture<EffectResult<State, Result>> future2 = 
                java.util.concurrent.CompletableFuture.supplyAsync(() -> 
                    other.run(state, message, context)
                );
            
            try {
                // Return whichever completes first
                return java.util.concurrent.CompletableFuture.anyOf(future1, future2)
                    .thenApply(result -> (EffectResult<State, Result>) result)
                    .get();
            } catch (Exception e) {
                return EffectResult.failure(state, e);
            }
        };
    }
    
    /**
     * Executes an effect with a timeout.
     * If the effect doesn't complete within the timeout, returns a failure.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, Msg, Data> timedQuery = 
     *     Effect.ask(slowService, new Query(id), Duration.ofSeconds(30))
     *         .withTimeout(Duration.ofSeconds(5));
     * }</pre>
     * 
     * @param timeout Maximum time to wait for the effect to complete
     * @return A new effect with timeout protection
     */
    default Effect<State, Message, Result> withTimeout(java.time.Duration timeout) {
        return (state, message, context) -> {
            java.util.concurrent.CompletableFuture<EffectResult<State, Result>> future = 
                java.util.concurrent.CompletableFuture.supplyAsync(() -> 
                    this.run(state, message, context)
                );
            
            try {
                return future.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                return EffectResult.failure(state, 
                    new java.util.concurrent.TimeoutException("Effect timed out after " + timeout));
            } catch (Exception e) {
                return EffectResult.failure(state, e);
            }
        };
    }
    
    // ============================================================================
    // Actor-Specific Effects - Messaging and Communication
    // ============================================================================
    
    /**
     * Creates an effect that sends a message to another actor.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, Msg, Void> sendEffect = 
     *     Effect.tell(targetPid, new Notification("Hello"));
     * }</pre>
     * 
     * @param target The target actor's PID
     * @param message The message to send
     * @return An effect that sends the message
     */
    static <S, M> Effect<S, M, Void> tell(Pid target, Object message) {
        return (state, msg, context) -> {
            context.tell(target, message);
            return EffectResult.noResult(state);
        };
    }
    
    /**
     * Creates an effect that sends a message to this actor.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, Msg, Void> selfEffect = 
     *     Effect.tellSelf(new CheckStatus());
     * }</pre>
     * 
     * @param message The message to send to self
     * @return An effect that sends the message to self
     */
    static <S, M> Effect<S, M, Void> tellSelf(Object message) {
        return (state, msg, context) -> {
            context.tellSelf(message);
            return EffectResult.noResult(state);
        };
    }
    
    /**
     * Creates an effect that performs a request-response interaction with another actor.
     * This uses the ask pattern with a timeout.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, Msg, String> askEffect = 
     *     Effect.ask(servicePid, new GetData(), Duration.ofSeconds(5));
     * }</pre>
     * 
     * @param target The target actor's PID
     * @param message The request message
     * @param timeout The timeout duration
     * @return An effect that performs the ask and returns the response
     */
    static <S, M, R> Effect<S, M, R> ask(Pid target, Object message, Duration timeout) {
        return (state, msg, context) -> {
            try {
                @SuppressWarnings("unchecked")
                R response = (R) target.ask(message, timeout).get();
                return EffectResult.success(state, response);
            } catch (Exception e) {
                return EffectResult.failure(state, e);
            }
        };
    }
    
    // ============================================================================
    // Logging Effects
    // ============================================================================
    
    /**
     * Creates an effect that logs a message at INFO level.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, Msg, Void> logEffect = 
     *     Effect.log("Processing started");
     * }</pre>
     * 
     * @param message The message to log
     * @return An effect that logs the message
     */
    static <S, M> Effect<S, M, Void> log(String message) {
        return (state, msg, context) -> {
            context.getLogger().info(message);
            return EffectResult.noResult(state);
        };
    }
    
    /**
     * Creates an effect that logs a message derived from the state.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<Integer, Msg, Void> logEffect = 
     *     Effect.logState(count -> "Current count: " + count);
     * }</pre>
     * 
     * @param messageFunc The function to derive the log message from state
     * @return An effect that logs the derived message
     */
    static <S, M> Effect<S, M, Void> logState(Function<S, String> messageFunc) {
        return (state, msg, context) -> {
            context.getLogger().info(messageFunc.apply(state));
            return EffectResult.noResult(state);
        };
    }
    
    /**
     * Creates an effect that logs an error message.
     * 
     * @param message The error message to log
     * @return An effect that logs the error
     */
    static <S, M> Effect<S, M, Void> logError(String message) {
        return (state, msg, context) -> {
            context.getLogger().error(message);
            return EffectResult.noResult(state);
        };
    }
    
    /**
     * Creates an effect that logs an error with a throwable.
     * 
     * @param message The error message
     * @param error The throwable to log
     * @return An effect that logs the error
     */
    static <S, M> Effect<S, M, Void> logError(String message, Throwable error) {
        return (state, msg, context) -> {
            context.getLogger().error(message, error);
            return EffectResult.noResult(state);
        };
    }
    
    // ============================================================================
    // Pattern Matching Support
    // ============================================================================
    
    /**
     * Creates a pattern matching builder for message-based routing.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<Integer, CounterMsg, Void> effect = Effect.match()
     *     .when(Increment.class, (state, msg, ctx) -> 
     *         Effect.modify(s -> s + msg.amount()))
     *     .when(Decrement.class, (state, msg, ctx) ->
     *         Effect.modify(s -> s - msg.amount()))
     *     .otherwise(Effect.log("Unknown message"));
     * }</pre>
     * 
     * @return A new pattern matching builder
     */
    static <S, M, R> EffectMatcher<S, M, R> match() {
        return new EffectMatcher<>();
    }
    
    /**
     * Creates a conditional effect based on a predicate.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, Msg, Void> conditional = 
     *     Effect.when(
     *         msg -> msg.isValid(),
     *         Effect.modify(s -> s.process(msg)),
     *         Effect.log("Invalid message")
     *     );
     * }</pre>
     * 
     * @param condition The condition to test
     * @param thenEffect The effect to run if condition is true
     * @param elseEffect The effect to run if condition is false
     * @return A conditional effect
     */
    static <S, M, R> Effect<S, M, R> when(
        Predicate<M> condition,
        Effect<S, M, R> thenEffect,
        Effect<S, M, R> elseEffect
    ) {
        return (state, message, context) -> {
            if (condition.test(message)) {
                return thenEffect.run(state, message, context);
            } else {
                return elseEffect.run(state, message, context);
            }
        };
    }
    
    // ============================================================================
    // Utility Methods
    // ============================================================================
    
    /**
     * Wraps a potentially throwing operation in an effect.
     * Exceptions are caught and converted to failures.
     * 
     * <p>Example:
     * <pre>{@code
     * Effect<State, Msg, Result> safe = 
     *     Effect.attempt(() -> riskyOperation());
     * }</pre>
     * 
     * @param operation The operation to attempt
     * @return An effect that safely executes the operation
     */
    static <S, M, R> Effect<S, M, R> attempt(java.util.function.Supplier<R> operation) {
        return (state, message, context) -> {
            try {
                return EffectResult.success(state, operation.get());
            } catch (Exception e) {
                return EffectResult.failure(state, e);
            }
        };
    }
    
    /**
     * Converts this effect to a format compatible with Stream API.
     * Returns an Optional containing the result if successful.
     * 
     * @param state The state to run with
     * @param message The message to run with
     * @param context The context to run with
     * @return An Optional containing the result if successful
     */
    default Optional<Result> toOptional(State state, Message message, ActorContext context) {
        return this.run(state, message, context).value();
    }
}
