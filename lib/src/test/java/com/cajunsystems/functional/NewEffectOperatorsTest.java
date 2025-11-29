package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for new Effect operators: identity, filterOrElse, error channel, and parallel execution.
 */
class NewEffectOperatorsTest {

    @Mock
    private ActorContext context;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // ============================================================================
    // Factory Method Tests
    // ============================================================================
    
    @Test
    void testOf_createsEffectWithValue() {
        Effect<Integer, Throwable, String> effect = Effect.of("success");
        
        EffectResult<Integer, String> result = effect.run(42, "test", context);
        
        assertTrue(result.isSuccess());
        assertEquals(42, result.state());
        assertEquals("success", result.value().orElseThrow());
    }
    
    @Test
    void testState_returnsCurrentState() {
        Effect<Integer, Throwable, Integer> effect = Effect.state();
        
        EffectResult<Integer, Integer> result = effect.run(42, "test", context);
        
        assertTrue(result.isSuccess());
        assertEquals(42, result.state());
        assertEquals(42, result.value().orElseThrow());
    }
    
    @Test
    void testModify_changesState() {
        Effect<Integer, Throwable, Void> effect = Effect.modify(s -> s + 10);
        
        EffectResult<Integer, Void> result = effect.run(42, "test", context);
        
        assertInstanceOf(EffectResult.NoResult.class, result);
        assertEquals(52, result.state());
        assertFalse(result.value().isPresent());
    }
    
    @Test
    void testSetState_setsSpecificState() {
        Effect<Integer, Throwable, Void> effect = Effect.setState(100);
        
        EffectResult<Integer, Void> result = effect.run(42, "test", context);
        
        assertInstanceOf(EffectResult.NoResult.class, result);
        assertEquals(100, result.state());
    }
    
    @Test
    void testFail_createsFailingEffect() {
        IllegalStateException error = new IllegalStateException("test error");
        Effect<Integer, Throwable, String> effect = Effect.fail(error);
        
        EffectResult<Integer, String> result = effect.run(42, "test", context);
        
        assertTrue(result.isFailure());
        assertEquals(42, result.state());
        assertEquals(error, result.error().orElseThrow());
    }
    
    @Test
    void testNone_doesNothing() {
        Effect<Integer, Throwable, Void> effect = Effect.none();
        
        EffectResult<Integer, Void> result = effect.run(42, "test", context);
        
        assertInstanceOf(EffectResult.NoResult.class, result);
        assertEquals(42, result.state());
        assertFalse(result.value().isPresent());
    }

    // ============================================================================
    // Effect.identity() Tests
    // ============================================================================

    @Test
    void testIdentity_keepsStateUnchanged() {
        Effect<Integer, Throwable, Void> effect = Effect.identity();
        
        EffectResult<Integer, Void> result = effect.run(42, "test", context);
        
        assertEquals(42, result.state());
        assertFalse(result.value().isPresent());
    }

    @Test
    void testIdentity_canBeChained() {
        Effect<Integer, Throwable, Void> effect = Effect.identity();
        Effect<Integer, Throwable, Void> chained = effect.andThen(Effect.modify(s -> s + 10));
        
        EffectResult<Integer, Void> result = chained.run(5, "test", context);
        
        assertEquals(15, result.state());
    }

    // ============================================================================
    // filterOrElse() Tests
    // ============================================================================

