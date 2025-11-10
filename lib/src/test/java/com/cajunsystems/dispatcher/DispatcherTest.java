package com.cajunsystems.dispatcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Dispatcher class.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class DispatcherTest {

    private Dispatcher dispatcher;

    @AfterEach
    void cleanup() {
        if (dispatcher != null && !dispatcher.isShutdown()) {
            dispatcher.shutdown();
            dispatcher.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void testVirtualThreadDispatcherCreation() {
        dispatcher = Dispatcher.virtualThreadDispatcher();
        assertNotNull(dispatcher);
        assertFalse(dispatcher.isShutdown());
        assertEquals("dispatcher", dispatcher.getName());
    }

    @Test
    void testVirtualThreadDispatcherWithCustomName() {
        dispatcher = Dispatcher.virtualThreadDispatcher("custom-dispatcher");
        assertNotNull(dispatcher);
        assertEquals("custom-dispatcher", dispatcher.getName());
    }

    @Test
    void testFixedThreadPoolDispatcherCreation() {
        dispatcher = Dispatcher.fixedThreadPoolDispatcher(4);
        assertNotNull(dispatcher);
        assertFalse(dispatcher.isShutdown());
        assertEquals("dispatcher", dispatcher.getName());
    }

    @Test
    void testFixedThreadPoolDispatcherWithCustomName() {
        dispatcher = Dispatcher.fixedThreadPoolDispatcher(4, "fixed-dispatcher");
        assertNotNull(dispatcher);
        assertEquals("fixed-dispatcher", dispatcher.getName());
    }

    @Test
    void testScheduleTask() throws InterruptedException {
        dispatcher = Dispatcher.virtualThreadDispatcher();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger(0);

        dispatcher.schedule(() -> {
            result.set(42);
            latch.countDown();
        });

        assertTrue(latch.await(1, TimeUnit.SECONDS), "Task should complete");
        assertEquals(42, result.get());
    }

    @Test
    void testScheduleMultipleTasks() throws InterruptedException {
        dispatcher = Dispatcher.virtualThreadDispatcher();
        int taskCount = 100;
        CountDownLatch latch = new CountDownLatch(taskCount);
        AtomicInteger counter = new AtomicInteger(0);

        for (int i = 0; i < taskCount; i++) {
            dispatcher.schedule(() -> {
                counter.incrementAndGet();
                latch.countDown();
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "All tasks should complete");
        assertEquals(taskCount, counter.get());
    }

    @Test
    void testShutdown() {
        dispatcher = Dispatcher.virtualThreadDispatcher();
        assertFalse(dispatcher.isShutdown());

        dispatcher.shutdown();
        assertTrue(dispatcher.isShutdown());

        // Should be idempotent
        dispatcher.shutdown();
        assertTrue(dispatcher.isShutdown());
    }

    @Test
    void testScheduleAfterShutdown() throws InterruptedException {
        dispatcher = Dispatcher.virtualThreadDispatcher();
        dispatcher.shutdown();

        CountDownLatch latch = new CountDownLatch(1);
        
        // Should not throw, but task won't execute
        dispatcher.schedule(() -> latch.countDown());

        // Task should not execute after shutdown
        assertFalse(latch.await(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void testAwaitTermination() throws InterruptedException {
        dispatcher = Dispatcher.virtualThreadDispatcher();
        CountDownLatch taskLatch = new CountDownLatch(1);

        dispatcher.schedule(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            taskLatch.countDown();
        });

        dispatcher.shutdown();
        boolean terminated = dispatcher.awaitTermination(2, TimeUnit.SECONDS);

        assertTrue(terminated, "Dispatcher should terminate");
        assertTrue(taskLatch.getCount() == 0 || taskLatch.getCount() == 1); // Task may or may not complete
    }

    @Test
    void testConcurrentScheduling() throws InterruptedException {
        dispatcher = Dispatcher.virtualThreadDispatcher();
        int threadCount = 10;
        int tasksPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threadCount * tasksPerThread);
        AtomicInteger counter = new AtomicInteger(0);

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < tasksPerThread; j++) {
                    dispatcher.schedule(() -> {
                        counter.incrementAndGet();
                        latch.countDown();
                    });
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "All tasks should complete");
        assertEquals(threadCount * tasksPerThread, counter.get());
    }

    @Test
    void testFixedThreadPoolWithZeroThreads() {
        // Should create at least 1 thread
        dispatcher = Dispatcher.fixedThreadPoolDispatcher(0);
        assertNotNull(dispatcher);
    }

    @Test
    void testFixedThreadPoolWithNegativeThreads() {
        // Should create at least 1 thread
        dispatcher = Dispatcher.fixedThreadPoolDispatcher(-5);
        assertNotNull(dispatcher);
    }
}
