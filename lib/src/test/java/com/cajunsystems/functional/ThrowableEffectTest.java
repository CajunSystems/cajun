package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ThrowableEffect - the stack-safe, simplified effect monad.
 */
class ThrowableEffectTest {
    
    private ActorContext context;
    
    @BeforeEach
    void setUp() {
        context = Mockito.mock(ActorContext.class);
    }
    
    // ============================================================================
    // Factory Methods
    // ============================================================================
    
    @Test
    void testOf_returnsValue() {
        ThrowableEffect<Integer, String> effect = ThrowableEffect.of("hello");
        EffectResult<Integer, String> result = effect.run(42, "msg", context);
        
        assertTrue(result.value().isPresent());
        assertEquals("hello", result.value().get());
        assertEquals(42, result.state());
    }
    
    @Test
    void testState_returnsCurrentState() {
        ThrowableEffect<Integer, Integer> effect = ThrowableEffect.state();
        EffectResult<Integer, Integer> result = effect.run(99, "msg", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(99, result.value().get());
    }
    
    @Test
    void testModify_changesState() {
        ThrowableEffect<Integer, Void> effect = ThrowableEffect.modify(s -> s * 2);
        EffectResult<Integer, Void> result = effect.run(21, "msg", context);
        
        assertEquals(42, result.state());
        assertFalse(result.value().isPresent());
    }
    
    @Test
    void testSetState_replacesState() {
        ThrowableEffect<Integer, Void> effect = ThrowableEffect.setState(100);
        EffectResult<Integer, Void> result = effect.run(42, "msg", context);
        
        assertEquals(100, result.state());
    }
    
    @Test
    void testIdentity_keepsStateUnchanged() {
        ThrowableEffect<Integer, Void> effect = ThrowableEffect.identity();
        EffectResult<Integer, Void> result = effect.run(42, "msg", context);
        
        assertEquals(42, result.state());
    }
    
    @Test
    void testFail_returnsFailure() {
        RuntimeException error = new RuntimeException("test error");
        ThrowableEffect<Integer, String> effect = ThrowableEffect.fail(error);
        EffectResult<Integer, String> result = effect.run(42, "msg", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        EffectResult.Failure<Integer, String> failure = (EffectResult.Failure<Integer, String>) result;
        assertEquals(error, failure.errorValue());
    }
    
    // ============================================================================
    // Monadic Operations - Stack Safety
    // ============================================================================
    
    @Test
    void testMap_transformsResult() {
        ThrowableEffect<Integer, Integer> effect = ThrowableEffect.<Integer, Integer>of(10)
            .map(x -> x * 2)
            .map(x -> x + 5);
        
        EffectResult<Integer, Integer> result = effect.run(0, "msg", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(25, result.value().get());
    }
    
    @Test
    void testFlatMap_chainsEffects() {
        ThrowableEffect<Integer, Integer> effect = ThrowableEffect.<Integer, Integer>of(5)
            .flatMap(x -> ThrowableEffect.<Integer, Integer>of(x * 2))
            .flatMap(x -> ThrowableEffect.<Integer, Integer>of(x + 10));
        
        EffectResult<Integer, Integer> result = effect.run(0, "msg", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(20, result.value().get());
    }
    
    @Test
    void testAndThen_sequencesEffects() {
        ThrowableEffect<Integer, String> effect = ThrowableEffect.<Integer>modify(s -> s + 10)
            .andThen(ThrowableEffect.of("done"));
        
        EffectResult<Integer, String> result = effect.run(5, "msg", context);
        
        assertEquals(15, result.state());
        assertTrue(result.value().isPresent());
        assertEquals("done", result.value().get());
    }
    
    @Test
    void testStackSafety_deepMapChain() {
        // Demonstrates trampoline prevents stack overflow for reasonable depths
        ThrowableEffect<Integer, Integer> effect = ThrowableEffect.<Integer, Integer>of(0);
        
        for (int i = 0; i < 100; i++) {
            final ThrowableEffect<Integer, Integer> current = effect;
            effect = (state, msg, ctx) -> current.runT(state, msg, ctx).map(r -> r.map(x -> x + 1));
        }
        
        // Should not cause StackOverflowError
        EffectResult<Integer, Integer> result = effect.run(0, "msg", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(100, result.value().get());
    }
    
    @Test
    void testStackSafety_deepFlatMapChain() {
        // Demonstrates trampoline prevents stack overflow for reasonable depths
        ThrowableEffect<Integer, Integer> effect = ThrowableEffect.<Integer, Integer>of(0);
        
        for (int i = 0; i < 100; i++) {
            final ThrowableEffect<Integer, Integer> current = effect;
            effect = (state, msg, ctx) -> current.runT(state, msg, ctx)
                .flatMap(r -> ThrowableEffect.<Integer, Integer>of(r.value().orElse(0) + 1).runT(r.state(), msg, ctx));
        }
        
        // Should not cause StackOverflowError
        EffectResult<Integer, Integer> result = effect.run(0, "msg", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(100, result.value().get());
    }
    
    // ============================================================================
    // Error Channel
    // ============================================================================
    
    @Test
    void testAttempt_catchesExceptions() {
        ThrowableEffect<Integer, String> effect = ((ThrowableEffect<Integer, String>) (state, msg, ctx) -> {
            throw new RuntimeException("test error");
        }).attempt();
        
        EffectResult<Integer, String> result = effect.run(0, "msg", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
    }
    
    @Test
    void testHandleErrorWith_recoversFromError() {
        ThrowableEffect<Integer, String> effect = ThrowableEffect.<Integer, String>fail(new RuntimeException("error"))
            .handleErrorWith((err, s, m, c) -> ThrowableEffect.of("recovered"));
        
        EffectResult<Integer, String> result = effect.run(42, "msg", context);
        
        assertTrue(result.value().isPresent());
        assertEquals("recovered", result.value().get());
    }
    
    @Test
    void testHandleError_recoversState() {
        ThrowableEffect<Integer, String> effect = ThrowableEffect.<Integer, String>fail(new RuntimeException("error"))
            .handleError((err, s, m, c) -> 999);
        
        EffectResult<Integer, String> result = effect.run(42, "msg", context);
        
        assertEquals(999, result.state());
    }
    
    @Test
    void testTapError_performsSideEffect() {
        StringBuilder log = new StringBuilder();
        
        ThrowableEffect<Integer, String> effect = ThrowableEffect.<Integer, String>fail(new RuntimeException("test"))
            .tapError(err -> log.append("Error: ").append(err.getMessage()));
        
        effect.run(0, "msg", context);
        
        assertEquals("Error: test", log.toString());
    }
    
    // ============================================================================
    // Validation
    // ============================================================================
    
    @Test
    void testFilterOrElse_passesWhenPredicateTrue() {
        ThrowableEffect<Integer, Void> effect = ThrowableEffect.<Integer>modify(s -> s + 10)
            .filterOrElse(s -> s > 0, ThrowableEffect.of(null));
        
        EffectResult<Integer, Void> result = effect.run(5, "msg", context);
        
        assertEquals(15, result.state());
    }
    
    @Test
    void testFilterOrElse_usesFallbackWhenPredicateFalse() {
        ThrowableEffect<Integer, Void> effect = ThrowableEffect.<Integer>modify(s -> s - 100)
            .filterOrElse(s -> s > 0, ThrowableEffect.setState(0));
        
        EffectResult<Integer, Void> result = effect.run(50, "msg", context);
        
        assertEquals(0, result.state());  // Fallback was used
    }
    
    // ============================================================================
    // Parallel Execution
    // ============================================================================
    
    @Test
    void testParZip_combinesTwoEffects() {
        ThrowableEffect<Integer, Integer> effect1 = ThrowableEffect.of(10);
        ThrowableEffect<Integer, Integer> effect2 = ThrowableEffect.of(20);
        
        ThrowableEffect<Integer, Integer> combined = effect1.parZip(effect2, (a, b) -> a + b);
        
        EffectResult<Integer, Integer> result = combined.run(0, "msg", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(30, result.value().get());
    }
    
    @Test
    void testParSequence_collectsAllResults() {
        List<ThrowableEffect<Integer, Integer>> effects = List.of(
            ThrowableEffect.of(1),
            ThrowableEffect.of(2),
            ThrowableEffect.of(3)
        );
        
        ThrowableEffect<Integer, List<Integer>> combined = ThrowableEffect.parSequence(effects);
        
        EffectResult<Integer, List<Integer>> result = combined.run(0, "msg", context);
        
        assertTrue(result.value().isPresent());
        assertEquals(List.of(1, 2, 3), result.value().get());
    }
    
    @Test
    void testSequence_threadsStateThroughEffects() {
        List<ThrowableEffect<Integer, Void>> effects = List.of(
            ThrowableEffect.modify(s -> s + 10),  // 5 + 10 = 15
            ThrowableEffect.modify(s -> s * 2),   // 15 * 2 = 30
            ThrowableEffect.modify(s -> s - 5)    // 30 - 5 = 25
        );
        
        ThrowableEffect<Integer, List<Void>> pipeline = ThrowableEffect.sequence(effects);
        
        EffectResult<Integer, List<Void>> result = pipeline.run(5, "msg", context);
        
        assertEquals(25, result.state());
    }
    
    // ============================================================================
    // Match Builder
    // ============================================================================
    
    record Increment(int amount) {}
    record Decrement(int amount) {}
    
    @Test
    void testMatch_dispatchesByMessageType() {
        ThrowableEffect<Integer, Void> behavior = ThrowableEffect.<Integer>match()
            .when(Increment.class, (state, msg, ctx) -> 
                ThrowableEffect.modify(s -> s + msg.amount())
            )
            .when(Decrement.class, (state, msg, ctx) -> 
                ThrowableEffect.modify(s -> s - msg.amount())
            )
            .build();
        
        // Test Increment
        EffectResult<Integer, Void> result1 = behavior.run(10, new Increment(5), context);
        assertEquals(15, result1.state());
        
        // Test Decrement
        EffectResult<Integer, Void> result2 = behavior.run(20, new Decrement(7), context);
        assertEquals(13, result2.state());
    }
}
