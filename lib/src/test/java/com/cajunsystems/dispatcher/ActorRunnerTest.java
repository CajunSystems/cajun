package com.cajunsystems.dispatcher;

import com.cajunsystems.ActorLifecycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ActorRunner.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ActorRunnerTest {

    private Dispatcher dispatcher;

    @AfterEach
    void cleanup() {
        if (dispatcher != null && !dispatcher.isShutdown()) {
            dispatcher.shutdown();
            dispatcher.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void testProcessSingleMessage() throws InterruptedException {
        dispatcher = Dispatcher.virtualThreadDispatcher();
        AtomicInteger processedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithConcurrentLinkedQueue(
            1024,
            OverflowStrategy.BLOCK,
            () -> {},
            "test-actor"
        );

        ActorLifecycle<String> lifecycle = new ActorLifecycle<String>() {
            @Override
            public void preStart() {}

            @Override
            public void receive(String message) {
                processedCount.incrementAndGet();
                latch.countDown();
            }

            @Override
            public void postStop() {}
        };

        ActorRunner<String> runner = new ActorRunner<>(
            "test-actor",
            mailbox,
            lifecycle,
            (msg, ex) -> {},
            dispatcher,
            64
        );

        mailbox.enqueue("test-message");
        mailbox.getScheduled().set(true); // Simulate scheduled state
        
        runner.run();
        
        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals(1, processedCount.get());
    }

    @Test
    void testBatchProcessing() throws InterruptedException {
        dispatcher = Dispatcher.virtualThreadDispatcher();
        List<String> processed = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(10);

        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithConcurrentLinkedQueue(
            1024,
            OverflowStrategy.BLOCK,
            () -> {},
            "test-actor"
        );

        ActorLifecycle<String> lifecycle = new ActorLifecycle<String>() {
            @Override
            public void preStart() {}

            @Override
            public void receive(String message) {
                synchronized (processed) {
                    processed.add(message);
                }
                latch.countDown();
            }

            @Override
            public void postStop() {}
        };

        ActorRunner<String> runner = new ActorRunner<>(
            "test-actor",
            mailbox,
            lifecycle,
            (msg, ex) -> {},
            dispatcher,
            5 // Process 5 messages per batch
        );

        // Enqueue 10 messages
        for (int i = 0; i < 10; i++) {
            mailbox.enqueue("msg-" + i);
        }
        
        mailbox.getScheduled().set(true);
        
        // First run should process 5 messages
        runner.run();
        Thread.sleep(100); // Give time for processing
        
        // Should have processed 5 messages
        synchronized (processed) {
            assertTrue(processed.size() >= 5 && processed.size() <= 10);
        }
        
        // Runner should have re-scheduled itself for remaining messages
        // Run again to process remaining messages
        runner.run();
        
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(10, processed.size());
    }

    @Test
    void testExceptionHandling() throws InterruptedException {
        dispatcher = Dispatcher.virtualThreadDispatcher();
        AtomicInteger errorCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithConcurrentLinkedQueue(
            1024,
            OverflowStrategy.BLOCK,
            () -> {},
            "test-actor"
        );

        ActorLifecycle<String> lifecycle = new ActorLifecycle<String>() {
            @Override
            public void preStart() {}

            @Override
            public void receive(String message) {
                throw new RuntimeException("Test exception");
            }

            @Override
            public void postStop() {}
        };

        ActorRunner<String> runner = new ActorRunner<>(
            "test-actor",
            mailbox,
            lifecycle,
            (msg, ex) -> {
                errorCount.incrementAndGet();
                latch.countDown();
            },
            dispatcher,
            64
        );

        mailbox.enqueue("error-message");
        mailbox.getScheduled().set(true);
        
        runner.run();
        
        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals(1, errorCount.get());
    }

    @Test
    void testReScheduling() throws InterruptedException {
        dispatcher = Dispatcher.virtualThreadDispatcher();
        AtomicInteger processedCount = new AtomicInteger(0);
        CountDownLatch completeLatch = new CountDownLatch(5); // All messages processed

        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithConcurrentLinkedQueue(
            1024,
            OverflowStrategy.BLOCK,
            () -> {}, // No-op schedule action for this test
            "test-actor"
        );

        ActorLifecycle<String> lifecycle = new ActorLifecycle<String>() {
            @Override
            public void preStart() {}

            @Override
            public void receive(String message) {
                processedCount.incrementAndGet();
                completeLatch.countDown();
            }

            @Override
            public void postStop() {}
        };

        ActorRunner<String> runner = new ActorRunner<>(
            "test-actor",
            mailbox,
            lifecycle,
            (msg, ex) -> {},
            dispatcher,
            2 // Small batch size - will process 2 at a time
        );

        // Enqueue 5 messages
        for (int i = 0; i < 5; i++) {
            mailbox.enqueue("msg-" + i);
        }

        mailbox.getScheduled().set(true);
        
        // Run multiple times to process all messages in batches of 2
        for (int i = 0; i < 3; i++) {
            runner.run();
            Thread.sleep(50); // Small delay between runs
        }

        // Should have processed all 5 messages
        assertTrue(completeLatch.await(2, TimeUnit.SECONDS));
        assertEquals(5, processedCount.get());
    }

    @Test
    void testThroughputGetter() {
        dispatcher = Dispatcher.virtualThreadDispatcher();
        
        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithConcurrentLinkedQueue(
            1024,
            OverflowStrategy.BLOCK,
            () -> {},
            "test-actor"
        );

        ActorRunner<String> runner = new ActorRunner<>(
            "test-actor",
            mailbox,
            new ActorLifecycle<String>() {
                @Override
                public void preStart() {}
                @Override
                public void receive(String message) {}
                @Override
                public void postStop() {}
            },
            (msg, ex) -> {},
            dispatcher,
            128
        );

        assertEquals(128, runner.getThroughput());
        assertEquals("test-actor", runner.getActorId());
    }

    @Test
    void testZeroThroughputDefaultsToOne() {
        dispatcher = Dispatcher.virtualThreadDispatcher();
        
        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithConcurrentLinkedQueue(
            1024,
            OverflowStrategy.BLOCK,
            () -> {},
            "test-actor"
        );

        ActorRunner<String> runner = new ActorRunner<>(
            "test-actor",
            mailbox,
            new ActorLifecycle<String>() {
                @Override
                public void preStart() {}
                @Override
                public void receive(String message) {}
                @Override
                public void postStop() {}
            },
            (msg, ex) -> {},
            dispatcher,
            0 // Should be adjusted to 1
        );

        assertEquals(1, runner.getThroughput());
    }

    @Test
    void testNegativeThroughputDefaultsToOne() {
        dispatcher = Dispatcher.virtualThreadDispatcher();
        
        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithConcurrentLinkedQueue(
            1024,
            OverflowStrategy.BLOCK,
            () -> {},
            "test-actor"
        );

        ActorRunner<String> runner = new ActorRunner<>(
            "test-actor",
            mailbox,
            new ActorLifecycle<String>() {
                @Override
                public void preStart() {}
                @Override
                public void receive(String message) {}
                @Override
                public void postStop() {}
            },
            (msg, ex) -> {},
            dispatcher,
            -10 // Should be adjusted to 1
        );

        assertEquals(1, runner.getThroughput());
    }

    @Test
    void testEmptyMailboxProcessing() {
        dispatcher = Dispatcher.virtualThreadDispatcher();
        AtomicInteger processedCount = new AtomicInteger(0);

        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithConcurrentLinkedQueue(
            1024,
            OverflowStrategy.BLOCK,
            () -> {},
            "test-actor"
        );

        ActorRunner<String> runner = new ActorRunner<>(
            "test-actor",
            mailbox,
            new ActorLifecycle<String>() {
                @Override
                public void preStart() {}
                @Override
                public void receive(String message) {
                    processedCount.incrementAndGet();
                }
                @Override
                public void postStop() {}
            },
            (msg, ex) -> {},
            dispatcher,
            64
        );

        mailbox.getScheduled().set(true);
        
        // Run with empty mailbox - should not crash
        runner.run();
        
        assertEquals(0, processedCount.get());
    }
}