    @Test
    void testFilterOrElse_passesWhenPredicateTrue() {
        Effect<Integer, Throwable, Integer> effect = Effect.<Integer, Throwable, Integer>of(10);
        Effect<Integer, Throwable, Integer> fallback = Effect.<Integer, Throwable, Integer>of(-1);
        
        Effect<Integer, Throwable, Integer> validated = effect.filterOrElse(
            state -> state > 0,
            fallback
        );
        
        EffectResult<Integer, Integer> result = validated.run(5, "test", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(10, result.value().get());
    }

    @Test
    void testFilterOrElse_executesFallbackWhenPredicateFalse() {
        // Create effect that fails validation
        Effect<Integer, Throwable, Integer> effect = (state, msg, ctx) -> 
            Trampoline.done(EffectResult.success(state - 10, state - 10));
        
        Effect<Integer, Throwable, Integer> fallback = Effect.<Integer, Throwable, Integer>of(-1);
        
        Effect<Integer, Throwable, Integer> validated = effect.filterOrElse(
            state -> state >= 0,
            fallback
        );
        
        EffectResult<Integer, Integer> result = validated.run(5, "test", context);
        
        // Should execute fallback since 5 - 10 = -5 (< 0)
        assertTrue(result.value().isPresent());
        assertEquals(-1, result.value().get());
    }

    // ============================================================================
    // Error Channel Tests
    // ============================================================================

    @Test
    void testAttempt_catchesExceptions() {
        Effect<Integer, Throwable, Integer> effect = (state, msg, ctx) -> {
            if (state < 0) {
                throw new IllegalArgumentException("Negative value");
            }
            return Trampoline.done(EffectResult.success(state * 2, state * 2));
        };
        
        Effect<Integer, Throwable, Integer> safe = effect.attempt();
        
        EffectResult<Integer, Integer> result = safe.run(-5, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertEquals(-5, result.state());
    }

    @Test
    void testHandleErrorWith_recoversFromError() {
        Effect<Integer, Throwable, Integer> effect = (state, msg, ctx) -> {
            throw new RuntimeException("Error");
        };
        
        Effect<Integer, Throwable, Integer> recovered = effect
            .attempt()
            .handleErrorWith((err, s, m, c) -> {
                Effect<Integer, Throwable, Integer> recovery = (st, ms, ct) -> Trampoline.done(EffectResult.success(s, 999));
                return recovery;
            });
        
        EffectResult<Integer, Integer> result = recovered.run(10, "test", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(999, result.value().get());
    }

    @Test
    void testHandleError_recoversState() {
        Effect<Integer, Throwable, Void> effect = (state, msg, ctx) -> 
            Trampoline.done(EffectResult.failure(state, new RuntimeException("test error")));
        
        Effect<Integer, Throwable, Void> recovered = effect
            .attempt()
            .handleError((err, s, m, c) -> s + 100);
        
        EffectResult<Integer, Void> result = recovered.run(10, "test", context);
        
        assertEquals(110, result.state());
    }

    @Test
    void testTapError_performsSideEffectOnError() {
        final boolean[] errorLogged = {false};
        
        Effect<Integer, Throwable, Integer> effect = (state, msg, ctx) -> 
            Trampoline.done(EffectResult.failure(state, new RuntimeException("test error")));
        
        Effect<Integer, Throwable, Integer> withTap = effect
            .attempt()
            .tapError(err -> errorLogged[0] = true)
            .handleErrorWith((err, s, m, c) -> {
                Effect<Integer, Throwable, Integer> recovery = (st, ms, ct) -> Trampoline.done(EffectResult.success(s, 0));
                return recovery;
            });
        
        withTap.run(10, "test", context);
        
        assertTrue(errorLogged[0]);
    }

    // ============================================================================
    // Parallel Execution Tests
    // ============================================================================

    @Test
    void testParZip_combinesTwoEffects() {
        Effect<Integer, Throwable, Integer> effect1 = Effect.<Integer, Throwable, Integer>of(10);
        Effect<Integer, Throwable, Integer> effect2 = Effect.<Integer, Throwable, Integer>of(20);
        
        Effect<Integer, Throwable, Integer> combined = effect1.parZip(effect2, 
            (a, b) -> a + b
        );
        
        EffectResult<Integer, Integer> result = combined.run(0, "test", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(30, result.value().get());
    }

    @Test
    void testParZip_failsIfEitherFails() {
        Effect<Integer, Throwable, Integer> effect1 = Effect.<Integer, Throwable, Integer>of(10);
        Effect<Integer, Throwable, Integer> effect2 = Effect.<Integer, Throwable, Integer>fail(new RuntimeException("Error"));
        
        Effect<Integer, Throwable, Integer> combined = effect1.parZip(effect2, 
            (a, b) -> a + b
        );
        
        EffectResult<Integer, Integer> result = combined.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
    }

    @Test
    void testSequence_threadsStateThroughEffects() {
        List<Effect<Integer, Throwable, Void>> effects = List.of(
            Effect.<Integer, Throwable>modify(s -> s + 10),  // 5 + 10 = 15
            Effect.<Integer, Throwable>modify(s -> s * 2),   // 15 * 2 = 30
            Effect.<Integer, Throwable>modify(s -> s - 5)    // 30 - 5 = 25
        );
        
        Effect<Integer, Throwable, List<Void>> pipeline = Effect.sequence(effects);
        
        EffectResult<Integer, List<Void>> result = pipeline.run(5, "test", context);
        
        assertEquals(25, result.state());  // Final state after all transformations
    }

    @Test
    @org.junit.jupiter.api.Disabled("TODO: Fix sequence failure propagation")
    void testSequence_failsOnFirstError() {
        List<Effect<Integer, Throwable, Integer>> effects = List.of(
            Effect.<Integer, Throwable, Integer>of(1),
            (state, msg, ctx) -> 
                Trampoline.done(EffectResult.failure(state, new RuntimeException("Error in step 2"))),
            Effect.<Integer, Throwable, Integer>of(3)  // Should not execute
        );
        
        Effect<Integer, Throwable, List<Integer>> pipeline = Effect.sequence(effects);
        
        EffectResult<Integer, List<Integer>> result = pipeline.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
    }

    @Test
    void testParSequence_collectsAllResults() {
        List<Effect<Integer, Throwable, Integer>> effects = List.of(
            Effect.<Integer, Throwable, Integer>of(1),
            Effect.<Integer, Throwable, Integer>of(2),
            Effect.<Integer, Throwable, Integer>of(3)
        );
        
        Effect<Integer, Throwable, List<Integer>> combined = Effect.parSequence(effects);
        
        EffectResult<Integer, List<Integer>> result = combined.run(0, "test", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(List.of(1, 2, 3), result.value().get());
    }

    @Test
    void testParSequence_failsIfAnyFails() {
        List<Effect<Integer, Throwable, Integer>> effects = List.of(
            Effect.<Integer, Throwable, Integer>of(1),
            Effect.<Integer, Throwable, Integer>fail(new RuntimeException("Error")),
            Effect.<Integer, Throwable, Integer>of(3)
        );
        
        Effect<Integer, Throwable, List<Integer>> combined = Effect.parSequence(effects);
        
        EffectResult<Integer, List<Integer>> result = combined.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
    }

    @Test
    void testRace_returnsFirstToComplete() {
        // Create effects with different delays
        Effect<Integer, Throwable, String> fast = (state, msg, ctx) -> {
            return Trampoline.done(EffectResult.success(state, "fast"));
        };
        
        Effect<Integer, Throwable, String> slow = (state, msg, ctx) -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Trampoline.done(EffectResult.success(state, "slow"));
        };
        
        Effect<Integer, Throwable, String> effect1 = (state, msg, ctx) -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Trampoline.done(EffectResult.success(state, "first"));
        };
        
        Effect<Integer, Throwable, String> raced = fast.race(slow);
        
        EffectResult<Integer, String> result = raced.run(0, "test", context);
        
        assertTrue(result.value().isPresent());
        assertEquals("fast", result.value().get());
    }

    @Test
    void testWithTimeout_completesWithinTimeout() {
        Effect<Integer, Throwable, Integer> effect = Effect.<Integer, Throwable, Integer>of(42);
        Effect<Integer, Throwable, Integer> timed = effect.withTimeout(Duration.ofSeconds(1));
        
        EffectResult<Integer, Integer> result = timed.run(0, "test", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(42, result.value().get());
    }

    @Test
    void testWithTimeout_failsOnTimeout() {
        Effect<Integer, Throwable, Integer> slowEffect = (state, msg, ctx) -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Trampoline.done(EffectResult.success(state, 42));
        };
        
        Effect<Integer, Throwable, Integer> timedEffect = slowEffect.withTimeout(Duration.ofMillis(50));
        
        EffectResult<Integer, Integer> result = timedEffect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        EffectResult.Failure<Integer, Integer> failure = (EffectResult.Failure<Integer, Integer>) result;
        assertInstanceOf(TimeoutException.class, failure.errorValue());
    }

    // ============================================================================
    // Integration Tests - Combining Multiple Operators
    // ============================================================================

    @Test
    void testCombinedOperators_parallelWithErrorHandling() {
        Effect<Integer, Throwable, Integer> effect1 = Effect.<Integer, Throwable, Integer>of(10).attempt();
        Effect<Integer, Throwable, Integer> effect2 = Effect.<Integer, Throwable, Integer>of(20).attempt();
        
        Effect<Integer, Throwable, Integer> combined = effect1.parZip(effect2, 
            (a, b) -> a + b
        )
        .handleErrorWith((err, s, m, c) -> {
            Effect<Integer, Throwable, Integer> recovery = (st, ms, ct) -> Trampoline.done(EffectResult.success(s, 0));
            return recovery;
        });
        
        EffectResult<Integer, Integer> result = combined.run(0, "test", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(30, result.value().get());
    }

    @Test
    void testCombinedOperators_timeoutWithFallback() {
        Effect<Integer, Throwable, Integer> slowEffect = (state, msg, ctx) -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Trampoline.done(EffectResult.success(state, 42));
        };
        
        Effect<Integer, Throwable, Integer> robust = slowEffect
            .withTimeout(Duration.ofMillis(50))
            .handleErrorWith((err, s, m, c) -> {
                Effect<Integer, Throwable, Integer> recovery = (st, ms, ct) -> Trampoline.done(EffectResult.success(st, 99));
                return recovery;
            });
        
        EffectResult<Integer, Integer> result = robust.run(0, "test", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(99, result.value().get());
    }

    @Test
    void testCombinedOperators_parallelSequenceWithTimeout() {
        List<Effect<Integer, Throwable, Integer>> effects = List.of(
            Effect.<Integer, Throwable, Integer>of(1).withTimeout(Duration.ofSeconds(1)),
            Effect.<Integer, Throwable, Integer>of(2).withTimeout(Duration.ofSeconds(1)),
            Effect.<Integer, Throwable, Integer>of(3).withTimeout(Duration.ofSeconds(1))
        );
        
        Effect<Integer, Throwable, List<Integer>> combined = Effect.parSequence(effects)
            .handleErrorWith((err, s, m, c) -> {
                Effect<Integer, Throwable, List<Integer>> recovery = (st, ms, ct) -> Trampoline.done(EffectResult.success(st, List.of()));
                return recovery;
            });
        
        EffectResult<Integer, List<Integer>> result = combined.run(0, "test", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(List.of(1, 2, 3), result.value().get());
    }

    @Test
    void testIdentityWithModify_preservesStateCorrectly() {
        Effect<Integer, Throwable, Void> effect = Effect.<Integer, Throwable>modify(s -> s + 10)
            .andThen(Effect.identity())
            .andThen(Effect.<Integer, Throwable>modify(s -> s * 2));
        
        EffectResult<Integer, Void> result = effect.run(5, "test", context);
        
        assertEquals(30, result.state());  // (5 + 10) * 2
    }

    // ============================================================================
    // Filter Tests (Typed Error Factory)
    // ============================================================================

    @Test
    void testFilter_passesWhenPredicateTrue() {
        Effect<Integer, IllegalArgumentException, Integer> effect = 
            Effect.<Integer, IllegalArgumentException, Integer>of(42)
                .filter(v -> v > 0, 
                        v -> new IllegalArgumentException("Value must be positive, got: " + v));
        
        EffectResult<Integer, Integer> result = effect.run(0, "test", context);
        
        assertTrue(result.isSuccess());
        assertEquals(42, result.value().orElseThrow());
    }

    @Test
    void testFilter_failsWhenPredicateFalse() {
        Effect<Integer, IllegalArgumentException, Integer> effect = 
            Effect.<Integer, IllegalArgumentException, Integer>of(-5)
                .filter(v -> v > 0, 
                        v -> new IllegalArgumentException("Value must be positive, got: " + v));
        
        EffectResult<Integer, Integer> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertTrue(result.error().isPresent());
        assertInstanceOf(IllegalArgumentException.class, result.error().get());
        assertEquals("Value must be positive, got: -5", result.error().get().getMessage());
    }

    @Test
    void testFilter_withCustomException() {
        // Custom validation exception
        class ValidationError extends Exception {
            ValidationError(String field, Object value, String reason) {
                super(String.format("%s: %s (got: %s)", field, reason, value));
            }
        }
        
        Effect<Integer, ValidationError, Integer> effect = 
            Effect.<Integer, ValidationError, Integer>of(150)
                .filter(v -> v <= 100, 
                        v -> new ValidationError("age", v, "must be <= 100"));
        
        EffectResult<Integer, Integer> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertTrue(result.error().isPresent());
        assertInstanceOf(ValidationError.class, result.error().get());
        assertEquals("age: must be <= 100 (got: 150)", result.error().get().getMessage());
    }

    @Test
    void testFilter_chainedFilters() {
        Effect<Integer, IllegalArgumentException, Integer> effect = 
            Effect.<Integer, IllegalArgumentException, Integer>of(50)
                .filter(v -> v > 0, 
                        v -> new IllegalArgumentException("Must be positive: " + v))
                .filter(v -> v < 100, 
                        v -> new IllegalArgumentException("Must be < 100: " + v))
                .filter(v -> v % 2 == 0, 
                        v -> new IllegalArgumentException("Must be even: " + v));
        
        EffectResult<Integer, Integer> result = effect.run(0, "test", context);
        
        assertTrue(result.isSuccess());
        assertEquals(50, result.value().orElseThrow());
    }

    @Test
    void testFilter_chainedFilters_failsOnSecond() {
        Effect<Integer, IllegalArgumentException, Integer> effect = 
            Effect.<Integer, IllegalArgumentException, Integer>of(150)
                .filter(v -> v > 0, 
                        v -> new IllegalArgumentException("Must be positive: " + v))
                .filter(v -> v < 100, 
                        v -> new IllegalArgumentException("Must be < 100: " + v));
        
        EffectResult<Integer, Integer> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertEquals("Must be < 100: 150", result.error().get().getMessage());
    }

    @Test
    void testFilter_withRecover() {
        Effect<Integer, IllegalArgumentException, Integer> effect = 
            Effect.<Integer, IllegalArgumentException, Integer>of(-5)
                .filter(v -> v > 0, 
                        v -> new IllegalArgumentException("Value must be positive, got: " + v))
                .recover(error -> {
                    // Recover with default value
                    return 0;
                });
        
        EffectResult<Integer, Integer> result = effect.run(0, "test", context);
        
        assertTrue(result.isSuccess());
        assertEquals(0, result.value().orElseThrow());
    }

    @Test
    void testFilter_withHandleErrorWith() {
        Effect<Integer, IllegalArgumentException, Integer> effect = 
            Effect.<Integer, IllegalArgumentException, Integer>of(-5)
                .filter(v -> v > 0, 
                        v -> new IllegalArgumentException("Value must be positive, got: " + v))
                .handleErrorWith((error, state, msg, ctx) -> 
                    Effect.of(Math.abs((Integer) msg))  // Use absolute value
                );
        
        EffectResult<Integer, Integer> result = effect.run(0, -5, context);
        
        assertTrue(result.isSuccess());
        assertEquals(5, result.value().orElseThrow());
    }

    @Test
    void testFilter_preservesState() {
        Effect<Integer, IllegalArgumentException, Integer> effect = 
            Effect.<Integer, IllegalArgumentException>modify(s -> s + 10)
                .andThen(Effect.of(42))
                .filter(v -> v > 0, 
                        v -> new IllegalArgumentException("Invalid: " + v));
        
        EffectResult<Integer, Integer> result = effect.run(5, "test", context);
        
        assertTrue(result.isSuccess());
        assertEquals(15, result.state());  // State was modified
        assertEquals(42, result.value().orElseThrow());
    }

    @Test
    void testFilter_withMap() {
        Effect<Integer, IllegalArgumentException, String> effect = 
            Effect.<Integer, IllegalArgumentException, Integer>of(42)
                .filter(v -> v > 0, 
                        v -> new IllegalArgumentException("Must be positive: " + v))
                .map(v -> "Value: " + v);
        
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertTrue(result.isSuccess());
        assertEquals("Value: 42", result.value().orElseThrow());
    }

    @Test
    void testFilter_withFlatMap() {
        Effect<Integer, IllegalArgumentException, String> effect = 
            Effect.<Integer, IllegalArgumentException, Integer>of(10)
                .filter(v -> v > 0, 
                        v -> new IllegalArgumentException("Must be positive: " + v))
                .flatMap(v -> Effect.of("Doubled: " + (v * 2)));
        
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertTrue(result.isSuccess());
        assertEquals("Doubled: 20", result.value().orElseThrow());
    }

    @Test
    void testFilter_doesNotAffectNoResult() {
        Effect<Integer, IllegalArgumentException, Void> effect = 
            Effect.<Integer, IllegalArgumentException>modify(s -> s + 10)
                .filter(v -> true,  // This won't be called for NoResult
                        v -> new IllegalArgumentException("Should not happen"));
        
        EffectResult<Integer, Void> result = effect.run(5, "test", context);
        
        assertInstanceOf(EffectResult.NoResult.class, result);
        assertEquals(15, result.state());
    }

    @Test
    void testFilter_doesNotAffectFailure() {
        Effect<Integer, IllegalArgumentException, Integer> effect = 
            Effect.<Integer, IllegalArgumentException, Integer>fail(
                new IllegalArgumentException("Original error"))
                .filter(v -> true,  // This won't be called for Failure
                        v -> new IllegalArgumentException("Should not happen"));
        
        EffectResult<Integer, Integer> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertEquals("Original error", result.error().get().getMessage());
    }

    // ============================================================================
    // Effect.attempt (Static Factory) Tests
    // ============================================================================

    @Test
    void testAttemptStaticFactory_success() throws Exception {
        Effect<Integer, Throwable, String> effect = 
            Effect.attempt(() -> "success");
        
        EffectResult<Integer, String> result = effect.run(42, "test", context);
        
        assertTrue(result.isSuccess());
        assertEquals("success", result.value().orElseThrow());
        assertEquals(42, result.state());
    }

    @Test
    void testAttemptStaticFactory_handlesCheckedException() {
        Effect<Integer, Throwable, String> effect = 
            Effect.attempt(() -> {
                if (true) throw new java.io.IOException("File not found");
                return "never";
            });
        
        EffectResult<Integer, String> result = effect.run(42, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertInstanceOf(java.io.IOException.class, result.error().get());
        assertEquals("File not found", result.error().get().getMessage());
    }

    @Test
    void testAttemptStaticFactory_handlesRuntimeException() {
        Effect<Integer, Throwable, Integer> effect = 
            Effect.attempt(() -> {
                throw new IllegalStateException("Bad state");
            });
        
        EffectResult<Integer, Integer> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertInstanceOf(IllegalStateException.class, result.error().get());
    }

    // ============================================================================
    // Effect.pure Tests
    // ============================================================================

    @Test
    void testPure_isAliasForOf() {
        Effect<Integer, Throwable, String> effect1 = Effect.of("test");
        Effect<Integer, Throwable, String> effect2 = Effect.pure("test");
        
        EffectResult<Integer, String> result1 = effect1.run(42, "msg", context);
        EffectResult<Integer, String> result2 = effect2.run(42, "msg", context);
        
        assertEquals(result1.value(), result2.value());
        assertEquals(result1.state(), result2.state());
    }

    // ============================================================================
    // Effect.when (Conditional Execution) Tests
    // ============================================================================

    @Test
    void testWhen_executesEffectWhenPredicateTrue() {
        Effect<Integer, Throwable, String> effect = 
            Effect.when(
                msg -> msg.equals("execute"),
                Effect.of("executed"),
                Effect.of("fallback")
            );
        
        EffectResult<Integer, String> result = effect.run(0, "execute", context);
        
        assertTrue(result.isSuccess());
        assertEquals("executed", result.value().orElseThrow());
    }

    @Test
    void testWhen_executesFallbackWhenPredicateFalse() {
        Effect<Integer, Throwable, String> effect = 
            Effect.when(
                msg -> msg.equals("execute"),
                Effect.of("executed"),
                Effect.of("fallback")
            );
        
        EffectResult<Integer, String> result = effect.run(0, "other", context);
        
        assertTrue(result.isSuccess());
        assertEquals("fallback", result.value().orElseThrow());
    }

    @Test
    void testWhen_withStateModification() {
        Effect<Integer, Throwable, Void> effect = 
            Effect.when(
                msg -> ((String) msg).startsWith("inc"),
                Effect.modify(s -> s + 10),
                Effect.modify(s -> s - 5)
            );
        
        EffectResult<Integer, Void> result1 = effect.run(100, "increment", context);
        assertEquals(110, result1.state());
        
        EffectResult<Integer, Void> result2 = effect.run(100, "other", context);
        assertEquals(95, result2.state());
    }

    @Test
    void testWhen_twoArgOverload_executesWhenTrue() {
        Effect<Integer, Throwable, Void> effect = 
            Effect.when(
                msg -> msg.equals("process"),
                Effect.modify(s -> s + 1)
            );
        
        EffectResult<Integer, Void> result = effect.run(5, "process", context);
        
        assertEquals(6, result.state());
    }

    @Test
    void testWhen_twoArgOverload_doesNothingWhenFalse() {
        Effect<Integer, Throwable, Void> effect = 
            Effect.when(
                msg -> msg.equals("process"),
                Effect.modify(s -> s + 1)
            );
        
        EffectResult<Integer, Void> result = effect.run(5, "skip", context);
        
        assertEquals(5, result.state());  // State unchanged
        assertInstanceOf(EffectResult.NoResult.class, result);
    }

    @Test
    void testWhen_canBeChained() {
        Effect<Integer, Throwable, String> effect = 
            Effect.<Integer, Throwable, String>when(
                msg -> msg.equals("start"),
                Effect.of("started"),
                Effect.of("skipped")
            )
            .map(s -> s.toUpperCase());
        
        EffectResult<Integer, String> result1 = effect.run(0, "start", context);
        assertEquals("STARTED", result1.value().orElseThrow());
        
        EffectResult<Integer, String> result2 = effect.run(0, "other", context);
        assertEquals("SKIPPED", result2.value().orElseThrow());
    }

    record TestPriority(int level) {}
    
    @Test
    void testWhen_withComplexPredicate() {
        Effect<Integer, Throwable, String> effect = 
            Effect.when(
                msg -> msg instanceof TestPriority p && p.level() > 5,
                Effect.of("high-priority"),
                Effect.of("low-priority")
            );
        
        EffectResult<Integer, String> result1 = effect.run(0, new TestPriority(8), context);
        assertEquals("high-priority", result1.value().orElseThrow());
        
        EffectResult<Integer, String> result2 = effect.run(0, new TestPriority(3), context);
        assertEquals("low-priority", result2.value().orElseThrow());
    }

    @Test
    void testWhen_preservesErrorsInBranches() {
        Effect<Integer, Throwable, String> effect = 
            Effect.when(
                msg -> msg.equals("fail"),
                Effect.fail(new IllegalStateException("Branch failed")),
                Effect.of("success")
            );
        
        EffectResult<Integer, String> result = effect.run(0, "fail", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertInstanceOf(IllegalStateException.class, result.error().get());
        assertEquals("Branch failed", result.error().get().getMessage());
    }
    
    // ============================================================================
    // Tests for New Operators
    // ============================================================================
    
    @Test
    void testDelay_suspendsExecutionForSpecifiedDuration() {
        long startTime = System.currentTimeMillis();
        
        Effect<Integer, Throwable, Void> effect = Effect.delay(Duration.ofMillis(100));
        EffectResult<Integer, Void> result = effect.run(42, "test", context);
        
        long elapsed = System.currentTimeMillis() - startTime;
        
        assertInstanceOf(EffectResult.NoResult.class, result);
        assertEquals(42, result.state());
        assertTrue(elapsed >= 100, "Should delay at least 100ms, was: " + elapsed);
    }
    
    @Test
    void testDelay_canBeChainedWithOtherEffects() {
        Effect<Integer, Throwable, String> effect = 
            Effect.<Integer, Throwable>delay(Duration.ofMillis(50))
                .andThen(Effect.of("delayed result"));
        
        EffectResult<Integer, String> result = effect.run(10, "test", context);
        
        assertInstanceOf(EffectResult.Success.class, result);
        assertEquals("delayed result", result.value().get());
        assertEquals(10, result.state());
    }
    
    @Test
    void testSuspend_defersComputationUntilExecution() {
        var counter = new java.util.concurrent.atomic.AtomicInteger(0);
        
        // Create effect - computation should NOT run yet
        Effect<Integer, Throwable, Integer> effect = Effect.suspend(() -> {
            counter.incrementAndGet();
            return 42;
        });
        
        assertEquals(0, counter.get(), "Computation should not run during effect creation");
        
        // Run effect - now computation should execute
        EffectResult<Integer, Integer> result = effect.run(0, "test", context);
        
        assertEquals(1, counter.get(), "Computation should run once");
        assertInstanceOf(EffectResult.Success.class, result);
        assertEquals(42, result.value().get());
    }
    
    @Test
    void testSuspend_capturesExceptions() {
        Effect<Integer, Throwable, String> effect = Effect.suspend(() -> {
            throw new RuntimeException("Suspended computation failed");
        });
        
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertEquals("Suspended computation failed", result.error().get().getMessage());
    }
    
    @Test
    void testBracket_ensuresResourceIsReleased() {
        var log = new java.util.ArrayList<String>();
        
        Effect<Integer, Throwable, String> effect = Effect.bracket(
            // Acquire
            Effect.attempt(() -> {
                log.add("acquire");
                return "resource";
            }),
            // Use
            resource -> Effect.attempt(() -> {
                log.add("use: " + resource);
                return "result";
            }),
            // Release
            resource -> Effect.attempt(() -> {
                log.add("release: " + resource);
                return null;
            })
        );
        
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Success.class, result);
        assertEquals("result", result.value().get());
        assertEquals(java.util.List.of("acquire", "use: resource", "release: resource"), log);
    }
    
    @Test
    void testBracket_releasesResourceEvenOnFailure() {
        var log = new java.util.ArrayList<String>();
        
        Effect<Integer, Throwable, String> effect = Effect.bracket(
            // Acquire
            Effect.attempt(() -> {
                log.add("acquire");
                return "resource";
            }),
            // Use - fails
            resource -> Effect.attempt(() -> {
                log.add("use: " + resource);
                throw new RuntimeException("Use failed");
            }),
            // Release - should still run
            resource -> Effect.attempt(() -> {
                log.add("release: " + resource);
                return null;
            })
        );
        
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertEquals("Use failed", result.error().get().getMessage());
        assertEquals(java.util.List.of("acquire", "use: resource", "release: resource"), log);
    }
    
    @Test
    void testBracket_doesNotReleaseIfAcquireFails() {
        var log = new java.util.ArrayList<String>();
        
        Effect<Integer, Throwable, String> effect = Effect.bracket(
            // Acquire - fails
            Effect.attempt(() -> {
                log.add("acquire");
                throw new RuntimeException("Acquire failed");
            }),
            // Use - should not run
            resource -> Effect.attempt(() -> {
                log.add("use: " + resource);
                return "result";
            }),
            // Release - should not run
            resource -> Effect.attempt(() -> {
                log.add("release: " + resource);
                return null;
            })
        );
        
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertEquals("Acquire failed", result.error().get().getMessage());
        assertEquals(java.util.List.of("acquire"), log);
    }
    
    @Test
    void testFromFuture_convertsCompletedFuture() {
        var future = java.util.concurrent.CompletableFuture.completedFuture("success");
        
        Effect<Integer, Throwable, String> effect = Effect.fromFuture(future);
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Success.class, result);
        assertEquals("success", result.value().get());
    }
    
    @Test
    void testFromFuture_waitsForAsyncFuture() {
        var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "async result";
        });
        
        Effect<Integer, Throwable, String> effect = Effect.fromFuture(future);
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Success.class, result);
        assertEquals("async result", result.value().get());
    }
    
    @Test
    void testFromFuture_capturesFailedFuture() {
        var future = new java.util.concurrent.CompletableFuture<String>();
        future.completeExceptionally(new RuntimeException("Future failed"));
        
        Effect<Integer, Throwable, String> effect = Effect.fromFuture(future);
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertTrue(result.error().get().getMessage().contains("Future failed"));
    }
    
    @Test
    void testParTraverse_processesCollectionInParallel() {
        var items = java.util.List.of(1, 2, 3, 4, 5);
        
        Effect<Integer, Throwable, java.util.List<Integer>> effect = 
            Effect.parTraverse(items, item -> 
                Effect.attempt(() -> item * 2)
            );
        
        EffectResult<Integer, java.util.List<Integer>> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Success.class, result);
        assertEquals(java.util.List.of(2, 4, 6, 8, 10), result.value().get());
    }
    
    @Test
    void testParTraverse_failsIfAnyItemFails() {
        var items = java.util.List.of(1, 2, 3, 4, 5);
        
        Effect<Integer, Throwable, java.util.List<Integer>> effect = 
            Effect.parTraverse(items, item -> 
                Effect.attempt(() -> {
                    if (item == 3) {
                        throw new RuntimeException("Item 3 failed");
                    }
                    return item * 2;
                })
            );
        
        EffectResult<Integer, java.util.List<Integer>> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertEquals("Item 3 failed", result.error().get().getMessage());
    }
    
    @Test
    void testParTraverse_handlesEmptyCollection() {
        var items = java.util.List.<Integer>of();
        
        Effect<Integer, Throwable, java.util.List<Integer>> effect = 
            Effect.parTraverse(items, item -> Effect.of(item * 2));
        
        EffectResult<Integer, java.util.List<Integer>> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Success.class, result);
        assertTrue(result.value().get().isEmpty());
    }
    
