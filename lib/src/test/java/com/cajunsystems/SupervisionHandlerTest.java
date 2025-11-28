package com.cajunsystems;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.cajunsystems.handler.Handler;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for supervision strategies using Handler-based actors.
 * Tests all four strategies: RESUME, RESTART, STOP, ESCALATE
 */
class SupervisionHandlerTest {

    private static final Logger logger = LoggerFactory.getLogger(SupervisionHandlerTest.class);
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
        system = null;
    }

    // Test messages
    sealed interface TestMessage {}
    record NormalMessage(String content) implements TestMessage {}
    record ErrorMessage(String errorType) implements TestMessage {}
    record GetStateMessage(CountDownLatch latch, AtomicInteger result) implements TestMessage {}

    /**
     * Simple handler for testing basic supervision
     */
    static class SimpleHandler implements Handler<TestMessage> {
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public void receive(TestMessage message, ActorContext context) {
            if (message instanceof NormalMessage) {
                counter.incrementAndGet();
            } else if (message instanceof ErrorMessage) {
                throw new RuntimeException("Test error");
            } else if (message instanceof GetStateMessage getMsg) {
                getMsg.result().set(counter.get());
                getMsg.latch().countDown();
            }
        }

        public int getCounter() {
            return counter.get();
        }
    }

    /**
     * Handler that returns true from onError to test reprocessing
     */
    static class ReprocessHandler implements Handler<TestMessage> {
        private static final AtomicInteger processCount = new AtomicInteger(0);
        private static final AtomicInteger errorAttempts = new AtomicInteger(0);

        static void resetCounters() {
            processCount.set(0);
            errorAttempts.set(0);
        }

        @Override
        public void receive(TestMessage message, ActorContext context) {
            if (message instanceof ErrorMessage) {
                int attempts = errorAttempts.incrementAndGet();
                if (attempts == 1) {
                    // First attempt fails
                    throw new RuntimeException("First attempt fails");
                }
                // Second attempt (after restart and reprocess) succeeds
                processCount.incrementAndGet();
            } else if (message instanceof GetStateMessage getMsg) {
                getMsg.result().set(processCount.get());
                getMsg.latch().countDown();
            }
        }

        @Override
        public boolean onError(TestMessage message, Throwable exception, ActorContext context) {
            return true; // Request reprocessing
        }
    }

    /**
     * Handler that returns false from onError
     */
    static class NoReprocessHandler implements Handler<TestMessage> {
        private static final AtomicInteger errorCount = new AtomicInteger(0);

        static void resetCounters() {
            errorCount.set(0);
        }

        @Override
        public void receive(TestMessage message, ActorContext context) {
            if (message instanceof ErrorMessage) {
                errorCount.incrementAndGet();
                throw new RuntimeException("Always fails");
            } else if (message instanceof GetStateMessage getMsg) {
                getMsg.result().set(errorCount.get());
                getMsg.latch().countDown();
            }
        }

        @Override
        public boolean onError(TestMessage message, Throwable exception, ActorContext context) {
            return false; // Do NOT reprocess
        }
    }

    /**
     * Handler for testing concurrent errors in batch processing
     */
    static class BatchErrorHandler implements Handler<TestMessage> {
        private static final AtomicInteger processedCount = new AtomicInteger(0);
        private static final AtomicInteger restartCount = new AtomicInteger(0);

        static void resetCounters() {
            processedCount.set(0);
            restartCount.set(0);
        }

        static int getProcessedCount() {
            return processedCount.get();
        }

        static int getRestartCount() {
            return restartCount.get();
        }

        @Override
        public void preStart(ActorContext context) {
            restartCount.incrementAndGet();
        }

        @Override
        public void receive(TestMessage message, ActorContext context) {
            if (message instanceof NormalMessage) {
                processedCount.incrementAndGet();
            } else if (message instanceof ErrorMessage) {
                throw new RuntimeException("Batch error");
            } else if (message instanceof GetStateMessage getMsg) {
                getMsg.result().set(processedCount.get());
                getMsg.latch().countDown();
            }
        }

        @Override
        public boolean onError(TestMessage message, Throwable exception, ActorContext context) {
            return false;
        }
    }

    private static void awaitCondition(BooleanSupplier condition, long timeoutMillis, String failureMessage) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError(failureMessage);
        }
    }

    @Test
    void testResumeStrategy() throws Exception {
        Pid actorPid = system.actorOf(SimpleHandler.class)
                .withSupervisionStrategy(SupervisionStrategy.RESUME)
                .withId("resume-actor")
                .spawn();

        // Send normal message
        actorPid.tell(new NormalMessage("first"));
        
        // Send error message
        actorPid.tell(new ErrorMessage("runtime"));
        
        // Send another normal message
        actorPid.tell(new NormalMessage("second"));

        // Wait for processing
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger(0);
        actorPid.tell(new GetStateMessage(latch, result));
        
        assertTrue(latch.await(2000, TimeUnit.MILLISECONDS));
        assertEquals(2, result.get(), "Actor should have processed 2 normal messages with RESUME");
    }

    @Test
    void testStopStrategy() throws Exception {
        Pid actorPid = system.actorOf(SimpleHandler.class)
                .withSupervisionStrategy(SupervisionStrategy.STOP)
                .withId("stop-actor")
                .spawn();

        // Send normal message
        actorPid.tell(new NormalMessage("first"));
        
        // Wait for first message
        TimeUnit.MILLISECONDS.sleep(100);
        
        // Send error message
        actorPid.tell(new ErrorMessage("runtime"));
        
        // Wait for stop
        TimeUnit.MILLISECONDS.sleep(500);

        // Try to send another message - should not be processed
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger(0);
        actorPid.tell(new GetStateMessage(latch, result));
        
        // Should timeout since actor is stopped
        assertFalse(latch.await(500, TimeUnit.MILLISECONDS), 
                "Stopped actor should not process messages");
    }

    @Test
    void testRestartStrategy() throws Exception {
        Pid actorPid = system.actorOf(SimpleHandler.class)
                .withSupervisionStrategy(SupervisionStrategy.RESTART)
                .withId("restart-actor")
                .spawn();

        // Send messages
        actorPid.tell(new NormalMessage("first"));
        actorPid.tell(new ErrorMessage("runtime"));
        actorPid.tell(new NormalMessage("second"));

        // Wait for processing
        awaitCondition(() -> {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger result = new AtomicInteger(0);
            actorPid.tell(new GetStateMessage(latch, result));
            try {
                latch.await(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
            return result.get() >= 1;
        }, 2_000, "Actor should process messages after restart");
    }

    @Test
    void testMultipleRestarts() throws Exception {
        Pid actorPid = system.actorOf(SimpleHandler.class)
                .withSupervisionStrategy(SupervisionStrategy.RESTART)
                .withId("multi-restart-actor")
                .spawn();

        // Send multiple error messages to trigger multiple restarts
        for (int i = 0; i < 3; i++) {
            actorPid.tell(new NormalMessage("msg-" + i));
            actorPid.tell(new ErrorMessage("runtime"));
            TimeUnit.MILLISECONDS.sleep(200);
        }

        // Send final normal message
        actorPid.tell(new NormalMessage("final"));

        // Wait for final message
        awaitCondition(() -> {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger result = new AtomicInteger(0);
            actorPid.tell(new GetStateMessage(latch, result));
            try {
                latch.await(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
            return result.get() >= 1;
        }, 3_000, "Actor should process messages after multiple restarts");
    }

    @Test
    void testShouldReprocessTrue() throws Exception {
        ReprocessHandler.resetCounters();
        
        Pid actorPid = system.actorOf(ReprocessHandler.class)
                .withSupervisionStrategy(SupervisionStrategy.RESTART)
                .withId("reprocess-actor")
                .spawn();

        // Send error message
        actorPid.tell(new ErrorMessage("test"));

        // Wait for reprocessing - give more time for restart and reprocessing
        TimeUnit.MILLISECONDS.sleep(1000);

        // Verify error was attempted twice (once failed, once reprocessed)
        // Note: After restart, the actor state is fresh, but static counters persist
        // The errorAttempts should be 2 (first attempt + reprocess)
        // The processCount should be 1 (successful reprocess)
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger(0);
        actorPid.tell(new GetStateMessage(latch, result));
        assertTrue(latch.await(1000, TimeUnit.MILLISECONDS), "Should receive response");
        
        // If shouldReprocess works, we should see processCount = 1
        // If it doesn't work, processCount will be 0
        assertTrue(result.get() >= 1, 
                "Message should have been reprocessed successfully. Got: " + result.get());
    }

    @Test
    void testShouldReprocessFalse() throws Exception {
        NoReprocessHandler.resetCounters();
        
        Pid actorPid = system.actorOf(NoReprocessHandler.class)
                .withSupervisionStrategy(SupervisionStrategy.RESTART)
                .withId("no-reprocess-actor")
                .spawn();

        // Send error message
        actorPid.tell(new ErrorMessage("test"));

        // Wait for restart
        TimeUnit.MILLISECONDS.sleep(500);

        // Verify message was NOT reprocessed
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger(0);
        actorPid.tell(new GetStateMessage(latch, result));
        assertTrue(latch.await(500, TimeUnit.MILLISECONDS));
        assertEquals(1, result.get(), "Message should NOT have been reprocessed when onError returns false");
    }

    @Test
    void testConcurrentErrorsInBatch() throws Exception {
        BatchErrorHandler.resetCounters();
        
        Pid actorPid = system.actorOf(BatchErrorHandler.class)
                .withSupervisionStrategy(SupervisionStrategy.RESTART)
                .withId("batch-error-actor")
                .spawn();

        // Send a batch with multiple errors
        actorPid.tell(new NormalMessage("msg1"));
        actorPid.tell(new ErrorMessage("error1"));
        actorPid.tell(new NormalMessage("msg2"));
        actorPid.tell(new ErrorMessage("error2"));
        actorPid.tell(new NormalMessage("msg3"));

        // Wait for processing
        awaitCondition(() -> BatchErrorHandler.getProcessedCount() >= 2, 3_000,
                "Actor should process messages after concurrent errors");

        // Verify restart count
        assertTrue(BatchErrorHandler.getRestartCount() >= 2,
                "Actor should have restarted at least once from initial start");
    }

    @Test
    void testRestartPreservesUnprocessedMessages() throws Exception {
        Pid actorPid = system.actorOf(SimpleHandler.class)
                .withSupervisionStrategy(SupervisionStrategy.RESTART)
                .withId("preserve-actor")
                .spawn();

        // Send messages in quick succession
        actorPid.tell(new NormalMessage("msg1"));
        actorPid.tell(new ErrorMessage("runtime"));
        actorPid.tell(new NormalMessage("msg2"));
        actorPid.tell(new NormalMessage("msg3"));

        // Wait for all messages to be processed
        awaitCondition(() -> {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger result = new AtomicInteger(0);
            actorPid.tell(new GetStateMessage(latch, result));
            try {
                latch.await(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
            return result.get() >= 3;
        }, 3_000, "All normal messages should be processed after restart");

        // Verify all 3 normal messages were processed
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger(0);
        actorPid.tell(new GetStateMessage(latch, result));
        assertTrue(latch.await(500, TimeUnit.MILLISECONDS));
        assertEquals(3, result.get(), "All 3 normal messages should have been processed");
    }
}
