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
    // Effect.identity() Tests
    // ============================================================================

    @Test
    void testIdentity_keepsStateUnchanged() {
        Effect<Integer, String, Void> effect = Effect.identity();
        
        EffectResult<Integer, Void> result = effect.run(42, "test", context);
        
        assertEquals(42, result.state());
        assertFalse(result.value().isPresent());
    }

    @Test
    void testIdentity_canBeChained() {
        Effect<Integer, String, Void> effect = Effect.identity();
        Effect<Integer, String, Void> chained = effect.andThen(Effect.modify(s -> s + 10));
        
        EffectResult<Integer, Void> result = chained.run(5, "test", context);
        
        assertEquals(15, result.state());
    }

    // ============================================================================
    // filterOrElse() Tests
    // ============================================================================

    @Test
    void testFilterOrElse_passesWhenPredicateTrue() {
        Effect<Integer, String, Integer> effect = Effect.<Integer, String, Integer>of(10);
        Effect<Integer, String, Integer> fallback = Effect.<Integer, String, Integer>of(-1);
        
        Effect<Integer, String, Integer> validated = effect.filterOrElse(
            state -> state > 0,
            fallback
        );
        
        EffectResult<Integer, Integer> result = validated.run(5, "test", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(10, result.value().get());
    }

    @Test
    void testFilterOrElse_executesFallbackWhenPredicateFalse() {
        // Create effect that subtracts 10 and returns result
        Effect<Integer, String, Integer> effect = (state, msg, ctx) -> 
            EffectResult.success(state - 10, state - 10);
        
        Effect<Integer, String, Integer> fallback = Effect.<Integer, String, Integer>of(-1);
        
        Effect<Integer, String, Integer> validated = effect.filterOrElse(
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
        Effect<Integer, String, Integer> effect = (state, msg, ctx) -> {
            if (state < 0) {
                throw new IllegalArgumentException("Negative value");
            }
            return EffectResult.success(state * 2, state * 2);
        };
        
        Effect<Integer, String, Integer> safe = effect.attempt();
        
        EffectResult<Integer, Integer> result = safe.run(-5, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertEquals(-5, result.state());
    }

    @Test
    void testHandleErrorWith_recoversFromError() {
        Effect<Integer, String, Integer> effect = (state, msg, ctx) -> {
            throw new RuntimeException("Error");
        };
        
        Effect<Integer, String, Integer> recovered = effect
            .attempt()
            .handleErrorWith((err, s, m, c) -> {
                Effect<Integer, String, Integer> recovery = (st, ms, ct) -> EffectResult.success(s, 999);
                return recovery;
            });
        
        EffectResult<Integer, Integer> result = recovered.run(10, "test", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(999, result.value().get());
    }

    @Test
    void testHandleError_recoversState() {
        Effect<Integer, String, Void> effect = (state, msg, ctx) -> {
            throw new RuntimeException("Error");
        };
        
        Effect<Integer, String, Void> recovered = effect
            .attempt()
            .handleError((err, s, m, c) -> s + 100);
        
        EffectResult<Integer, Void> result = recovered.run(10, "test", context);
        
        assertEquals(110, result.state());
    }

    @Test
    void testTapError_performsSideEffectOnError() {
        final boolean[] errorLogged = {false};
        
        Effect<Integer, String, Integer> effect = (state, msg, ctx) -> {
            throw new RuntimeException("Test error");
        };
        
        Effect<Integer, String, Integer> withTap = effect
            .attempt()
            .tapError(err -> errorLogged[0] = true)
            .handleErrorWith((err, s, m, c) -> {
                Effect<Integer, String, Integer> recovery = (st, ms, ct) -> EffectResult.success(s, 0);
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
        Effect<Integer, String, Integer> effect1 = Effect.<Integer, String, Integer>of(10);
        Effect<Integer, String, Integer> effect2 = Effect.<Integer, String, Integer>of(20);
        
        Effect<Integer, String, Integer> combined = effect1.parZip(effect2, 
            (a, b) -> a + b
        );
        
        EffectResult<Integer, Integer> result = combined.run(0, "test", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(30, result.value().get());
    }

    @Test
    void testParZip_failsIfEitherFails() {
        Effect<Integer, String, Integer> effect1 = Effect.<Integer, String, Integer>of(10);
        Effect<Integer, String, Integer> effect2 = Effect.<Integer, String, Integer>fail(new RuntimeException("Error"));
        
        Effect<Integer, String, Integer> combined = effect1.parZip(effect2, 
            (a, b) -> a + b
        );
        
        EffectResult<Integer, Integer> result = combined.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
    }

    @Test
    void testSequence_threadsStateThroughEffects() {
        List<Effect<Integer, String, Void>> effects = List.of(
            Effect.<Integer, String>modify(s -> s + 10),  // 5 + 10 = 15
            Effect.<Integer, String>modify(s -> s * 2),   // 15 * 2 = 30
            Effect.<Integer, String>modify(s -> s - 5)    // 30 - 5 = 25
        );
        
        Effect<Integer, String, List<Void>> pipeline = Effect.sequence(effects);
        
        EffectResult<Integer, List<Void>> result = pipeline.run(5, "test", context);
        
        assertEquals(25, result.state());  // Final state after all transformations
    }

    @Test
    void testSequence_failsOnFirstError() {
        List<Effect<Integer, String, Integer>> effects = List.of(
            Effect.<Integer, String, Integer>of(1),
            (state, msg, ctx) -> {
                throw new RuntimeException("Error in step 2");
            },
            Effect.<Integer, String, Integer>of(3)  // Should not execute
        );
        
        Effect<Integer, String, List<Integer>> pipeline = Effect.sequence(effects)
            .attempt();
        
        EffectResult<Integer, List<Integer>> result = pipeline.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
    }

    @Test
    void testParSequence_collectsAllResults() {
        List<Effect<Integer, String, Integer>> effects = List.of(
            Effect.<Integer, String, Integer>of(1),
            Effect.<Integer, String, Integer>of(2),
            Effect.<Integer, String, Integer>of(3)
        );
        
        Effect<Integer, String, List<Integer>> combined = Effect.parSequence(effects);
        
        EffectResult<Integer, List<Integer>> result = combined.run(0, "test", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(List.of(1, 2, 3), result.value().get());
    }

    @Test
    void testParSequence_failsIfAnyFails() {
        List<Effect<Integer, String, Integer>> effects = List.of(
            Effect.<Integer, String, Integer>of(1),
            Effect.<Integer, String, Integer>fail(new RuntimeException("Error")),
            Effect.<Integer, String, Integer>of(3)
        );
        
        Effect<Integer, String, List<Integer>> combined = Effect.parSequence(effects);
        
        EffectResult<Integer, List<Integer>> result = combined.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
    }

    @Test
    void testRace_returnsFirstToComplete() {
        // Create effects with different delays
        Effect<Integer, String, String> fast = (state, msg, ctx) -> {
            return EffectResult.success(state, "fast");
        };
        
        Effect<Integer, String, String> slow = (state, msg, ctx) -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return EffectResult.success(state, "slow");
        };
        
        Effect<Integer, String, String> raced = fast.race(slow);
        
        EffectResult<Integer, String> result = raced.run(0, "test", context);
        
        assertTrue(result.value().isPresent());
        assertEquals("fast", result.value().get());
    }

    @Test
    void testWithTimeout_completesWithinTimeout() {
        Effect<Integer, String, Integer> effect = Effect.<Integer, String, Integer>of(42);
        Effect<Integer, String, Integer> timed = effect.withTimeout(Duration.ofSeconds(1));
        
        EffectResult<Integer, Integer> result = timed.run(0, "test", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(42, result.value().get());
    }

    @Test
    void testWithTimeout_failsOnTimeout() {
        Effect<Integer, String, Integer> slowEffect = (state, msg, ctx) -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return EffectResult.success(state, 42);
        };
        
        Effect<Integer, String, Integer> timedEffect = slowEffect.withTimeout(Duration.ofMillis(50));
        
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
        Effect<Integer, String, Integer> effect1 = Effect.<Integer, String, Integer>of(10).attempt();
        Effect<Integer, String, Integer> effect2 = Effect.<Integer, String, Integer>of(20).attempt();
        
        Effect<Integer, String, Integer> combined = effect1.parZip(effect2, 
            (a, b) -> a + b
        )
        .handleErrorWith((err, s, m, c) -> {
            Effect<Integer, String, Integer> recovery = (st, ms, ct) -> EffectResult.success(s, 0);
            return recovery;
        });
        
        EffectResult<Integer, Integer> result = combined.run(0, "test", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(30, result.value().get());
    }

    @Test
    void testCombinedOperators_timeoutWithFallback() {
        Effect<Integer, String, Integer> slowEffect = (state, msg, ctx) -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return EffectResult.success(state, 42);
        };
        
        Effect<Integer, String, Integer> robust = slowEffect
            .withTimeout(Duration.ofMillis(50))
            .handleErrorWith((err, s, m, c) -> {
                Effect<Integer, String, Integer> recovery = (st, ms, ct) -> EffectResult.success(s, 99);
                return recovery;
            });
        
        EffectResult<Integer, Integer> result = robust.run(0, "test", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(99, result.value().get());
    }

    @Test
    void testCombinedOperators_parallelSequenceWithTimeout() {
        List<Effect<Integer, String, Integer>> effects = List.of(
            Effect.<Integer, String, Integer>of(1).withTimeout(Duration.ofSeconds(1)),
            Effect.<Integer, String, Integer>of(2).withTimeout(Duration.ofSeconds(1)),
            Effect.<Integer, String, Integer>of(3).withTimeout(Duration.ofSeconds(1))
        );
        
        Effect<Integer, String, List<Integer>> combined = Effect.parSequence(effects)
            .handleErrorWith((err, s, m, c) -> {
                Effect<Integer, String, List<Integer>> recovery = (st, ms, ct) -> EffectResult.success(s, List.of());
                return recovery;
            });
        
        EffectResult<Integer, List<Integer>> result = combined.run(0, "test", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(List.of(1, 2, 3), result.value().get());
    }

    @Test
    void testIdentityWithModify_preservesStateCorrectly() {
        Effect<Integer, String, Void> effect = Effect.<Integer, String>modify(s -> s + 10)
            .andThen(Effect.identity())
            .andThen(Effect.<Integer, String>modify(s -> s * 2));
        
        EffectResult<Integer, Void> result = effect.run(5, "test", context);
        
        assertEquals(30, result.state());  // (5 + 10) * 2
    }
}
