package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;
import com.cajunsystems.Pid;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive test suite for the Effect monad.
 * Tests all monadic operations, factory methods, and actor-specific effects.
 */
@DisplayName("Effect Monad Tests")
class EffectTest {
    
    private ActorContext mockContext;
    private Logger mockLogger;
    
    @BeforeEach
    void setUp() {
        mockContext = mock(ActorContext.class);
        mockLogger = mock(Logger.class);
        when(mockContext.getLogger()).thenReturn(mockLogger);
    }
    
    // ============================================================================
    // Factory Methods Tests
    // ============================================================================
    
    @Nested
    @DisplayName("Factory Methods")
    class FactoryMethodsTest {
        
        @Test
        @DisplayName("of() creates effect with value")
        void testOf() {
            Effect<Integer, String, String> effect = Effect.of("success");
            
            EffectResult<Integer, String> result = effect.run(42, "msg", mockContext);
            
            assertTrue(result.isSuccess());
            assertEquals(42, result.state());
            assertEquals("success", result.value().orElseThrow());
        }
        
        @Test
        @DisplayName("state() returns current state as result")
        void testState() {
            Effect<Integer, String, Integer> effect = Effect.state();
            
            EffectResult<Integer, Integer> result = effect.run(42, "msg", mockContext);
            
            assertTrue(result.isSuccess());
            assertEquals(42, result.state());
            assertEquals(42, result.value().orElseThrow());
        }
        
        @Test
        @DisplayName("modify() changes state")
        void testModify() {
            Effect<Integer, String, Void> effect = Effect.modify(s -> s + 10);
            
            EffectResult<Integer, Void> result = effect.run(42, "msg", mockContext);
            
            assertFalse(result.hasValue());
            assertEquals(52, result.state());
        }
        
        @Test
        @DisplayName("setState() sets specific state")
        void testSetState() {
            Effect<Integer, String, Void> effect = Effect.setState(100);
            
            EffectResult<Integer, Void> result = effect.run(42, "msg", mockContext);
            
            assertFalse(result.hasValue());
            assertEquals(100, result.state());
        }
        
        @Test
        @DisplayName("fromTransition() uses state and message")
        void testFromTransition() {
            Effect<Integer, String, Void> effect = 
                Effect.fromTransition((state, msg) -> state + msg.length());
            
            EffectResult<Integer, Void> result = effect.run(10, "hello", mockContext);
            
            assertFalse(result.hasValue());
            assertEquals(15, result.state());
        }
        
        @Test
        @DisplayName("fail() creates failing effect")
        void testFail() {
            IllegalStateException error = new IllegalStateException("test error");
            Effect<Integer, String, String> effect = Effect.fail(error);
            
            EffectResult<Integer, String> result = effect.run(42, "msg", mockContext);
            
            assertTrue(result.isFailure());
            assertEquals(42, result.state());
            assertEquals(error, result.error().orElseThrow());
        }
        
        @Test
        @DisplayName("none() does nothing")
        void testNone() {
            Effect<Integer, String, Void> effect = Effect.none();
            
            EffectResult<Integer, Void> result = effect.run(42, "msg", mockContext);
            
            assertFalse(result.hasValue());
            assertFalse(result.isFailure());
            assertEquals(42, result.state());
        }
    }
    
    // ============================================================================
    // Monadic Operations Tests
    // ============================================================================
    
    @Nested
    @DisplayName("Monadic Operations")
    class MonadicOperationsTest {
        
        @Test
        @DisplayName("map() transforms result")
        void testMap() {
            Effect<Integer, String, Integer> effect = Effect.of(10);
            Effect<Integer, String, String> mapped = effect.map(n -> "Count: " + n);
            
            EffectResult<Integer, String> result = mapped.run(42, "msg", mockContext);
            
            assertTrue(result.isSuccess());
            assertEquals("Count: 10", result.value().orElseThrow());
        }
        
        @Test
        @DisplayName("map() preserves failure")
        void testMapPreservesFailure() {
            Effect<Integer, String, Integer> effect = Effect.fail(new RuntimeException("error"));
            Effect<Integer, String, String> mapped = effect.map(n -> "Count: " + n);
            
            EffectResult<Integer, String> result = mapped.run(42, "msg", mockContext);
            
            assertTrue(result.isFailure());
        }
        
