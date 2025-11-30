package com.cajunsystems.builder;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.handler.Handler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for all built-in IdStrategy implementations.
 * Tests ID format validation, uniqueness, and counter behavior.
 */
class IdStrategyTest {

    private ActorSystem system;
    private IdStrategy.IdGenerationContext context;

    // Test handler classes for different scenarios
    public static class TestHandler implements Handler<String> {
        @Override
        public void receive(String message, com.cajunsystems.ActorContext context) {}
    }

    public static class UserHandler implements Handler<String> {
        @Override
        public void receive(String message, com.cajunsystems.ActorContext context) {}
    }

    public static class OrderProcessingHandler implements Handler<String> {
        @Override
        public void receive(String message, com.cajunsystems.ActorContext context) {}
    }

    @BeforeEach
    void setUp() {
        system = new ActorSystem();
        context = new IdStrategy.IdGenerationContext(system, TestHandler.class, null);
    }

    @Nested
    class UUIDStrategyTest {
        
        @Test
        void shouldGenerateValidUUIDFormat() {
            String id = IdStrategy.UUID.generateId(context);
            
            // Should be a valid UUID format
            assertDoesNotThrow(() -> UUID.fromString(id));
            assertEquals(36, id.length()); // Standard UUID length
            assertTrue(id.contains("-"));
        }

        @Test
        void shouldGenerateUniqueIds() {
            Set<String> ids = new HashSet<>();
            
            for (int i = 0; i < 100; i++) {
                String id = IdStrategy.UUID.generateId(context);
                assertTrue(ids.add(id), "Generated ID should be unique: " + id);
            }
        }

        @Test
        void shouldIgnoreHandlerClassAndParent() {
            IdStrategy.IdGenerationContext context2 = 
                new IdStrategy.IdGenerationContext(system, UserHandler.class, "parent123");
            
            String id1 = IdStrategy.UUID.generateId(context);
            String id2 = IdStrategy.UUID.generateId(context2);
            
            assertNotEquals(id1, id2);
            // Both should be valid UUIDs
            assertDoesNotThrow(() -> UUID.fromString(id1));
            assertDoesNotThrow(() -> UUID.fromString(id2));
        }
    }

    @Nested
    class ClassBasedSequentialStrategyTest {
        
        @Test
        void shouldGenerateClassBasedSequentialIds() {
            String id = IdStrategy.CLASS_BASED_SEQUENTIAL.generateId(context);
            assertTrue(id.startsWith("test:"));
            assertTrue(id.matches("test:\\d+"));
        }

        @Test
        void shouldIncrementCounterForSameClass() {
            String id1 = IdStrategy.CLASS_BASED_SEQUENTIAL.generateId(context);
            String id2 = IdStrategy.CLASS_BASED_SEQUENTIAL.generateId(context);
            String id3 = IdStrategy.CLASS_BASED_SEQUENTIAL.generateId(context);
            
            // Extract sequence numbers and verify they increment using dynamic split
            long seq1 = Long.parseLong(id1.split(":")[1]);
            long seq2 = Long.parseLong(id2.split(":")[1]);
            long seq3 = Long.parseLong(id3.split(":")[1]);
            
            assertEquals(seq1 + 1, seq2);
            assertEquals(seq2 + 1, seq3);
        }

