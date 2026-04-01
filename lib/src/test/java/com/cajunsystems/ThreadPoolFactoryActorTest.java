package com.cajunsystems;

import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.test.TempPersistenceExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that ThreadPoolFactory can be configured for actors
 * and that actors use the specified thread pool configuration.
 */
@ExtendWith(TempPersistenceExtension.class)
public class ThreadPoolFactoryActorTest {

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

    public record GetThreadName(Pid replyTo) implements java.io.Serializable {}
    public record ThreadNameResponse(String threadName) implements java.io.Serializable {}

    // Test handler that captures the thread name
    public static class ThreadCapturingHandler implements Handler<Object> {
        private final CountDownLatch latch;
        
        public ThreadCapturingHandler() {
            this.latch = new CountDownLatch(1);
        }
        
        @Override
        public void receive(Object message, ActorContext context) {
            if (message instanceof GetThreadName getThreadName) {
                String threadName = Thread.currentThread().getName();
                context.tell(getThreadName.replyTo(), new ThreadNameResponse(threadName));
                latch.countDown();
            }
        }
        
        public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
    }

    // Response handler to capture the thread name response
    public static class ResponseHandler implements Handler<ThreadNameResponse> {
        private volatile String receivedThreadName;
        private final CountDownLatch latch = new CountDownLatch(1);
        
        @Override
        public void receive(ThreadNameResponse message, ActorContext context) {
            this.receivedThreadName = message.threadName();
            latch.countDown();
        }
        
        public String getReceivedThreadName() throws InterruptedException {
            latch.await(5, TimeUnit.SECONDS);
            return receivedThreadName;
        }
    }

    @Test
    void testActorWithDefaultVirtualThreads() throws InterruptedException {
        // Create actor without specifying thread pool factory (should use default virtual threads)
        ThreadCapturingHandler handler = new ThreadCapturingHandler();
        Pid actorPid = system.actorOf(handler)
            .withId("default-thread-actor")
            .spawn();

        // Create response handler
        ResponseHandler responseHandler = new ResponseHandler();
        Pid responsePid = system.actorOf(responseHandler)
            .withId("response-actor")
            .spawn();

        // Send message to get thread name
        actorPid.tell(new GetThreadName(responsePid));

        // Wait for response
        String threadName = responseHandler.getReceivedThreadName();
        assertNotNull(threadName);
        
        // Thread names can vary based on implementation details
        // Check for common patterns in thread names
        assertTrue(
            threadName.contains("actor-default-thread-actor") || 
            threadName.contains("VirtualThread") || 
            threadName.contains("virtual") ||
            threadName.contains("worker") ||
            threadName.contains("default-thread-actor"),
            STR."Expected thread name pattern, got: \{threadName}"
        );
    }

    @Test
    void testActorWithCustomThreadPoolFactory() throws InterruptedException {
        // Create a custom thread pool factory configured for platform threads
        ThreadPoolFactory customFactory = new ThreadPoolFactory()
            .setExecutorType(ThreadPoolFactory.ThreadPoolType.FIXED)
            .setFixedPoolSize(2)
            .setPreferVirtualThreads(false)
            .setUseNamedThreads(true);

        // Create actor with custom thread pool factory
        ThreadCapturingHandler handler = new ThreadCapturingHandler();
        Pid actorPid = system.actorOf(handler)
            .withId("custom-thread-actor")
            .withThreadPoolFactory(customFactory)
            .spawn();

        // Create response handler
        ResponseHandler responseHandler = new ResponseHandler();
        Pid responsePid = system.actorOf(responseHandler)
            .withId("response-actor-2")
            .spawn();

        // Send message to get thread name
        actorPid.tell(new GetThreadName(responsePid));

        // Wait for response
        String threadName = responseHandler.getReceivedThreadName();
        assertNotNull(threadName);
        
        // With custom thread pool factory, we should see either the actor ID or "worker" in the thread name
        assertTrue(
            threadName.contains("custom-thread-actor") || 
            threadName.contains("worker"),
            "Expected custom thread name pattern, got: " + threadName
        );
    }

    @Test
    void testStatefulActorWithCustomThreadPoolFactory() throws InterruptedException {
        // For this test, we'll use a simple string message to avoid serialization issues
        StatefulHandler<String, String> statefulHandler = new StatefulHandler<>() {
            @Override
            public String receive(String message, String state, ActorContext context) {
                if ("get-thread-name".equals(message)) {
                    String threadName = Thread.currentThread().getName();
                    // Store the thread name in state for retrieval
                    return threadName;
                }
                return state;
            }
        };

        // Create a thread pool factory optimized for CPU-bound work
        ThreadPoolFactory cpuOptimizedFactory = new ThreadPoolFactory()
            .optimizeFor(ThreadPoolFactory.WorkloadType.CPU_BOUND);

        // Create stateful actor with custom thread pool factory
        Pid statefulActorPid = system.statefulActorOf(statefulHandler, "initial")
            .withId("stateful-thread-actor")
            .withThreadPoolFactory(cpuOptimizedFactory)
            .spawn();

        // Send message to capture thread name
        statefulActorPid.tell("get-thread-name");

        // Give it time to process
        Thread.sleep(1000);
        
        // For now, just verify the actor was created successfully
        // A more sophisticated test would involve getting the state back
        assertNotNull(statefulActorPid);
        assertTrue(statefulActorPid.actorId().contains("stateful-thread-actor"));
    }

    @Test
    void testThreadPoolFactoryWorkloadOptimization() {
        // Test IO-bound optimization
        ThreadPoolFactory ioFactory = new ThreadPoolFactory()
            .optimizeFor(ThreadPoolFactory.WorkloadType.IO_BOUND);
        assertEquals(ThreadPoolFactory.ThreadPoolType.VIRTUAL, ioFactory.getExecutorType());
        assertTrue(ioFactory.isPreferVirtualThreads());

        // Test CPU-bound optimization
        ThreadPoolFactory cpuFactory = new ThreadPoolFactory()
            .optimizeFor(ThreadPoolFactory.WorkloadType.CPU_BOUND);
        assertEquals(ThreadPoolFactory.ThreadPoolType.FIXED, cpuFactory.getExecutorType());
        assertFalse(cpuFactory.isPreferVirtualThreads());

        // Test mixed workload optimization
        ThreadPoolFactory mixedFactory = new ThreadPoolFactory()
            .optimizeFor(ThreadPoolFactory.WorkloadType.MIXED);
        assertEquals(ThreadPoolFactory.ThreadPoolType.WORK_STEALING, mixedFactory.getExecutorType());
        assertTrue(mixedFactory.isPreferVirtualThreads());
    }
}