        @Test
        @DisplayName("flatMap() chains effects")
        void testFlatMap() {
            Effect<Integer, String, Integer> effect = Effect.of(10);
            Effect<Integer, String, Integer> chained = effect.flatMap(n -> 
                Effect.<Integer, String>modify(s -> s + n).andThen(Effect.of(n * 2))
            );
            
            EffectResult<Integer, Integer> result = chained.run(5, "msg", mockContext);
            
            assertTrue(result.isSuccess());
            assertEquals(15, result.state()); // 5 + 10
            assertEquals(20, result.value().orElseThrow()); // 10 * 2
        }
        
        @Test
        @DisplayName("flatMap() short-circuits on failure")
        void testFlatMapShortCircuits() {
            Effect<Integer, String, Integer> effect = Effect.fail(new RuntimeException("error"));
            Effect<Integer, String, Integer> chained = effect.flatMap(n -> Effect.of(n * 2));
            
            EffectResult<Integer, Integer> result = chained.run(42, "msg", mockContext);
            
            assertTrue(result.isFailure());
            assertEquals(42, result.state());
        }
        
        @Test
        @DisplayName("andThen() sequences effects")
        void testAndThen() {
            Effect<Integer, String, Void> effect1 = Effect.modify(s -> s + 10);
            Effect<Integer, String, Void> effect2 = Effect.modify(s -> s * 2);
            Effect<Integer, String, Void> combined = effect1.andThen(effect2);
            
            EffectResult<Integer, Void> result = combined.run(5, "msg", mockContext);
            
            assertEquals(30, result.state()); // (5 + 10) * 2
        }
        
        @Test
        @DisplayName("andThen() stops on failure")
        void testAndThenStopsOnFailure() {
            Effect<Integer, String, Void> effect1 = Effect.fail(new RuntimeException("error"));
            Effect<Integer, String, Void> effect2 = Effect.modify(s -> s * 2);
            Effect<Integer, String, Void> combined = effect1.andThen(effect2);
            
            EffectResult<Integer, Void> result = combined.run(5, "msg", mockContext);
            
            assertTrue(result.isFailure());
            assertEquals(5, result.state()); // State unchanged
        }
    }
    
    // ============================================================================
    // Error Handling Tests
    // ============================================================================
    
    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTest {
        
        @Test
        @DisplayName("orElse() provides fallback")
        void testOrElse() {
            Effect<Integer, String, String> failing = Effect.fail(new RuntimeException("error"));
            Effect<Integer, String, String> fallback = Effect.of("default");
            Effect<Integer, String, String> combined = failing.orElse(fallback);
            
            EffectResult<Integer, String> result = combined.run(42, "msg", mockContext);
            
            assertTrue(result.isSuccess());
            assertEquals("default", result.value().orElseThrow());
        }
        
        @Test
        @DisplayName("orElse() skipped on success")
        void testOrElseSkippedOnSuccess() {
            Effect<Integer, String, String> success = Effect.of("success");
            Effect<Integer, String, String> fallback = Effect.of("fallback");
            Effect<Integer, String, String> combined = success.orElse(fallback);
            
            EffectResult<Integer, String> result = combined.run(42, "msg", mockContext);
            
            assertTrue(result.isSuccess());
            assertEquals("success", result.value().orElseThrow());
        }
        
        @Test
        @DisplayName("recover() transforms error to result")
        void testRecover() {
            Effect<Integer, String, String> failing = Effect.fail(new RuntimeException("error"));
            Effect<Integer, String, String> recovered = failing.recover(error -> 
                "Recovered: " + error.getMessage()
            );
            
            EffectResult<Integer, String> result = recovered.run(42, "msg", mockContext);
            
            assertTrue(result.isSuccess());
            assertEquals("Recovered: error", result.value().orElseThrow());
        }
        
        @Test
        @DisplayName("recoverWith() runs recovery effect")
        void testRecoverWith() {
            Effect<Integer, String, String> failing = Effect.fail(new RuntimeException("error"));
            Effect<Integer, String, String> recovered = failing.recoverWith(error -> 
                Effect.<Integer, String>modify(s -> s + 100).andThen(Effect.of("recovered"))
            );
            
            EffectResult<Integer, String> result = recovered.run(42, "msg", mockContext);
            
            assertTrue(result.isSuccess());
            assertEquals(142, result.state());
            assertEquals("recovered", result.value().orElseThrow());
        }
    }
    
    // ============================================================================
    // Filtering and Validation Tests
    // ============================================================================
    
    @Nested
    @DisplayName("Filtering and Validation")
    class FilteringTest {
        
