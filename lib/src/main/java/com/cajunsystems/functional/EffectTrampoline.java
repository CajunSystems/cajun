package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Function;

/**
 * Internal trampolining mechanism for stack-safe effect evaluation.
 *
 * <p>This prevents stack overflow when chaining many map/flatMap operations
 * by building up a data structure and evaluating it iteratively instead of recursively.
 *
 * <p>Example: {@code effect.map(f1).map(f2)...map(f10000)} will not overflow.
 *
 * @param <State> The actor state type
 * @param <Message> The message type
 * @param <Result> The result type
 */
sealed interface EffectTrampoline<State, Error, Result> {

    /**
     * A pure effect that executes a base computation.
     */
    record Pure<State, Error, Result>(
        Effect<State, Error, Result> effect
    ) implements EffectTrampoline<State, Error, Result> {}

    /**
     * A map transformation applied to an effect.
     *
     * @param <R1> The source result type before mapping
     */
    record Map<State, Error, R1, Result>(
        EffectTrampoline<State, Error, R1> source,
        Function<R1, Result> mapper
    ) implements EffectTrampoline<State, Error, Result> {}

    /**
     * A flatMap transformation applied to an effect.
     *
     * @param <R1> The source result type before flat mapping
     */
    record FlatMap<State, Error, R1, Result>(
        EffectTrampoline<State, Error, R1> source,
        Function<R1, Effect<State, Error, Result>> binder
    ) implements EffectTrampoline<State, Error, Result> {}

    /**
     * Evaluates a trampolined effect iteratively without recursion.
     *
     * <p>This method uses an explicit stack to track transformations,
     * preventing stack overflow for deeply nested map/flatMap chains.
     *
     * @param trampoline The trampolined effect to evaluate
     * @param state The initial state
     * @param message The message being processed
     * @param context The actor context
     * @return The final effect result
     */
    static <S, E, R> EffectResult<S, E, R> evaluate(
        EffectTrampoline<S, E, R> trampoline,
        S state,
        M message,
        ActorContext context
    ) {
        // Stack to track transformations (map/flatMap functions)
        Deque<Function<Object, Object>> transformStack = new ArrayDeque<>();

        // Current trampoline being processed
        EffectTrampoline<S, E, ?> current = trampoline;

        // Step 1: Unwind the trampoline structure to find the base Pure effect
        // and collect all transformations onto the stack
        while (true) {
            switch (current) {
                case Pure<S, E, ?> pure -> {
                    // Found the base effect - execute it
                    EffectResult<S, E, ?> result = pure.effect().run(state, message, context);

                    // Step 2: Apply all collected transformations in FIFO order
                    return applyTransformations(result, transformStack, state, message, context);
                }

                case Map<S, E, ?, ?> map -> {
                    // Push the mapper onto the stack
                    @SuppressWarnings("unchecked")
                    Function<Object, Object> mapper = (Function<Object, Object>) map.mapper();
                    transformStack.push(mapper);

                    // Continue unwinding with the source effect
                    current = map.source();
                }

                case FlatMap<S, E, ?, ?> flatMap -> {
                    // Push a marker function that indicates flatMap
                    @SuppressWarnings("unchecked")
                    Function<Object, Effect<S, E, Object>> binder =
                        (Function<Object, Effect<S, E, Object>>) flatMap.binder();

                    transformStack.push(new FlatMapMarker<>(binder, message, context));

                    // Continue unwinding with the source effect
                    current = flatMap.source();
                }
            }
        }
    }

    /**
     * Applies accumulated transformations from the stack to the base result.
     *
     * @param baseResult The result from the base Pure effect
     * @param transformStack Stack of transformations to apply
     * @param state The actor state
     * @param message The message being processed
     * @param context The actor context
     * @return The final transformed result
     */
    @SuppressWarnings("unchecked")
    private static <S, E, R> EffectResult<S, E, R> applyTransformations(
        EffectResult<S, E, ?> baseResult,
        Deque<Function<Object, Object>> transformStack,
        S state,
        M message,
        ActorContext context
    ) {
        EffectResult<S, E, ?> current = baseResult;

        // Apply transformations in reverse order (LIFO from stack)
        while (!transformStack.isEmpty()) {
            Function<Object, Object> transform = transformStack.pop();

            // Check if this is a flatMap marker
            if (transform instanceof FlatMapMarker<S, E, ?, ?> marker) {
                current = applyFlatMap(current, (FlatMapMarker<S, E, Object, Object>) marker, state);
            } else {
                // Regular map transformation
                current = applyMap(current, transform);
            }
        }

        return (EffectResult<S, E, R>) current;
    }

    /**
     * Applies a map transformation to a result.
     */
    private static <S, R1, R2> EffectResult<S, E, R2> applyMap(
        EffectResult<S, E, R1> result,
        Function<Object, Object> mapper
    ) {
        return switch (result) {
            case EffectResult.Success<S, R1> success -> {
                @SuppressWarnings("unchecked")
                Function<R1, R2> typedMapper = (Function<R1, R2>) mapper;
                R2 mapped = typedMapper.apply(success.resultValue());
                yield EffectResult.success(success.state(), mapped);
            }
            case EffectResult.NoResult<S, R1> noResult ->
                new EffectResult.NoResult<>(noResult.state());
            case EffectResult.Failure<S, R1> failure ->
                new EffectResult.Failure<>(failure.state(), failure.errorValue());
        };
    }

    /**
     * Applies a flatMap transformation to a result.
     */
    private static <S, E, R1, R2> EffectResult<S, E, R2> applyFlatMap(
        EffectResult<S, E, R1> result,
        FlatMapMarker<S, E, R1, R2> marker,
        S state
    ) {
        return switch (result) {
            case EffectResult.Success<S, R1> success -> {
                Effect<S, E, R2> nextEffect = marker.binder().apply(success.resultValue());
                yield nextEffect.run(success.state(), marker.message(), marker.context());
            }
            case EffectResult.NoResult<S, R1> noResult ->
                new EffectResult.NoResult<>(noResult.state());
            case EffectResult.Failure<S, R1> failure ->
                new EffectResult.Failure<>(failure.state(), failure.errorValue());
        };
    }

    /**
     * Marker class to distinguish flatMap from map in the transform stack.
     */
    private record FlatMapMarker<S, E, R1, R2>(
        Function<R1, Effect<S, E, R2>> binder,
        M message,
        ActorContext context
    ) implements Function<Object, Object> {

        @Override
        public Object apply(Object o) {
            throw new UnsupportedOperationException("FlatMapMarker should not be called as a function");
        }
    }
}