        @Test
        void shouldUseSeparateCountersForDifferentClasses() {
            IdStrategy.IdGenerationContext userContext = 
                new IdStrategy.IdGenerationContext(system, UserHandler.class, null);
            IdStrategy.IdGenerationContext orderContext = 
                new IdStrategy.IdGenerationContext(system, OrderProcessingHandler.class, null);

            String testId1 = IdStrategy.CLASS_BASED_SEQUENTIAL.generateId(context);
            String userId1 = IdStrategy.CLASS_BASED_SEQUENTIAL.generateId(userContext);
            String orderId1 = IdStrategy.CLASS_BASED_SEQUENTIAL.generateId(orderContext);
            
            // Each should start with their own counter
            assertTrue(testId1.startsWith("test:"));
            assertTrue(userId1.startsWith("user:"));
            assertTrue(orderId1.startsWith("orderprocessing:"));
            
            // Second set should increment each counter independently
            String testId2 = IdStrategy.CLASS_BASED_SEQUENTIAL.generateId(context);
            String userId2 = IdStrategy.CLASS_BASED_SEQUENTIAL.generateId(userContext);
            String orderId2 = IdStrategy.CLASS_BASED_SEQUENTIAL.generateId(orderContext);
            
            // Extract sequence numbers using dynamic split approach
            long testSeq1 = Long.parseLong(testId1.split(":")[1]);
            long testSeq2 = Long.parseLong(testId2.split(":")[1]);
            long userSeq1 = Long.parseLong(userId1.split(":")[1]);
            long userSeq2 = Long.parseLong(userId2.split(":")[1]);
            long orderSeq1 = Long.parseLong(orderId1.split(":")[1]);
            long orderSeq2 = Long.parseLong(orderId2.split(":")[1]);
            
            assertEquals(testSeq1 + 1, testSeq2);
            assertEquals(userSeq1 + 1, userSeq2);
            assertEquals(orderSeq1 + 1, orderSeq2);
        }

        @Test
        void shouldStripHandlerSuffix() {
            assertEquals("test", IdStrategy.extractBaseName(TestHandler.class));
            assertEquals("user", IdStrategy.extractBaseName(UserHandler.class));
            assertEquals("orderprocessing", IdStrategy.extractBaseName(OrderProcessingHandler.class));
        }

        @Test
        void shouldConvertToLowerCase() {
            assertEquals("test", IdStrategy.extractBaseName(TestHandler.class));
            assertTrue(IdStrategy.extractBaseName(UserHandler.class).equals("user"));
        }

        @Test
        void shouldGenerateUniqueIds() {
            Set<String> ids = new HashSet<>();
            
            for (int i = 0; i < 100; i++) {
                String id = IdStrategy.CLASS_BASED_SEQUENTIAL.generateId(context);
                assertTrue(ids.add(id), "Generated ID should be unique: " + id);
            }
        }
    }

    @Nested
    class ClassBasedUUIDStrategyTest {
        
        @Test
        void shouldGenerateClassBasedUUIDIds() {
            String id = IdStrategy.CLASS_BASED_UUID.generateId(context);
            
            assertTrue(id.startsWith("test:"));
            assertEquals(41, id.length()); // "test:" + 36 char UUID
        }

        @Test
        void shouldGenerateUniqueIds() {
            Set<String> ids = new HashSet<>();
            
            for (int i = 0; i < 50; i++) {
                String id = IdStrategy.CLASS_BASED_UUID.generateId(context);
                assertTrue(ids.add(id), "Generated ID should be unique: " + id);
            }
        }

        @Test
        void shouldUseDifferentClassPrefixes() {
            IdStrategy.IdGenerationContext userContext = 
                new IdStrategy.IdGenerationContext(system, UserHandler.class, null);
            
            String testId = IdStrategy.CLASS_BASED_UUID.generateId(context);
            String userId = IdStrategy.CLASS_BASED_UUID.generateId(userContext);
            
            assertTrue(testId.startsWith("test:"));
            assertTrue(userId.startsWith("user:"));
            assertNotEquals(testId, userId);
            
            // UUID parts should be valid
            String testUuid = testId.split(":")[1];
            String userUuid = userId.split(":")[1];
            assertDoesNotThrow(() -> UUID.fromString(testUuid));
            assertDoesNotThrow(() -> UUID.fromString(userUuid));
        }
    }

    @Nested
    class ClassBasedShortUUIDStrategyTest {
        
        @Test
        void shouldGenerateClassBasedShortUUIDIds() {
            String id = IdStrategy.CLASS_BASED_SHORT_UUID.generateId(context);
            
            assertTrue(id.startsWith("test:"));
            assertEquals(13, id.length()); // "test:" + 8 char short UUID
        }

        @Test
        void shouldGenerateUniqueIds() {
            Set<String> ids = new HashSet<>();
            
            for (int i = 0; i < 100; i++) {
                String id = IdStrategy.CLASS_BASED_SHORT_UUID.generateId(context);
                assertTrue(ids.add(id), "Generated ID should be unique: " + id);
            }
        }