        @Test
        @DisplayName("filter() passes when predicate is true")
        void testFilterPass() {
            Effect<Integer, String, Integer> effect = Effect.of(10);
            Effect<Integer, String, Integer> filtered = effect.filter(n -> n > 5, "Too small");
            
            EffectResult<Integer, Integer> result = filtered.run(42, "msg", mockContext);
            
            assertTrue(result.isSuccess());
            assertEquals(10, result.value().orElseThrow());
        }
        
        @Test
        @DisplayName("filter() fails when predicate is false")
        void testFilterFail() {
            Effect<Integer, String, Integer> effect = Effect.of(3);
            Effect<Integer, String, Integer> filtered = effect.filter(n -> n > 5, "Too small");
            
            EffectResult<Integer, Integer> result = filtered.run(42, "msg", mockContext);
            
            assertTrue(result.isFailure());
            assertTrue(result.error().orElseThrow() instanceof IllegalStateException);
            assertEquals("Too small", result.error().orElseThrow().getMessage());
        }
    }
    
    // ============================================================================
    // Side Effects Tests
    // ============================================================================
    
    @Nested
    @DisplayName("Side Effects")
    class SideEffectsTest {
        
        @Test
        @DisplayName("tap() performs side effect with result")
        void testTap() {
            AtomicReference<Integer> captured = new AtomicReference<>();
            Effect<Integer, String, Integer> effect = Effect.of(10);
            Effect<Integer, String, Integer> tapped = effect.tap(captured::set);
            
            EffectResult<Integer, Integer> result = tapped.run(42, "msg", mockContext);
            
            assertTrue(result.isSuccess());
            assertEquals(10, captured.get());
            assertEquals(10, result.value().orElseThrow());
        }
        
        @Test
        @DisplayName("tap() skipped when no result")
        void testTapSkippedWhenNoResult() {
            AtomicInteger callCount = new AtomicInteger(0);
            Effect<Integer, String, Void> effect = Effect.modify(s -> s + 1);
            Effect<Integer, String, Void> tapped = effect.tap(v -> callCount.incrementAndGet());
            
            EffectResult<Integer, Void> result = tapped.run(42, "msg", mockContext);
            
            assertEquals(0, callCount.get()); // tap not called
            assertEquals(43, result.state());
        }
        
        @Test
        @DisplayName("tapState() performs side effect with state")
        void testTapState() {
            AtomicReference<Integer> captured = new AtomicReference<>();
            Effect<Integer, String, Integer> effect = Effect.of(10);
            Effect<Integer, String, Integer> tapped = effect.tapState(captured::set);
            
            EffectResult<Integer, Integer> result = tapped.run(42, "msg", mockContext);
            
            assertTrue(result.isSuccess());
            assertEquals(42, captured.get()); // Captures state, not result
        }
        
        @Test
        @DisplayName("tapBoth() performs side effect with state and result")
        void testTapBoth() {
            AtomicReference<Integer> capturedState = new AtomicReference<>();
            AtomicReference<Optional<Integer>> capturedResult = new AtomicReference<>();
            
            Effect<Integer, String, Integer> effect = Effect.of(10);
            Effect<Integer, String, Integer> tapped = effect.tapBoth((state, result) -> {
                capturedState.set(state);
                capturedResult.set(result);
            });
            
            EffectResult<Integer, Integer> result = tapped.run(42, "msg", mockContext);
            
            assertTrue(result.isSuccess());
            assertEquals(42, capturedState.get());
            assertEquals(10, capturedResult.get().orElseThrow());
        }
    }
    
    // ============================================================================
    // Actor-Specific Effects Tests
    // ============================================================================
    
    @Nested
    @DisplayName("Actor-Specific Effects")
    class ActorEffectsTest {
        
        @Test
        @DisplayName("tell() sends message to actor")
        void testTell() {
            Pid targetPid = mock(Pid.class);
            Effect<Integer, String, Void> effect = Effect.tell(targetPid, "hello");
            
            EffectResult<Integer, Void> result = effect.run(42, "msg", mockContext);
            
            verify(mockContext).tell(targetPid, "hello");
            assertFalse(result.hasValue());
            assertEquals(42, result.state());
        }
        
        @Test
        @DisplayName("tellSelf() sends message to self")
        void testTellSelf() {
            Effect<Integer, String, Void> effect = Effect.tellSelf("self-message");
            
            EffectResult<Integer, Void> result = effect.run(42, "msg", mockContext);
            
            verify(mockContext).tellSelf("self-message");
            assertFalse(result.hasValue());
            assertEquals(42, result.state());
        }
        
