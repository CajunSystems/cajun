package com.cajunsystems.functional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for EffectResult.
 * Tests all result types, transformations, and utility methods.
 */
@DisplayName("EffectResult Tests")
class EffectResultTest {
    
    // ============================================================================
    // Factory Methods Tests
    // ============================================================================
    
    @Nested
    @DisplayName("Factory Methods")
    class FactoryMethodsTest {
        
        @Test
        @DisplayName("success() creates Success result")
        void testSuccess() {
            EffectResult<Integer, String> result = EffectResult.success(42, "value");
            
            assertTrue(result.isSuccess());
            assertTrue(result.hasValue());
            assertFalse(result.isFailure());
            assertEquals(42, result.state());
            assertEquals("value", result.value().orElseThrow());
        }
        
        @Test
        @DisplayName("noResult() creates NoResult")
        void testNoResult() {
            EffectResult<Integer, String> result = EffectResult.noResult(42);
            
            assertFalse(result.isSuccess());
            assertFalse(result.hasValue());
            assertFalse(result.isFailure());
            assertEquals(42, result.state());
            assertTrue(result.value().isEmpty());
        }
        
        @Test
        @DisplayName("failure() creates Failure result")
        void testFailure() {
            RuntimeException error = new RuntimeException("test error");
            EffectResult<Integer, String> result = EffectResult.failure(42, error);
            
            assertFalse(result.isSuccess());
            assertFalse(result.hasValue());
            assertTrue(result.isFailure());
            assertEquals(42, result.state());
            assertEquals(error, result.error().orElseThrow());
        }
    }
    
    // ============================================================================
    // Query Methods Tests
    // ============================================================================
    
    @Nested
    @DisplayName("Query Methods")
    class QueryMethodsTest {
        
        @Test
        @DisplayName("value() returns Optional with value for Success")
        void testValueOnSuccess() {
            EffectResult<Integer, String> result = EffectResult.success(42, "value");
            
            Optional<String> value = result.value();
            assertTrue(value.isPresent());
            assertEquals("value", value.get());
        }
        
        @Test
        @DisplayName("value() returns empty Optional for NoResult")
        void testValueOnNoResult() {
            EffectResult<Integer, String> result = EffectResult.noResult(42);
            
            Optional<String> value = result.value();
            assertTrue(value.isEmpty());
        }
        
        @Test
        @DisplayName("value() returns empty Optional for Failure")
        void testValueOnFailure() {
            EffectResult<Integer, String> result = EffectResult.failure(42, new RuntimeException());
            
            Optional<String> value = result.value();
            assertTrue(value.isEmpty());
        }
        
        @Test
        @DisplayName("error() returns Optional with error for Failure")
        void testErrorOnFailure() {
            RuntimeException error = new RuntimeException("test");
            EffectResult<Integer, String> result = EffectResult.failure(42, error);
            
            Optional<Throwable> errorOpt = result.error();
            assertTrue(errorOpt.isPresent());
            assertEquals(error, errorOpt.get());
        }
        
        @Test
        @DisplayName("error() returns empty Optional for Success")
        void testErrorOnSuccess() {
            EffectResult<Integer, String> result = EffectResult.success(42, "value");
            
            Optional<Throwable> error = result.error();
            assertTrue(error.isEmpty());
        }
    }
    
    // ============================================================================
    // Transformation Methods Tests
    // ============================================================================
    
    @Nested
    @DisplayName("Transformation Methods")
    class TransformationMethodsTest {
        
        @Test
        @DisplayName("map() transforms Success value")
        void testMapSuccess() {
            EffectResult<Integer, Integer> result = EffectResult.success(42, 10);
            EffectResult<Integer, String> mapped = result.map(n -> "Count: " + n);
            
            assertTrue(mapped.isSuccess());
            assertEquals("Count: 10", mapped.value().orElseThrow());
        }
        
        @Test
        @DisplayName("map() preserves NoResult")
        void testMapNoResult() {
            EffectResult<Integer, Integer> result = EffectResult.noResult(42);
            EffectResult<Integer, String> mapped = result.map(n -> "Count: " + n);
            
            assertFalse(mapped.hasValue());
            assertEquals(42, mapped.state());
        }
        
        @Test
        @DisplayName("map() preserves Failure")
        void testMapFailure() {
            RuntimeException error = new RuntimeException("test");
            EffectResult<Integer, Integer> result = EffectResult.failure(42, error);
            EffectResult<Integer, String> mapped = result.map(n -> "Count: " + n);
            
            assertTrue(mapped.isFailure());
            assertEquals(error, mapped.error().orElseThrow());
        }
        
