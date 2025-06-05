package com.cajunsystems;

import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.handler.Handler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test to verify ThreadPoolFactory configuration for regular actors.
 */
public class SimpleThreadPoolFactoryTest {

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

    public static class ThreadNameCapturingHandler implements Handler<String> {
        private volatile String capturedThreadName;
        private final CountDownLatch latch = new CountDownLatch(1);
        
        @Override
        public void receive(String message, ActorContext context) {
            if ("capture".equals(message)) {
                capturedThreadName = Thread.currentThread().getName();
                latch.countDown();
            }
        }
        
        public String getCapturedThreadName() throws InterruptedException {
            boolean success = latch.await(5, TimeUnit.SECONDS);
            if (!success) {
                throw new RuntimeException("Timeout waiting for thread name capture");
            }
            return capturedThreadName;
        }
    }

    @Test
    void testActorWithDefaultVirtualThreads() throws InterruptedException {
        // Create actor without specifying thread pool factory (should use default virtual threads)
        ThreadNameCapturingHandler handler = new ThreadNameCapturingHandler();
        Pid actorPid = system.actorOf(handler)
            .withId("default-virtual-actor")
            .spawn();

        // Send message to capture thread name
        actorPid.tell("capture");

        // Wait for thread name to be captured
        String threadName = handler.getCapturedThreadName();
        assertNotNull(threadName);
        
        // Should contain the actor ID in the thread name
        assertTrue(threadName.contains("default-virtual-actor"),
                "Expected actor ID in thread name, got: " + threadName);
    }

    @Test
    void testActorWithCustomPlatformThreadFactory() throws InterruptedException {
        // Create a custom thread pool factory configured for platform threads
        ThreadPoolFactory customFactory = new ThreadPoolFactory()
            .setExecutorType(ThreadPoolFactory.ThreadPoolType.FIXED)
            .setFixedPoolSize(2)
            .setPreferVirtualThreads(false)
            .setUseNamedThreads(true);

        // Create actor with custom thread pool factory
        ThreadNameCapturingHandler handler = new ThreadNameCapturingHandler();
        Pid actorPid = system.actorOf(handler)
            .withId("platform-thread-actor")
            .withThreadPoolFactory(customFactory)
            .spawn();

        // Send message to capture thread name
        actorPid.tell("capture");

        // Wait for thread name to be captured
        String threadName = handler.getCapturedThreadName();
        assertNotNull(threadName);
        
        // Should contain the actor ID in the thread name
        assertTrue(threadName.contains("platform-thread-actor"),
                "Expected actor ID in platform thread name, got: " + threadName);
    }

    @Test
    void testActorWithIOOptimizedThreadFactory() throws InterruptedException {
        // Create a thread pool factory optimized for IO-bound work
        ThreadPoolFactory ioFactory = new ThreadPoolFactory()
            .optimizeFor(ThreadPoolFactory.WorkloadType.IO_BOUND);

        // Create actor with IO-optimized thread pool factory
        ThreadNameCapturingHandler handler = new ThreadNameCapturingHandler();
        Pid actorPid = system.actorOf(handler)
            .withId("io-optimized-actor")
            .withThreadPoolFactory(ioFactory)
            .spawn();

        // Send message to capture thread name
        actorPid.tell("capture");

        // Wait for thread name to be captured
        String threadName = handler.getCapturedThreadName();
        assertNotNull(threadName);
        
        // Should contain the actor ID in the thread name
        assertTrue(threadName.contains("io-optimized-actor"),
                "Expected actor ID in IO-optimized thread name, got: " + threadName);
    }

    @Test
    void testThreadPoolFactoryCreateActorThread() {
        // Test the new createActorThread method
        ThreadPoolFactory factory = new ThreadPoolFactory()
            .setExecutorType(ThreadPoolFactory.ThreadPoolType.VIRTUAL)
            .setUseNamedThreads(true);

        String actorId = "test-actor-123";
        Runnable task = () -> System.out.println("Test task");
        
        Thread thread = factory.createThreadFactory(actorId).newThread(task);
        assertNotNull(thread);
        assertTrue(thread.getName().contains(actorId));
        assertTrue(thread.isVirtual());
    }

    @Test
    void testThreadPoolFactoryCreatePlatformThread() {
        // Test creating platform threads
        ThreadPoolFactory factory = new ThreadPoolFactory()
            .setExecutorType(ThreadPoolFactory.ThreadPoolType.FIXED)
            .setPreferVirtualThreads(false)
            .setUseNamedThreads(true);

        String actorId = "platform-actor-456";
        Runnable task = () -> System.out.println("Platform task");
        
        Thread thread = factory.createThreadFactory(actorId).newThread(task);
        assertNotNull(thread);
        assertTrue(thread.getName().contains(actorId));
        assertFalse(thread.isVirtual());
    }
}