        @Test
        @DisplayName("log() logs message")
        void testLog() {
            Effect<Integer, String, Void> effect = Effect.log("test message");
            
            EffectResult<Integer, Void> result = effect.run(42, "msg", mockContext);
            
            verify(mockLogger).info("test message");
            assertFalse(result.hasValue());
        }
        
        @Test
        @DisplayName("logState() logs derived from state")
        void testLogState() {
            Effect<Integer, String, Void> effect = Effect.logState(s -> "State: " + s);
            
            EffectResult<Integer, Void> result = effect.run(42, "msg", mockContext);
            
            verify(mockLogger).info("State: 42");
            assertFalse(result.hasValue());
        }
        
        @Test
        @DisplayName("logError() logs error message")
        void testLogError() {
            Effect<Integer, String, Void> effect = Effect.logError("error occurred");
            
            EffectResult<Integer, Void> result = effect.run(42, "msg", mockContext);
            
            verify(mockLogger).error("error occurred");
            assertFalse(result.hasValue());
        }
        
        @Test
        @DisplayName("logError() with throwable logs error with exception")
        void testLogErrorWithThrowable() {
            Throwable error = new RuntimeException("test");
            Effect<Integer, String, Void> effect = Effect.logError("error occurred", error);
            
            EffectResult<Integer, Void> result = effect.run(42, "msg", mockContext);
            
            verify(mockLogger).error("error occurred", error);
            assertFalse(result.hasValue());
        }
    }
    
    // ============================================================================
    // Pattern Matching Tests
    // ============================================================================
    
    @Nested
    @DisplayName("Pattern Matching")
    class PatternMatchingTest {
        
        sealed interface CounterMsg {}
        record Increment(int amount) implements CounterMsg {}
        record Decrement(int amount) implements CounterMsg {}
        record Reset() implements CounterMsg {}
        
        @Test
        @DisplayName("match() routes to correct handler")
        void testMatch() {
            Effect<Integer, CounterMsg, Void> effect = Effect.<Integer, CounterMsg, Void>match()
                .when(Increment.class, (state, msg, ctx) -> 
                    Effect.modify(s -> s + msg.amount()))
                .when(Decrement.class, (state, msg, ctx) ->
                    Effect.modify(s -> s - msg.amount()))
                .when(Reset.class, (state, msg, ctx) ->
                    Effect.setState(0))
                .build();
            
            // Test Increment
            EffectResult<Integer, Void> result1 = effect.run(10, new Increment(5), mockContext);
            assertEquals(15, result1.state());
            
            // Test Decrement
            EffectResult<Integer, Void> result2 = effect.run(10, new Decrement(3), mockContext);
            assertEquals(7, result2.state());
            
            // Test Reset
            EffectResult<Integer, Void> result3 = effect.run(10, new Reset(), mockContext);
            assertEquals(0, result3.state());
        }
        
        @Test
        @DisplayName("match() uses otherwise for unmatched messages")
        void testMatchOtherwise() {
            Effect<Integer, CounterMsg, Void> effect = Effect.<Integer, CounterMsg, Void>match()
                .when(Increment.class, (state, msg, ctx) -> 
                    Effect.modify(s -> s + msg.amount()))
                .otherwise(Effect.log("Unknown message"));
            
            EffectResult<Integer, Void> result = effect.run(10, new Decrement(3), mockContext);
            
            verify(mockLogger).info("Unknown message");
            assertEquals(10, result.state()); // State unchanged
        }
        
        @Test
        @DisplayName("match() fails without otherwise for unmatched messages")
        void testMatchFailsWithoutOtherwise() {
            Effect<Integer, CounterMsg, Void> effect = Effect.<Integer, CounterMsg, Void>match()
                .when(Increment.class, (state, msg, ctx) -> 
                    Effect.modify(s -> s + msg.amount()))
                .build();
            
            EffectResult<Integer, Void> result = effect.run(10, new Decrement(3), mockContext);
            
            assertTrue(result.isFailure());
            assertTrue(result.error().orElseThrow() instanceof IllegalArgumentException);
        }
        
