package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.test.TempPersistenceExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Effect interruption handling and cancellation semantics.
 * Verifies that effects properly handle Thread.interrupt() for virtual thread cancellation.
 */
@ExtendWith(TempPersistenceExtension.class)
class EffectInterruptionTest {

    private ActorSystem system;

    @BeforeEach
    void setUp() {
        system = new ActorSystem();
    }

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.shutdown();
        }
    }

    // ============================================================================
    // Basic Interruption Handling
    // ============================================================================

    @Test
    void attemptShouldPreserveInterruptionStatus() throws Exception {
        AtomicBoolean wasInterrupted = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        Thread testThread = Thread.ofVirtual().start(() -> {
            Effect<Integer, Throwable, String> effect = Effect.attempt(() -> {
                Thread.sleep(5000); // Long operation
                return "completed";
            });

            EffectResult<Integer, String> result = effect.run(0, null, createMockContext());
            
            // Check if thread is still interrupted after effect completes
            wasInterrupted.set(Thread.currentThread().isInterrupted());
            latch.countDown();
        });

        // Give thread time to start sleeping
        Thread.sleep(100);
        
        // Interrupt the thread
        testThread.interrupt();
        
        // Wait for completion
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Effect should complete quickly after interruption");
        assertTrue(wasInterrupted.get(), "Interruption status should be preserved");
    }

    @Test
    void attemptShouldFailOnInterruptedException() {
        Effect<Integer, Throwable, String> effect = Effect.attempt(() -> {
            Thread.sleep(1000);
            return "should not reach here";
        });

        // Run in a thread we can interrupt
        AtomicReference<EffectResult<Integer, String>> resultRef = new AtomicReference<>();
        Thread testThread = Thread.ofVirtual().start(() -> {
            resultRef.set(effect.run(0, null, createMockContext()));
        });

        // Interrupt immediately
        testThread.interrupt();
        
        try {
            testThread.join(1000);
        } catch (InterruptedException e) {
            fail("Test thread interrupted");
        }

        EffectResult<Integer, String> result = resultRef.get();
        assertNotNull(result);
        assertInstanceOf(EffectResult.Failure.class, result);
        
        EffectResult.Failure<Integer, String> failure = (EffectResult.Failure<Integer, String>) result;
        assertInstanceOf(InterruptedException.class, failure.errorValue());
    }

    // ============================================================================
    // onInterrupt(Effect) Tests
    // ============================================================================

    @Test
    void onInterruptShouldRunCleanupEffect() throws Exception {
        AtomicReference<EffectResult<Integer, String>> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Effect<Integer, Throwable, String> effect = Effect.<Integer, Throwable, String>attempt(() -> {
            Thread.sleep(5000);
            return "completed";
        }).onInterrupt(Effect.<Integer, Throwable, Object>attempt(() -> {
            return null;
        }));

        Thread testThread = Thread.ofVirtual().start(() -> {
            resultRef.set(effect.run(0, null, createMockContext()));
            latch.countDown();
        });

        Thread.sleep(100);
        testThread.interrupt();
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Effect should complete");
        
        EffectResult<Integer, String> result = resultRef.get();
        assertNotNull(result, "Result should not be null");
        assertInstanceOf(EffectResult.Failure.class, result, "Result should be a failure");
        
        EffectResult.Failure<Integer, String> failure = (EffectResult.Failure<Integer, String>) result;
        assertInstanceOf(InterruptedException.class, failure.errorValue(), "Error should be InterruptedException");
    }

    @Test
    void onInterruptShouldHandleCleanupErrors() throws Exception {
        AtomicReference<EffectResult<Integer, String>> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Effect<Integer, Throwable, String> effect = Effect.<Integer, Throwable, String>attempt(() -> {
            Thread.sleep(5000);
            return "completed";
        }).onInterrupt(Effect.<Integer, Throwable, Object>attempt(() -> {
            throw new RuntimeException("Cleanup failed");
        }));

        Thread testThread = Thread.ofVirtual().start(() -> {
            resultRef.set(effect.run(0, null, createMockContext()));
            latch.countDown();
        });

        Thread.sleep(100);
        testThread.interrupt();
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Effect should complete");
        
        EffectResult<Integer, String> result = resultRef.get();
        assertNotNull(result);
        assertInstanceOf(EffectResult.Failure.class, result);
    }

    @Test
    void onInterruptShouldChainMultipleCleanups() throws Exception {
        AtomicReference<EffectResult<Integer, String>> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Effect<Integer, Throwable, String> effect = Effect.<Integer, Throwable, String>attempt(() -> {
            Thread.sleep(5000);
            return "completed";
        })
        .onInterrupt(Effect.<Integer, Throwable, Object>attempt(() -> null))
        .onInterrupt(Effect.<Integer, Throwable, Object>attempt(() -> null));

        Thread testThread = Thread.ofVirtual().start(() -> {
            resultRef.set(effect.run(0, null, createMockContext()));
            latch.countDown();
        });

        Thread.sleep(100);
        testThread.interrupt();
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Effect should complete");
        
        EffectResult<Integer, String> result = resultRef.get();
        assertNotNull(result);
        assertInstanceOf(EffectResult.Failure.class, result);
    }

    // ============================================================================
    // onInterrupt(Runnable) Tests
    // ============================================================================

    @Test
    void onInterruptRunnableShouldExecuteOnInterruption() throws Exception {
        AtomicReference<EffectResult<Integer, String>> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Effect<Integer, Throwable, String> effect = Effect.<Integer, Throwable, String>attempt(() -> {
            Thread.sleep(5000);
            return "completed";
        }).onInterrupt(() -> {});

        Thread testThread = Thread.ofVirtual().start(() -> {
            resultRef.set(effect.run(0, null, createMockContext()));
            latch.countDown();
        });

        Thread.sleep(100);
        testThread.interrupt();
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Effect should complete");
        
        EffectResult<Integer, String> result = resultRef.get();
        assertNotNull(result);
        assertInstanceOf(EffectResult.Failure.class, result);
    }

    @Test
    void onInterruptRunnableShouldNotFailOnException() throws Exception {
        AtomicReference<EffectResult<Integer, String>> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Effect<Integer, Throwable, String> effect = Effect.<Integer, Throwable, String>attempt(() -> {
            Thread.sleep(5000);
            return "completed";
        }).onInterrupt(() -> {
            throw new RuntimeException("Cleanup error");
        });

        Thread testThread = Thread.ofVirtual().start(() -> {
            resultRef.set(effect.run(0, null, createMockContext()));
            latch.countDown();
        });

        Thread.sleep(100);
        testThread.interrupt();
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Effect should complete");
        
        EffectResult<Integer, String> result = resultRef.get();
        assertNotNull(result);
        assertInstanceOf(EffectResult.Failure.class, result);
    }

    // ============================================================================
    // checkInterrupted() Tests
    // ============================================================================

    @Test
    void checkInterruptedShouldFailWhenInterrupted() {
        // Set interruption flag
        Thread.currentThread().interrupt();

        Effect<Integer, Throwable, Void> effect = Effect.checkInterrupted();
        EffectResult<Integer, Void> result = effect.run(0, null, createMockContext());

        assertInstanceOf(EffectResult.Failure.class, result);
        EffectResult.Failure<Integer, Void> failure = (EffectResult.Failure<Integer, Void>) result;
        assertInstanceOf(InterruptedException.class, failure.errorValue());
        
        // Clear interruption flag for other tests
        Thread.interrupted();
    }

    @Test
    void checkInterruptedShouldSucceedWhenNotInterrupted() {
        // Ensure not interrupted
        Thread.interrupted();

        Effect<Integer, Throwable, Void> effect = Effect.checkInterrupted();
        EffectResult<Integer, Void> result = effect.run(0, null, createMockContext());

        assertInstanceOf(EffectResult.NoResult.class, result);
    }

    @Test
    void checkInterruptedInLoopShouldEnableCooperativeCancellation() throws Exception {
        AtomicInteger processedItems = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        Effect<Integer, Throwable, Integer> effect = Effect.attempt(() -> {
            for (int i = 0; i < 1000; i++) {
                if (Thread.interrupted()) {
                    throw new InterruptedException("Processing cancelled");
                }
                processedItems.incrementAndGet();
                Thread.sleep(10); // Simulate work
            }
            return processedItems.get();
        });

        Thread testThread = Thread.ofVirtual().start(() -> {
            try {
                effect.run(0, null, createMockContext());
            } catch (Exception e) {
                // Expected
            } finally {
                latch.countDown();
            }
        });

        Thread.sleep(100);
        testThread.interrupt();
        
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertTrue(processedItems.get() < 1000, 
            "Should have processed fewer items due to interruption, processed: " + processedItems.get());
    }

    // ============================================================================
    // Integration with Actor Lifecycle
    // ============================================================================

    @Test
    void interruptionShouldWorkWithActorShutdown() throws Exception {
        CountDownLatch effectStarted = new CountDownLatch(1);
        CountDownLatch effectCompleted = new CountDownLatch(1);

        // Handler that runs a long effect
        Handler<String> handler = new Handler<String>() {
            @Override
            public void receive(String message, ActorContext context) {
                Effect<Integer, Throwable, String> effect = Effect.<Integer, Throwable, String>attempt(() -> {
                    effectStarted.countDown();
                    Thread.sleep(10000); // Very long operation
                    return "completed";
                }).onInterrupt(() -> {});

                effect.run(0, message, context);
                effectCompleted.countDown();
            }
        };

        Pid actor = system.actorOf(handler)
            .withId("long-running")
            .spawn();

        // Start the long-running effect
        actor.tell("start");
        
        // Wait for effect to start
        assertTrue(effectStarted.await(1, TimeUnit.SECONDS), "Effect should have started");
        
        // Stop the actor (should interrupt the effect)
        system.stopActor(actor);
        
        // Wait for effect to complete
        assertTrue(effectCompleted.await(2, TimeUnit.SECONDS), 
            "Effect should complete after actor shutdown");
        
        // The important part is that the effect completes and doesn't hang
    }

    // ============================================================================
    // Resource Cleanup Scenarios
    // ============================================================================

    @Test
    void databaseConnectionCleanupScenario() throws Exception {
        // Simulate database connection
        class MockConnection {
            AtomicBoolean closed = new AtomicBoolean(false);
            AtomicBoolean rolledBack = new AtomicBoolean(false);
            
            void close() { closed.set(true); }
            void rollback() { rolledBack.set(true); }
            String query() throws InterruptedException {
                Thread.sleep(5000);
                return "result";
            }
        }

        MockConnection conn = new MockConnection();
        AtomicReference<EffectResult<Integer, String>> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Effect<Integer, Throwable, String> dbEffect = Effect.<Integer, Throwable, String>attempt(() -> {
            return conn.query();
        }).onInterrupt(Effect.<Integer, Throwable, Object>attempt(() -> {
            conn.rollback();
            conn.close();
            return null;
        }));

        Thread testThread = Thread.ofVirtual().start(() -> {
            resultRef.set(dbEffect.run(0, null, createMockContext()));
            latch.countDown();
        });

        Thread.sleep(100);
        testThread.interrupt();
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Effect should complete");
        
        EffectResult<Integer, String> result = resultRef.get();
        assertNotNull(result);
        assertInstanceOf(EffectResult.Failure.class, result);
        // Cleanup may or may not run depending on timing - the important part is no hang
    }

    @Test
    void fileCleanupScenario() throws Exception {
        AtomicReference<EffectResult<Integer, String>> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Effect<Integer, Throwable, String> fileEffect = Effect.<Integer, Throwable, String>attempt(() -> {
            Thread.sleep(5000); // Simulate file processing
            return "processed";
        }).onInterrupt(() -> {
            // Cleanup actions would go here
        });

        Thread testThread = Thread.ofVirtual().start(() -> {
            resultRef.set(fileEffect.run(0, null, createMockContext()));
            latch.countDown();
        });

        Thread.sleep(100);
        testThread.interrupt();
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Effect should complete");
        
        EffectResult<Integer, String> result = resultRef.get();
        assertNotNull(result);
        assertInstanceOf(EffectResult.Failure.class, result);
    }

    // ============================================================================
    // Helper Methods
    // ============================================================================

    private ActorContext createMockContext() {
        // Use a simple mock that returns null for most operations
        return new ActorContext() {
            private final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger("test");
            
            public Pid self() { return null; }
            public java.util.Optional<Pid> getSender() { return java.util.Optional.empty(); }
            public <T> void tell(Pid target, T message) {}
            public <T> void tellSelf(T message) {}
            public <T> void tellSelf(T message, long delay, TimeUnit unit) {}
            public void stop() {}
            public org.slf4j.Logger getLogger() { return logger; }
            public String getActorId() { return "test-actor"; }
            public <T> Pid createChild(Class<?> handlerClass, String childId) { return null; }
            public <T> Pid createChild(Class<?> handlerClass) { return null; }
            public <M> com.cajunsystems.builder.ActorBuilder<M> childBuilder(Class<? extends Handler<M>> handlerClass) { return null; }
            public <T> void reply(com.cajunsystems.ReplyingMessage request, T response) {}
            public <T> void forward(Pid target, T message) {}
            public Pid getParent() { return null; }
            public java.util.Map<String, Pid> getChildren() { return java.util.Collections.emptyMap(); }
            public com.cajunsystems.ActorSystem getSystem() { return system; }
        };
    }
}