        @Test
        void shouldUseDifferentClassPrefixes() {
            IdStrategy.IdGenerationContext userContext = 
                new IdStrategy.IdGenerationContext(system, UserHandler.class, null);
            
            String testId = IdStrategy.CLASS_BASED_SHORT_UUID.generateId(context);
            String userId = IdStrategy.CLASS_BASED_SHORT_UUID.generateId(userContext);
            
            assertTrue(testId.startsWith("test:"));
            assertTrue(userId.startsWith("user:"));
            assertNotEquals(testId, userId);
            
            // Short UUID parts should be 8 characters
            assertEquals(8, testId.split(":")[1].length());
            assertEquals(8, userId.split(":")[1].length());
        }
    }

    @Nested
    class ClassBasedTimestampStrategyTest {
        
        @Test
        void shouldGenerateClassBasedTimestampIds() {
            String id = IdStrategy.CLASS_BASED_TIMESTAMP.generateId(context);
            
            assertTrue(id.startsWith("test:"));
            
            // Extract timestamp part and validate it's a number
            String timestampStr = id.split(":")[1];
            long timestamp = Long.parseLong(timestampStr);
            
            // Should be recent (within last 10 seconds)
            long now = System.currentTimeMillis();
            assertTrue(now - timestamp < 10000, "Timestamp should be recent: " + timestamp);
        }

        @Test
        void shouldGenerateDifferentIdsOverTime() throws InterruptedException {
            String id1 = IdStrategy.CLASS_BASED_TIMESTAMP.generateId(context);
            Thread.sleep(1); // Ensure different timestamp
            String id2 = IdStrategy.CLASS_BASED_TIMESTAMP.generateId(context);
            
            assertNotEquals(id1, id2);
            
            // Extract timestamps and validate they're different
            long timestamp1 = Long.parseLong(id1.split(":")[1]);
            long timestamp2 = Long.parseLong(id2.split(":")[1]);
            assertTrue(timestamp2 > timestamp1);
        }

        @Test
        void shouldUseDifferentClassPrefixes() {
            IdStrategy.IdGenerationContext userContext = 
                new IdStrategy.IdGenerationContext(system, UserHandler.class, null);
            
            String testId = IdStrategy.CLASS_BASED_TIMESTAMP.generateId(context);
            String userId = IdStrategy.CLASS_BASED_TIMESTAMP.generateId(userContext);
            
            assertTrue(testId.startsWith("test:"));
            assertTrue(userId.startsWith("user:"));
            assertNotEquals(testId, userId);
        }
    }

    @Nested
    class ClassBasedNanoStrategyTest {
        
        @Test
        void shouldGenerateClassBasedNanoIds() {
            String id = IdStrategy.CLASS_BASED_NANO.generateId(context);
            
            assertTrue(id.startsWith("test:"));
            
            // Extract nano part and validate it's a number
            String nanoStr = id.split(":")[1];
            long nano = Long.parseLong(nanoStr);
            
            // Should be recent (within last second)
            long now = System.nanoTime();
            assertTrue(now - nano < 1_000_000_000L, "Nano time should be recent: " + nano);
        }

        @Test
        void shouldGenerateUniqueIds() {
            Set<String> ids = new HashSet<>();
            
            for (int i = 0; i < 50; i++) {
                String id = IdStrategy.CLASS_BASED_NANO.generateId(context);
                assertTrue(ids.add(id), "Generated ID should be unique: " + id);
            }
        }

        @Test
        void shouldUseDifferentClassPrefixes() {
            IdStrategy.IdGenerationContext userContext = 
                new IdStrategy.IdGenerationContext(system, UserHandler.class, null);
            
            String testId = IdStrategy.CLASS_BASED_NANO.generateId(context);
            String userId = IdStrategy.CLASS_BASED_NANO.generateId(userContext);
            
            assertTrue(testId.startsWith("test:"));
            assertTrue(userId.startsWith("user:"));
            assertNotEquals(testId, userId);
        }
    }

    @Nested
    class SequentialStrategyTest {
        