        @Test
        @DisplayName("mapState() transforms state")
        void testMapState() {
            EffectResult<Integer, String> result = EffectResult.success(42, "value");
            EffectResult<String, String> mapped = result.mapState(n -> "State: " + n);
            
            assertTrue(mapped.isSuccess());
            assertEquals("State: 42", mapped.state());
            assertEquals("value", mapped.value().orElseThrow());
        }
        
        @Test
        @DisplayName("recover() transforms Failure to Success")
        void testRecover() {
            EffectResult<Integer, String> result = EffectResult.failure(42, new RuntimeException("error"));
            EffectResult<Integer, String> recovered = result.recover(error -> "Recovered: " + error.getMessage());
            
            assertTrue(recovered.isSuccess());
            assertEquals("Recovered: error", recovered.value().orElseThrow());
        }
        
        @Test
        @DisplayName("recover() preserves Success")
        void testRecoverPreservesSuccess() {
            EffectResult<Integer, String> result = EffectResult.success(42, "value");
            EffectResult<Integer, String> recovered = result.recover(error -> "Recovered");
            
            assertTrue(recovered.isSuccess());
            assertEquals("value", recovered.value().orElseThrow());
        }
    }
    
    // ============================================================================
    // Side Effect Methods Tests
    // ============================================================================
    
    @Nested
    @DisplayName("Side Effect Methods")
    class SideEffectMethodsTest {
        
        @Test
        @DisplayName("ifSuccess() executes action on Success")
        void testIfSuccessOnSuccess() {
            AtomicReference<String> captured = new AtomicReference<>();
            EffectResult<Integer, String> result = EffectResult.success(42, "value");
            
            EffectResult<Integer, String> returned = result.ifSuccess(captured::set);
            
            assertEquals("value", captured.get());
            assertSame(result, returned); // Returns same instance
        }
        
        @Test
        @DisplayName("ifSuccess() skips action on Failure")
        void testIfSuccessOnFailure() {
            AtomicInteger callCount = new AtomicInteger(0);
            EffectResult<Integer, String> result = EffectResult.failure(42, new RuntimeException());
            
            result.ifSuccess(v -> callCount.incrementAndGet());
            
            assertEquals(0, callCount.get());
        }
        
        @Test
        @DisplayName("ifFailure() executes action on Failure")
        void testIfFailureOnFailure() {
            AtomicReference<Throwable> captured = new AtomicReference<>();
            RuntimeException error = new RuntimeException("test");
            EffectResult<Integer, String> result = EffectResult.failure(42, error);
            
            EffectResult<Integer, String> returned = result.ifFailure(captured::set);
            
            assertEquals(error, captured.get());
            assertSame(result, returned);
        }
        
        @Test
        @DisplayName("ifFailure() skips action on Success")
        void testIfFailureOnSuccess() {
            AtomicInteger callCount = new AtomicInteger(0);
            EffectResult<Integer, String> result = EffectResult.success(42, "value");
            
            result.ifFailure(e -> callCount.incrementAndGet());
            
            assertEquals(0, callCount.get());
        }
        
        @Test
        @DisplayName("ifState() always executes action")
        void testIfState() {
            AtomicReference<Integer> captured = new AtomicReference<>();
            EffectResult<Integer, String> result = EffectResult.success(42, "value");
            
            result.ifState(captured::set);
            
            assertEquals(42, captured.get());
        }
    }
    
    // ============================================================================
    // Pattern Matching Tests
    // ============================================================================
    
    @Nested
    @DisplayName("Pattern Matching")
    class PatternMatchingTest {
        
        @Test
        @DisplayName("fold() handles Success case")
        void testFoldSuccess() {
            EffectResult<Integer, String> result = EffectResult.success(42, "value");
            
            String folded = result.fold(
                value -> "Success: " + value,
                state -> "NoResult: " + state,
                error -> "Error: " + error.getMessage()
            );
            
            assertEquals("Success: value", folded);
        }
        
        @Test
        @DisplayName("fold() handles NoResult case")
        void testFoldNoResult() {
            EffectResult<Integer, String> result = EffectResult.noResult(42);
            
            String folded = result.fold(
                value -> "Success: " + value,
                state -> "NoResult: " + state,
                error -> "Error: " + error.getMessage()
            );
            
            assertEquals("NoResult: 42", folded);
        }
        
        @Test
        @DisplayName("fold() handles Failure case")
        void testFoldFailure() {
            EffectResult<Integer, String> result = EffectResult.failure(42, new RuntimeException("test"));
            
            String folded = result.fold(
                value -> "Success: " + value,
                state -> "NoResult: " + state,
                error -> "Error: " + error.getMessage()
            );
            
            assertEquals("Error: test", folded);
        }
        
        @Test
        @DisplayName("getOrThrow() returns value on Success")
        void testGetOrThrowSuccess() throws Throwable {
            EffectResult<Integer, String> result = EffectResult.success(42, "value");
            
            String value = result.getOrThrow();
            
            assertEquals("value", value);
        }
        
