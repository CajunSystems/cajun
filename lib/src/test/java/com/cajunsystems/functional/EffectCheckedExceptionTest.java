package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Effect monad's error channel with checked exceptions.
 * Verifies that the Error type parameter properly handles checked exceptions.
 */
class EffectCheckedExceptionTest {

    @Mock
    private ActorContext context;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // ============================================================================
    // Checked Exception Tests
    // ============================================================================

    @Test
    void testIOException_canBeHandled() {
        // Effect that fails with IOException
        Effect<Integer, IOException, String> effect = 
            Effect.fail(new IOException("File not found"));
        
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertTrue(result.error().orElseThrow() instanceof IOException);
        assertEquals("File not found", result.error().orElseThrow().getMessage());
    }

    @Test
    void testSQLException_canBeHandled() {
        // Effect that fails with SQLException
        Effect<Integer, SQLException, String> effect = 
            Effect.fail(new SQLException("Connection failed"));
        
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertTrue(result.error().orElseThrow() instanceof SQLException);
        assertEquals("Connection failed", result.error().orElseThrow().getMessage());
    }

    @Test
    void testTimeoutException_canBeHandled() {
        // Effect that fails with TimeoutException
        Effect<Integer, TimeoutException, String> effect = 
            Effect.fail(new TimeoutException("Operation timed out"));
        
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertTrue(result.error().orElseThrow() instanceof TimeoutException);
        assertEquals("Operation timed out", result.error().orElseThrow().getMessage());
    }

    @Test
    void testGenericException_asErrorType() {
        // Effect with generic Exception as error type
        Effect<Integer, Exception, String> effect = 
            Effect.fail(new Exception("Generic error"));
        
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertEquals("Generic error", result.error().orElseThrow().getMessage());
    }

    // ============================================================================
    // Error Recovery with Checked Exceptions
    // ============================================================================

    @Test
    void testRecover_fromIOException() {
        Effect<Integer, IOException, String> effect = 
            Effect.fail(new IOException("Read failed"));
        
        Effect<Integer, IOException, String> recovered = effect.recover(err -> "default-value");
        
        EffectResult<Integer, String> result = recovered.run(0, "test", context);
        
        assertTrue(result.isSuccess());
        assertEquals("default-value", result.value().orElseThrow());
    }

    @Test
    void testRecoverWith_fromSQLException() {
        Effect<Integer, SQLException, String> effect = 
            Effect.fail(new SQLException("Query failed"));
        
        Effect<Integer, SQLException, String> fallback = Effect.of("fallback-result");
        Effect<Integer, SQLException, String> recovered = effect.recoverWith(err -> fallback);
        
        EffectResult<Integer, String> result = recovered.run(0, "test", context);
        
        assertTrue(result.isSuccess());
        assertEquals("fallback-result", result.value().orElseThrow());
    }

    @Test
    void testOrElse_withCheckedException() {
        Effect<Integer, IOException, String> primary = 
            Effect.fail(new IOException("Primary failed"));
        
        Effect<Integer, IOException, String> fallback = Effect.of("fallback");
        
        Effect<Integer, IOException, String> combined = primary.orElse(fallback);
        
        EffectResult<Integer, String> result = combined.run(0, "test", context);
        
        assertTrue(result.isSuccess());
        assertEquals("fallback", result.value().orElseThrow());
    }

    @Test
    void testHandleErrorWith_checkedException() {
        Effect<Integer, Exception, String> effect = 
            Effect.fail(new IOException("Error"));
        
        Effect<Integer, Exception, String> handled = effect.handleErrorWith((err, s, m, c) -> {
            if (err instanceof IOException) {
                return Effect.of("handled-io-error");
            }
            return Effect.of("handled-other-error");
        });
        
        EffectResult<Integer, String> result = handled.run(0, "test", context);
        
        assertTrue(result.isSuccess());
        assertEquals("handled-io-error", result.value().orElseThrow());
    }