    @Test
    void testEnsure_runsFinalizer() {
        var log = new java.util.ArrayList<String>();
        
        Effect<Integer, Throwable, String> effect = 
            Effect.<Integer, Throwable, String>attempt(() -> {
                log.add("main");
                return "result";
            })
            .ensure(Effect.attempt(() -> {
                log.add("finalizer");
                return null;
            }));
        
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Success.class, result);
        assertEquals("result", result.value().get());
        assertEquals(java.util.List.of("main", "finalizer"), log);
    }
    
    @Test
    void testEnsure_runsFinalizerEvenOnFailure() {
        var log = new java.util.ArrayList<String>();
        
        Effect<Integer, Throwable, String> effect = 
            Effect.<Integer, Throwable, String>attempt(() -> {
                log.add("main");
                throw new RuntimeException("Main failed");
            })
            .ensure(Effect.attempt(() -> {
                log.add("finalizer");
                return null;
            }));
        
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertEquals("Main failed", result.error().get().getMessage());
        assertEquals(java.util.List.of("main", "finalizer"), log);
    }
    
    @Test
    void testEnsure_canChainMultipleFinalizers() {
        var log = new java.util.ArrayList<String>();
        
        Effect<Integer, Throwable, String> effect = 
            Effect.<Integer, Throwable, String>attempt(() -> {
                log.add("main");
                return "result";
            })
            .ensure(Effect.attempt(() -> {
                log.add("finalizer1");
                return null;
            }))
            .ensure(Effect.attempt(() -> {
                log.add("finalizer2");
                return null;
            }));
        
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Success.class, result);
        assertEquals(java.util.List.of("main", "finalizer1", "finalizer2"), log);
    }
    
    @Test
    void testRetry_succeedsOnFirstAttempt() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger(0);
        
        Effect<Integer, Throwable, String> effect = 
            Effect.<Integer, Throwable, String>attempt(() -> {
                attempts.incrementAndGet();
                return "success";
            })
            .retry(3, Duration.ofMillis(10));
        
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Success.class, result);
        assertEquals("success", result.value().get());
        assertEquals(1, attempts.get(), "Should succeed on first attempt");
    }
    
    @Test
    void testRetry_retriesOnFailure() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger(0);
        
        Effect<Integer, Throwable, String> effect = 
            Effect.<Integer, Throwable, String>attempt(() -> {
                int count = attempts.incrementAndGet();
                if (count < 3) {
                    throw new RuntimeException("Attempt " + count + " failed");
                }
                return "success on attempt " + count;
            })
            .retry(5, Duration.ofMillis(10));
        
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Success.class, result);
        assertEquals("success on attempt 3", result.value().get());
        assertEquals(3, attempts.get());
    }
    
    @Test
    void testRetry_failsAfterMaxAttempts() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger(0);
        
        Effect<Integer, Throwable, String> effect = 
            Effect.<Integer, Throwable, String>attempt(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("Always fails");
            })
            .retry(3, Duration.ofMillis(10));
        
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertEquals("Always fails", result.error().get().getMessage());
        assertEquals(3, attempts.get(), "Should attempt exactly maxAttempts times");
    }
    
    @Test
    void testRetry_implementsExponentialBackoff() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger(0);
        var timestamps = new java.util.ArrayList<Long>();
        
        Effect<Integer, Throwable, String> effect = 
            Effect.<Integer, Throwable, String>attempt(() -> {
                timestamps.add(System.currentTimeMillis());
                int count = attempts.incrementAndGet();
                if (count < 4) {
                    throw new RuntimeException("Retry");
                }
                return "success";
            })
            .retry(5, Duration.ofMillis(50));
        
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Success.class, result);
        assertEquals(4, attempts.get());
        
        // Check backoff delays (approximate due to timing variance)
        // Attempt 1: immediate
        // Attempt 2: after ~50ms
        // Attempt 3: after ~100ms
        // Attempt 4: after ~200ms
        if (timestamps.size() >= 2) {
            long delay1 = timestamps.get(1) - timestamps.get(0);
            assertTrue(delay1 >= 40, "First retry delay should be ~50ms, was: " + delay1);
        }
        if (timestamps.size() >= 3) {
            long delay2 = timestamps.get(2) - timestamps.get(1);
            assertTrue(delay2 >= 80, "Second retry delay should be ~100ms, was: " + delay2);
        }
    }
    
    @Test
    void testRetry_preservesStateFromLastAttempt() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger(0);
        
        // Retry re-runs the entire effect, so state modifications happen on each attempt
        Effect<Integer, Throwable, Integer> effect = 
            Effect.<Integer, Throwable, Integer>attempt(() -> {
                int count = attempts.incrementAndGet();
                if (count < 2) {
                    throw new RuntimeException("Retry");
                }
                return count;
            })
            .retry(3, Duration.ofMillis(10));
        
        EffectResult<Integer, Integer> result = effect.run(10, "test", context);
        
        assertInstanceOf(EffectResult.Success.class, result);
        assertEquals(2, result.value().get());
        // State is preserved from the last successful attempt
        assertEquals(10, result.state());
    }
}