        @Test
        @DisplayName("when() creates conditional effect")
        void testWhen() {
            Effect<Integer, String, Void> effect = Effect.when(
                msg -> msg.startsWith("inc"),
                Effect.modify(s -> s + 1),
                Effect.modify(s -> s - 1)
            );
            
            // Test true condition
            EffectResult<Integer, Void> result1 = effect.run(10, "increment", mockContext);
            assertEquals(11, result1.state());
            
            // Test false condition
            EffectResult<Integer, Void> result2 = effect.run(10, "decrement", mockContext);
            assertEquals(9, result2.state());
        }
    }
    
    // ============================================================================
    // Utility Methods Tests
    // ============================================================================
    
    @Nested
    @DisplayName("Utility Methods")
    class UtilityMethodsTest {
        
        @Test
        @DisplayName("attempt() catches exceptions")
        void testAttempt() {
            Effect<Integer, String, Integer> effect = Effect.attempt(() -> {
                throw new RuntimeException("error");
            });
            
            EffectResult<Integer, Integer> result = effect.run(42, "msg", mockContext);
            
            assertTrue(result.isFailure());
            assertEquals("error", result.error().orElseThrow().getMessage());
        }
        
        @Test
        @DisplayName("attempt() returns value on success")
        void testAttemptSuccess() {
            Effect<Integer, String, Integer> effect = Effect.attempt(() -> 100);
            
            EffectResult<Integer, Integer> result = effect.run(42, "msg", mockContext);
            
            assertTrue(result.isSuccess());
            assertEquals(100, result.value().orElseThrow());
        }
        
        @Test
        @DisplayName("toOptional() returns Optional of result")
        void testToOptional() {
            Effect<Integer, String, Integer> effect = Effect.of(10);
            
            Optional<Integer> optional = effect.toOptional(42, "msg", mockContext);
            
            assertTrue(optional.isPresent());
            assertEquals(10, optional.get());
        }
        
        @Test
        @DisplayName("toOptional() returns empty on failure")
        void testToOptionalOnFailure() {
            Effect<Integer, String, Integer> effect = Effect.fail(new RuntimeException("error"));
            
            Optional<Integer> optional = effect.toOptional(42, "msg", mockContext);
            
            assertTrue(optional.isEmpty());
        }
    }
    
    // ============================================================================
    // Complex Composition Tests
    // ============================================================================
    
    @Nested
    @DisplayName("Complex Compositions")
    class ComplexCompositionTest {
        
        @Test
        @DisplayName("Complex workflow with multiple operations")
        void testComplexWorkflow() {
            List<String> log = new ArrayList<>();
            
            Effect<Integer, String, String> workflow = Effect.<Integer, String, Integer>of(10)
                .tap(n -> log.add("Got value: " + n))
                .map(n -> n * 2)
                .flatMap(n -> Effect.<Integer, String>modify(s -> s + n).andThen(Effect.of("result: " + n)))
                .tapState(s -> log.add("State: " + s))
                .filter(r -> r.contains("result"), "Invalid result")
                .tap(r -> log.add("Final: " + r));
            
            EffectResult<Integer, String> result = workflow.run(5, "msg", mockContext);
            
            assertTrue(result.isSuccess());
            assertEquals(25, result.state()); // 5 + (10 * 2)
            assertEquals("result: 20", result.value().orElseThrow());
            // Should have: "Got value: 10", "State: 25", "Final: result: 20"
            assertEquals(3, log.size());
        }
        
        @Test
        @DisplayName("Error recovery workflow")
        void testErrorRecoveryWorkflow() {
            Effect<Integer, String, String> recovered = Effect.<Integer, String, Integer>attempt(() -> 10 / 0)
                .map(n -> "Success: " + n)
                .recover(error -> "Error: " + error.getClass().getSimpleName())
                .tap(r -> System.out.println("Recovered: " + r));
            
            EffectResult<Integer, String> result = recovered.run(42, "msg", mockContext);
            
            assertTrue(result.isSuccess());
            assertEquals("Error: ArithmeticException", result.value().orElseThrow());
        }
        
        @Test
        @DisplayName("Chaining with state modifications")
        void testChainingWithStateModifications() {
            Effect<Integer, String, Integer> workflow = Effect.<Integer, String>modify(s -> s + 1)
                .andThen(Effect.<Integer, String>modify(s -> s * 2))
                .andThen(Effect.<Integer, String>modify(s -> s - 3))
                .andThen(Effect.state());
            
            EffectResult<Integer, Integer> result = workflow.run(5, "msg", mockContext);
            
            assertTrue(result.isSuccess());
            assertEquals(9, result.state()); // ((5 + 1) * 2) - 3
            assertEquals(9, result.value().orElseThrow());
        }
    }
}