    @Test
    void testTapError_withCheckedException() {
        final boolean[] errorLogged = {false};
        
        Effect<Integer, IOException, String> effect = 
            Effect.fail(new IOException("Error"));
        
        Effect<Integer, IOException, String> withTap = effect
            .tapError(err -> errorLogged[0] = true)
            .recover(err -> "recovered");
        
        EffectResult<Integer, String> result = withTap.run(0, "test", context);
        
        assertTrue(errorLogged[0]);
        assertTrue(result.isSuccess());
    }

    // ============================================================================
    // Composition with Checked Exceptions
    // ============================================================================

    @Test
    void testMap_preservesCheckedException() {
        Effect<Integer, IOException, Integer> effect = Effect.of(10);
        
        Effect<Integer, IOException, String> mapped = effect.map(x -> "value: " + x);
        
        EffectResult<Integer, String> result = mapped.run(0, "test", context);
        
        assertTrue(result.isSuccess());
        assertEquals("value: 10", result.value().orElseThrow());
    }

    @Test
    void testFlatMap_withCheckedException() {
        Effect<Integer, IOException, Integer> effect1 = Effect.of(10);
        
        Effect<Integer, IOException, String> effect2 = effect1.flatMap(x -> 
            Effect.of("result: " + x)
        );
        
        EffectResult<Integer, String> result = effect2.run(0, "test", context);
        
        assertTrue(result.isSuccess());
        assertEquals("result: 10", result.value().orElseThrow());
    }

    @Test
    void testFlatMap_propagatesCheckedException() {
        Effect<Integer, IOException, Integer> effect1 = Effect.of(10);
        
        Effect<Integer, IOException, String> effect2 = effect1.flatMap(x -> 
            Effect.fail(new IOException("Flatmap failed"))
        );
        
        EffectResult<Integer, String> result = effect2.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertTrue(result.error().orElseThrow() instanceof IOException);
    }

    @Test
    void testZip_withCheckedException() {
        Effect<Integer, IOException, Integer> effect1 = Effect.of(10);
        Effect<Integer, IOException, Integer> effect2 = Effect.of(20);
        
        Effect<Integer, IOException, Integer> zipped = effect1.zip(effect2, (a, b) -> a + b);
        
        EffectResult<Integer, Integer> result = zipped.run(0, "test", context);
        
        assertTrue(result.isSuccess());
        assertEquals(30, result.value().orElseThrow());
    }

    @Test
    void testZip_propagatesFirstCheckedException() {
        Effect<Integer, IOException, Integer> effect1 = Effect.fail(
            new IOException("First failed"));
        Effect<Integer, IOException, Integer> effect2 = Effect.of(20);
        
        Effect<Integer, IOException, Integer> zipped = effect1.zip(effect2, (a, b) -> a + b);
        
        EffectResult<Integer, Integer> result = zipped.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertEquals("First failed", result.error().orElseThrow().getMessage());
    }

    // ============================================================================
    // Parallel Execution with Checked Exceptions
    // ============================================================================

    @Test
    void testParZip_withCheckedException() {
        Effect<Integer, IOException, Integer> effect1 = Effect.of(10);
        Effect<Integer, IOException, Integer> effect2 = Effect.of(20);
        
        Effect<Integer, IOException, Integer> parallel = effect1.parZip(effect2, (a, b) -> a + b);
        
        EffectResult<Integer, Integer> result = parallel.run(0, "test", context);
        
        assertTrue(result.isSuccess());
        assertEquals(30, result.value().orElseThrow());
    }

    @Test
    void testParZip_propagatesCheckedException() {
        Effect<Integer, IOException, Integer> effect1 = Effect.fail(
            new IOException("Parallel failed"));
        Effect<Integer, IOException, Integer> effect2 = Effect.of(20);
        
        Effect<Integer, IOException, Integer> parallel = effect1.parZip(effect2, (a, b) -> a + b);
        
        EffectResult<Integer, Integer> result = parallel.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertTrue(result.error().orElseThrow() instanceof IOException);
    }

