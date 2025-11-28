package com.cajunsystems.functional;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Result of executing an effect.
 * Contains the new state and an optional result value.
 *
 * <p>This is a sealed interface with three possible outcomes:
 * <ul>
 *   <li>{@link Success} - Effect executed successfully with a result value</li>
 *   <li>{@link NoResult} - Effect executed successfully but produced no result (state change only)</li>
 *   <li>{@link Failure} - Effect execution failed with a typed error</li>
 * </ul>
 *
 * @param <State> The type of the actor's state
 * @param <Error> The type of errors that can occur
 * @param <Result> The type of result produced by the effect
 */
public sealed interface EffectResult<State, Error, Result> {
    
    /**
     * Gets the state after effect execution.
     * 
     * @return The resulting state
     */
    State state();
    
    /**
     * Successful effect execution with a result value.
     *
     * @param state The resulting state
     * @param resultValue The result value
     */
    record Success<State, Error, Result>(State state, Result resultValue) implements EffectResult<State, Error, Result> {}

    /**
     * Effect execution with no result (state change only).
     *
     * @param state The resulting state
     */
    record NoResult<State, Error, Result>(State state) implements EffectResult<State, Error, Result> {}

    /**
     * Effect execution failed with a typed error.
     *
     * @param state The state at the time of failure
     * @param errorValue The error that occurred
     */
    record Failure<State, Error, Result>(State state, Error errorValue) implements EffectResult<State, Error, Result> {}
    
    // ============================================================================
    // Convenience Factory Methods
    // ============================================================================
    
    /**
     * Creates a successful result with a value.
     *
     * @param state The resulting state
     * @param value The result value
     * @return A Success result
     */
    static <S, E, R> EffectResult<S, E, R> success(S state, R value) {
        return new Success<>(state, value);
    }

    /**
     * Creates a result with no value (state change only).
     *
     * @param state The resulting state
     * @return A NoResult result
     */
    static <S, E, R> EffectResult<S, E, R> noResult(S state) {
        return new NoResult<>(state);
    }

    /**
     * Creates a failure result.
     *
     * @param state The state at the time of failure
     * @param error The error that occurred
     * @return A Failure result
     */
    static <S, E, R> EffectResult<S, E, R> failure(S state, E error) {
        return new Failure<>(state, error);
    }
    
    // ============================================================================
    // Query Methods
    // ============================================================================
    
    /**
     * Checks if this result represents a successful execution.
     * 
     * @return true if this is a Success result
     */
    default boolean isSuccess() {
        return this instanceof Success;
    }
    
    /**
     * Checks if this result has a value.
     * 
     * @return true if this is a Success result with a value
     */
    default boolean hasValue() {
        return this instanceof Success;
    }
    
    /**
     * Checks if this result represents a failure.
     * 
     * @return true if this is a Failure result
     */
    default boolean isFailure() {
        return this instanceof Failure;
    }
    
    /**
     * Gets the result value if present.
     * 
     * @return An Optional containing the value if this is a Success, empty otherwise
     */
    default Optional<Result> value() {
        return this instanceof Success<State, Result> success 
            ? Optional.of(success.resultValue()) 
            : Optional.empty();
    }
    
    /**
     * Gets the error if present.
     *
     * @return An Optional containing the error if this is a Failure, empty otherwise
     */
    default Optional<Error> error() {
        return this instanceof Failure<State, Error, Result> failure
            ? Optional.of(failure.errorValue())
            : Optional.empty();
    }
    
    // ============================================================================
    // Transformation Methods
    // ============================================================================
    
    /**
     * Maps the result value using the given function.
     * If this is not a Success, returns this unchanged.
     *
     * @param f The function to transform the value
     * @return A new EffectResult with the transformed value
     */
    default <R2> EffectResult<State, Error, R2> map(Function<Result, R2> f) {
        return switch (this) {
            case Success<State, Error, Result> success ->
                new Success<>(success.state(), f.apply(success.resultValue()));
            case NoResult<State, Error, Result> noResult ->
                new NoResult<>(noResult.state());
            case Failure<State, Error, Result> failure ->
                new Failure<>(failure.state(), failure.errorValue());
        };
    }
    