        @Test
        @DisplayName("getOrThrow() throws on Failure")
        void testGetOrThrowFailure() {
            RuntimeException error = new RuntimeException("test");
            EffectResult<Integer, String> result = EffectResult.failure(42, error);
            
            Throwable thrown = assertThrows(RuntimeException.class, result::getOrThrow);
            
            assertEquals(error, thrown);
        }
        
        @Test
        @DisplayName("getOrThrow() throws on NoResult")
        void testGetOrThrowNoResult() {
            EffectResult<Integer, String> result = EffectResult.noResult(42);
            
            assertThrows(IllegalStateException.class, result::getOrThrow);
        }
        
        @Test
        @DisplayName("getOrElse() returns value on Success")
        void testGetOrElseSuccess() {
            EffectResult<Integer, String> result = EffectResult.success(42, "value");
            
            String value = result.getOrElse("default");
            
            assertEquals("value", value);
        }
        
        @Test
        @DisplayName("getOrElse() returns default on Failure")
        void testGetOrElseFailure() {
            EffectResult<Integer, String> result = EffectResult.failure(42, new RuntimeException());
            
            String value = result.getOrElse("default");
            
            assertEquals("default", value);
        }
        
        @Test
        @DisplayName("getOrElse() returns default on NoResult")
        void testGetOrElseNoResult() {
            EffectResult<Integer, String> result = EffectResult.noResult(42);
            
            String value = result.getOrElse("default");
            
            assertEquals("default", value);
        }
    }
    
    // ============================================================================
    // Debug String Tests
    // ============================================================================
    
    @Nested
    @DisplayName("Debug String")
    class DebugStringTest {
        
        @Test
        @DisplayName("toDebugString() formats Success")
        void testDebugStringSuccess() {
            EffectResult<Integer, String> result = EffectResult.success(42, "value");
            
            String debug = result.toDebugString();
            
            assertTrue(debug.contains("Success"));
            assertTrue(debug.contains("42"));
            assertTrue(debug.contains("value"));
        }
        
        @Test
        @DisplayName("toDebugString() formats NoResult")
        void testDebugStringNoResult() {
            EffectResult<Integer, String> result = EffectResult.noResult(42);
            
            String debug = result.toDebugString();
            
            assertTrue(debug.contains("NoResult"));
            assertTrue(debug.contains("42"));
        }
        
        @Test
        @DisplayName("toDebugString() formats Failure")
        void testDebugStringFailure() {
            EffectResult<Integer, String> result = EffectResult.failure(42, new RuntimeException("test"));
            
            String debug = result.toDebugString();
            
            assertTrue(debug.contains("Failure"));
            assertTrue(debug.contains("42"));
            assertTrue(debug.contains("test"));
        }
    }
    
    // ============================================================================
    // Chaining Tests
    // ============================================================================
    
    @Nested
    @DisplayName("Chaining Operations")
    class ChainingTest {
        
        @Test
        @DisplayName("Chain multiple transformations")
        void testChaining() {
            EffectResult<Integer, Integer> result = EffectResult.success(10, 5);
            
            EffectResult<String, String> transformed = result
                .map(n -> n * 2)
                .mapState(s -> s + 100)
                .map(n -> "Result: " + n)
                .mapState(s -> "State: " + s);
            
            assertTrue(transformed.isSuccess());
            assertEquals("State: 110", transformed.state());
            assertEquals("Result: 10", transformed.value().orElseThrow());
        }
        
        @Test
        @DisplayName("Chain with recovery")
        void testChainingWithRecovery() {
            EffectResult<Integer, String> result = EffectResult.<Integer, String>failure(42, new RuntimeException("error"))
                .recover(e -> "Recovered from: " + e.getMessage())
                .map(s -> s.toUpperCase())
                .mapState(n -> n + 10);
            
            assertTrue(result.isSuccess());
            assertEquals(52, result.state());
            assertEquals("RECOVERED FROM: ERROR", result.value().orElseThrow());
        }
        
        @Test
        @DisplayName("Chain with side effects")
        void testChainingWithSideEffects() {
            AtomicInteger stateCount = new AtomicInteger(0);
            AtomicInteger valueCount = new AtomicInteger(0);
            
            EffectResult<Integer, String> result = EffectResult.success(42, "value")
                .ifState(s -> stateCount.incrementAndGet())
                .ifSuccess(v -> valueCount.incrementAndGet())
                .map(String::toUpperCase)
                .ifSuccess(v -> valueCount.incrementAndGet());
            
            assertEquals(1, stateCount.get());
            assertEquals(2, valueCount.get());
            assertEquals("VALUE", result.value().orElseThrow());
        }
    }
}