        @Test
        void shouldGenerateSequentialIds() {
            String id1 = IdStrategy.SEQUENTIAL.generateId(context);
            String id2 = IdStrategy.SEQUENTIAL.generateId(context);
            String id3 = IdStrategy.SEQUENTIAL.generateId(context);
            
            // Test that they increment sequentially (not absolute values)
            long seq1 = Long.parseLong(id1);
            long seq2 = Long.parseLong(id2);
            long seq3 = Long.parseLong(id3);
            
            assertEquals(seq1 + 1, seq2);
            assertEquals(seq2 + 1, seq3);
        }

        @Test
        void shouldIgnoreHandlerClass() {
            IdStrategy.IdGenerationContext userContext = 
                new IdStrategy.IdGenerationContext(system, UserHandler.class, null);
            
            String id1 = IdStrategy.SEQUENTIAL.generateId(context);
            String id2 = IdStrategy.SEQUENTIAL.generateId(userContext);
            String id3 = IdStrategy.SEQUENTIAL.generateId(context);
            
            // Test that they increment sequentially regardless of handler class
            long seq1 = Long.parseLong(id1);
            long seq2 = Long.parseLong(id2);
            long seq3 = Long.parseLong(id3);
            
            assertEquals(seq1 + 1, seq2);
            assertEquals(seq2 + 1, seq3);
        }

        @Test
        void shouldIgnoreParentId() {
            IdStrategy.IdGenerationContext contextWithParent = 
                new IdStrategy.IdGenerationContext(system, TestHandler.class, "parent123");
            
            String id1 = IdStrategy.SEQUENTIAL.generateId(context);
            String id2 = IdStrategy.SEQUENTIAL.generateId(contextWithParent);
            String id3 = IdStrategy.SEQUENTIAL.generateId(context);
            
            // Test that they increment sequentially regardless of parent ID
            long seq1 = Long.parseLong(id1);
            long seq2 = Long.parseLong(id2);
            long seq3 = Long.parseLong(id3);
            
            assertEquals(seq1 + 1, seq2);
            assertEquals(seq2 + 1, seq3);
        }
    }

    @Nested
    class EdgeCasesTest {
        
        @Test
        void shouldHandleNullActorSystem() {
            IdStrategy.IdGenerationContext nullSystemContext = 
                new IdStrategy.IdGenerationContext(null, TestHandler.class, null);
            
            // All strategies should handle null system gracefully
            assertDoesNotThrow(() -> IdStrategy.UUID.generateId(nullSystemContext));
            assertDoesNotThrow(() -> IdStrategy.CLASS_BASED_SEQUENTIAL.generateId(nullSystemContext));
            assertDoesNotThrow(() -> IdStrategy.CLASS_BASED_UUID.generateId(nullSystemContext));
            assertDoesNotThrow(() -> IdStrategy.CLASS_BASED_SHORT_UUID.generateId(nullSystemContext));
            assertDoesNotThrow(() -> IdStrategy.CLASS_BASED_TIMESTAMP.generateId(nullSystemContext));
            assertDoesNotThrow(() -> IdStrategy.CLASS_BASED_NANO.generateId(nullSystemContext));
            assertDoesNotThrow(() -> IdStrategy.SEQUENTIAL.generateId(nullSystemContext));
        }

        @Test
        void shouldHandleNullParentId() {
            IdStrategy.IdGenerationContext nullParentContext = 
                new IdStrategy.IdGenerationContext(system, TestHandler.class, null);
            
            // All strategies should handle null parent gracefully
            assertDoesNotThrow(() -> IdStrategy.UUID.generateId(nullParentContext));
            assertDoesNotThrow(() -> IdStrategy.CLASS_BASED_SEQUENTIAL.generateId(nullParentContext));
            assertDoesNotThrow(() -> IdStrategy.CLASS_BASED_UUID.generateId(nullParentContext));
            assertDoesNotThrow(() -> IdStrategy.CLASS_BASED_SHORT_UUID.generateId(nullParentContext));
            assertDoesNotThrow(() -> IdStrategy.CLASS_BASED_TIMESTAMP.generateId(nullParentContext));
            assertDoesNotThrow(() -> IdStrategy.CLASS_BASED_NANO.generateId(nullParentContext));
            assertDoesNotThrow(() -> IdStrategy.SEQUENTIAL.generateId(nullParentContext));
        }
    }
}
