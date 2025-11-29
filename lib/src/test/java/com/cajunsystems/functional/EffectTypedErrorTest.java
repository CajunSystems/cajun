package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify the new Effect<State, Error, Result> signature works correctly.
 *
 * <p>This test demonstrates the typed error channel and confirms that the Message
 * type parameter has been successfully moved to the EffectMatcher.
 */
class EffectTypedErrorTest {

    // Simple error type for testing
    sealed interface AppError {
        record ValidationError(String message) implements AppError {}
        record DatabaseError(String message) implements AppError {}
    }

    // Message types for pattern matching
    sealed interface CounterMsg {
        record Increment(int amount) implements CounterMsg {}
        record Decrement(int amount) implements CounterMsg {}
    }

    @Test
    void testBasicEffectWithTypedError() {
        // Effect with Throwable error type (most common)
        Effect<Integer, Throwable, String> effect = Effect.of("success");

        EffectResult<Integer, Throwable, String> result = effect.run(0, null, null);

        assertTrue(result instanceof EffectResult.Success);
        assertEquals("success", ((EffectResult.Success<Integer, Throwable, String>) result).resultValue());
    }

    @Test
    void testEffectWithCustomErrorType() {
        // Effect with custom error type
        Effect<Integer, AppError, Integer> effect = Effect.of(42);

        EffectResult<Integer, AppError, Integer> result = effect.run(0, null, null);

        assertTrue(result instanceof EffectResult.Success);
        assertEquals(42, ((EffectResult.Success<Integer, AppError, Integer>) result).resultValue());
    }

    @Test
    void testFailureWithTypedError() {
        AppError error = new AppError.ValidationError("Invalid input");
        Effect<Integer, AppError, Integer> effect = Effect.fail(error);

        EffectResult<Integer, AppError, Integer> result = effect.run(0, null, null);

        assertTrue(result instanceof EffectResult.Failure);
        AppError resultError = ((EffectResult.Failure<Integer, AppError, Integer>) result).errorValue();
        assertEquals(error, resultError);
        assertTrue(resultError instanceof AppError.ValidationError);
    }

    @Test
    void testMapWithTypedError() {
        Effect<Integer, Throwable, Integer> effect = Effect.of(10);
        Effect<Integer, Throwable, String> mapped = effect.map(n -> "Number: " + n);

        EffectResult<Integer, Throwable, String> result = mapped.run(0, null, null);

        assertTrue(result instanceof EffectResult.Success);
        assertEquals("Number: 10", ((EffectResult.Success<Integer, Throwable, String>) result).resultValue());
    }

    @Test
    void testFlatMapWithTypedError() {
        Effect<Integer, Throwable, Integer> effect = Effect.of(10);
        Effect<Integer, Throwable, Integer> flatMapped = effect.flatMap(n -> Effect.of(n * 2));

        EffectResult<Integer, Throwable, Integer> result = flatMapped.run(0, null, null);

        assertTrue(result instanceof EffectResult.Success);
        assertEquals(20, ((EffectResult.Success<Integer, Throwable, Integer>) result).resultValue());
    }

    @Test
    void testRecoverWithTypedError() {
        AppError error = new AppError.DatabaseError("Connection failed");
        Effect<Integer, AppError, Integer> effect = Effect.fail(error);
        Effect<Integer, AppError, Integer> recovered = effect.recover(err ->
            err instanceof AppError.DatabaseError ? -1 : -2
        );

        EffectResult<Integer, AppError, Integer> result = recovered.run(0, null, null);

        assertTrue(result instanceof EffectResult.Success);
        assertEquals(-1, ((EffectResult.Success<Integer, AppError, Integer>) result).resultValue());
    }

    @Test
    void testDeepChainWithTypedError() {
        // Build a deep chain to verify trampolining works with new signatures
        Effect<Integer, Throwable, Integer> effect = Effect.of(0);

        for (int i = 0; i < 1000; i++) {
            effect = effect.map(n -> n + 1);
        }

        EffectResult<Integer, Throwable, Integer> result = effect.run(42, null, null);

        assertTrue(result instanceof EffectResult.Success);
        assertEquals(1000, ((EffectResult.Success<Integer, Throwable, Integer>) result).resultValue());
    }

    @Test
    void testMessageParameterInMatcher() {
        // Demonstrate that Message type is now local to the matcher
        Effect<Integer, Throwable, Void> counterEffect = Effect.<Integer, Throwable, Void, CounterMsg>match()
            .when(CounterMsg.Increment.class, (state, msg, ctx) ->
                Effect.modify((Integer s) -> s + msg.amount())
            )
            .when(CounterMsg.Decrement.class, (state, msg, ctx) ->
                Effect.modify((Integer s) -> s - msg.amount())
            )
            .build();

        // Test increment
        EffectResult<Integer, Throwable, Void> result1 = counterEffect.run(
            10,
            new CounterMsg.Increment(5),
            null
        );
        assertEquals(15, result1.state());

        // Test decrement
        EffectResult<Integer, Throwable, Void> result2 = counterEffect.run(
            10,
            new CounterMsg.Decrement(3),
            null
        );
        assertEquals(7, result2.state());
    }

    @Test
    void testUnmatchedMessageFails() {
        Effect<Integer, Throwable, Void> counterEffect = Effect.<Integer, Throwable, Void, CounterMsg>match()
            .when(CounterMsg.Increment.class, (state, msg, ctx) ->
                Effect.modify((Integer s) -> s + msg.amount())
            )
            .build();

        // Send a message that doesn't match
        EffectResult<Integer, Throwable, Void> result = counterEffect.run(
            10,
            "not a counter message",
            null
        );

        assertTrue(result instanceof EffectResult.Failure);
        Throwable error = ((EffectResult.Failure<Integer, Throwable, Void>) result).errorValue();
        assertTrue(error instanceof IllegalArgumentException);
        assertTrue(error.getMessage().contains("No matching case"));
    }
}