    /**
     * Maps the state using the given function.
     *
     * @param f The function to transform the state
     * @return A new EffectResult with the transformed state
     */
    default <S2> EffectResult<S2, Error, Result> mapState(Function<State, S2> f) {
        return switch (this) {
            case Success<State, Error, Result> success ->
                new Success<>(f.apply(success.state()), success.resultValue());
            case NoResult<State, Error, Result> noResult ->
                new NoResult<>(f.apply(noResult.state()));
            case Failure<State, Error, Result> failure ->
                new Failure<>(f.apply(failure.state()), failure.errorValue());
        };
    }
    
    /**
     * Recovers from a failure by transforming the error into a result.
     *
     * @param f The function to transform errors into results
     * @return A new EffectResult that cannot be a Failure
     */
    default EffectResult<State, Error, Result> recover(Function<Error, Result> f) {
        return switch (this) {
            case Failure<State, Error, Result> failure ->
                new Success<>(failure.state(), f.apply(failure.errorValue()));
            default -> this;
        };
    }
    
    // ============================================================================
    // Side Effect Methods
    // ============================================================================
    
    /**
     * Performs an action with the result value if present.
     *
     * @param action The action to perform
     * @return This result unchanged
     */
    default EffectResult<State, Error, Result> ifSuccess(Consumer<Result> action) {
        if (this instanceof Success<State, Error, Result> success) {
            action.accept(success.resultValue());
        }
        return this;
    }

    /**
     * Performs an action with the error if present.
     *
     * @param action The action to perform
     * @return This result unchanged
     */
    default EffectResult<State, Error, Result> ifFailure(Consumer<Error> action) {
        if (this instanceof Failure<State, Error, Result> failure) {
            action.accept(failure.errorValue());
        }
        return this;
    }

    /**
     * Performs an action with the state.
     *
     * @param action The action to perform
     * @return This result unchanged
     */
    default EffectResult<State, Error, Result> ifState(Consumer<State> action) {
        action.accept(this.state());
        return this;
    }
    
    // ============================================================================
    // Pattern Matching Support
    // ============================================================================
    
    /**
     * Folds this result into a single value by providing handlers for each case.
     *
     * @param onSuccess Handler for Success case
     * @param onNoResult Handler for NoResult case
     * @param onFailure Handler for Failure case
     * @return The result of applying the appropriate handler
     */
    default <T> T fold(
        Function<Result, T> onSuccess,
        Function<State, T> onNoResult,
        Function<Error, T> onFailure
    ) {
        return switch (this) {
            case Success<State, Error, Result> success -> onSuccess.apply(success.resultValue());
            case NoResult<State, Error, Result> noResult -> onNoResult.apply(noResult.state());
            case Failure<State, Error, Result> failure -> onFailure.apply(failure.errorValue());
        };
    }
    
    /**
     * Gets the value or throws if this is a Failure or NoResult.
     * For Failure, throws the error value (if it's a Throwable), otherwise wraps it.
     *
     * @return The result value
     * @throws Throwable if this is a Failure with a Throwable error
     * @throws IllegalStateException if this is NoResult or Failure with non-Throwable error
     */
    default Result getOrThrow() throws Throwable {
        return switch (this) {
            case Success<State, Error, Result> success -> success.resultValue();
            case NoResult<State, Error, Result> noResult ->
                throw new IllegalStateException("No result value present");
            case Failure<State, Error, Result> failure -> {
                Error error = failure.errorValue();
                if (error instanceof Throwable t) {
                    throw t;
                } else {
                    throw new IllegalStateException("Effect failed with error: " + error);
                }
            }
        };
    }
    
    /**
     * Gets the value or returns a default value.
     * 
     * @param defaultValue The default value to return if no value is present
     * @return The result value or the default
     */
    default Result getOrElse(Result defaultValue) {
        return switch (this) {
            case Success<State, Error, Result> success -> success.resultValue();
            default -> defaultValue;
        };
    }

    /**
     * Converts this result to a String representation.
     *
     * @return A string describing this result
     */
    default String toDebugString() {
        return switch (this) {
            case Success<State, Error, Result> success ->
                String.format("Success(state=%s, value=%s)", success.state(), success.resultValue());
            case NoResult<State, Error, Result> noResult ->
                String.format("NoResult(state=%s)", noResult.state());
            case Failure<State, Error, Result> failure ->
                String.format("Failure(state=%s, error=%s)", failure.state(), failure.errorValue());
        };
    }
}