    // ============================================================================
    // Custom Checked Exception Types
    // ============================================================================

    static class CustomCheckedException extends Exception {
        public CustomCheckedException(String message) {
            super(message);
        }
    }

    @Test
    void testCustomCheckedException() {
        Effect<Integer, CustomCheckedException, String> effect = Effect.fail(
            new CustomCheckedException("Custom error"));
        
        EffectResult<Integer, String> result = effect.run(0, "test", context);
        
        assertInstanceOf(EffectResult.Failure.class, result);
        assertTrue(result.error().orElseThrow() instanceof CustomCheckedException);
        assertEquals("Custom error", result.error().orElseThrow().getMessage());
    }

    @Test
    void testCustomCheckedException_recovery() {
        Effect<Integer, CustomCheckedException, String> effect = Effect.fail(
            new CustomCheckedException("Custom error"));
        
        Effect<Integer, CustomCheckedException, String> recovered = effect.recover(err -> 
            "Recovered from: " + err.getMessage()
        );
        
        EffectResult<Integer, String> result = recovered.run(0, "test", context);
        
        assertTrue(result.isSuccess());
        assertEquals("Recovered from: Custom error", result.value().orElseThrow());
    }

    // ============================================================================
    // Multiple Exception Types (using common supertype)
    // ============================================================================

    @Test
    void testMultipleExceptionTypes_usingException() {
        // Can handle both IOException and SQLException using Exception as error type
        
        // Test IOException case - state < 0
        Effect<Integer, Exception, String> ioEffect = Effect.fail(new IOException("IO error"));
        EffectResult<Integer, String> result1 = ioEffect.run(-1, "test", context);
        assertInstanceOf(EffectResult.Failure.class, result1);
        assertTrue(result1.error().orElseThrow() instanceof IOException);
        
        // Test SQLException case - state > 100
        Effect<Integer, Exception, String> sqlEffect = Effect.fail(new SQLException("SQL error"));
        EffectResult<Integer, String> result2 = sqlEffect.run(101, "test", context);
        assertInstanceOf(EffectResult.Failure.class, result2);
        assertTrue(result2.error().orElseThrow() instanceof SQLException);
        
        // Test success case
        Effect<Integer, Exception, String> successEffect = Effect.of("success");
        EffectResult<Integer, String> result3 = successEffect.run(50, "test", context);
        assertTrue(result3.isSuccess());
        assertEquals("success", result3.value().orElseThrow());
    }

    @Test
    void testHandleErrorWith_multipleExceptionTypes() {
        // Test IOException handling
        Effect<Integer, Exception, String> ioEffect = Effect.fail(new IOException("IO error"));
        Effect<Integer, Exception, String> handledIo = ioEffect.handleErrorWith((err, s, m, c) -> {
            if (err instanceof IOException) {
                return Effect.of("handled-io");
            } else if (err instanceof SQLException) {
                return Effect.of("handled-sql");
            }
            return Effect.of("handled-other");
        });
        
        EffectResult<Integer, String> result1 = handledIo.run(-1, "test", context);
        assertEquals("handled-io", result1.value().orElseThrow());
        
        // Test SQLException handling
        Effect<Integer, Exception, String> sqlEffect = Effect.fail(new SQLException("SQL error"));
        Effect<Integer, Exception, String> handledSql = sqlEffect.handleErrorWith((err, s, m, c) -> {
            if (err instanceof IOException) {
                return Effect.of("handled-io");
            } else if (err instanceof SQLException) {
                return Effect.of("handled-sql");
            }
            return Effect.of("handled-other");
        });
        
        EffectResult<Integer, String> result2 = handledSql.run(1, "test", context);
        assertEquals("handled-sql", result2.value().orElseThrow());
    }
}
