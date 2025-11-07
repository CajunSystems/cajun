///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.cajunsystems:cajun:0.1.4
//JAVA 21+
//PREVIEW

package examples;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This test demonstrates the performance difference between actors and threads
 * when accessing state. Actors maintain isolated state, while threads need to
 * synchronize access to shared state.
 * 
 * The test compares:
 * 1. Stateful actors that maintain their own isolated state
 * 2. Threads with synchronized access to shared state
 * 3. Threads with lock-based access to shared state
 * 4. Threads with unsynchronized access to shared state (unsafe)
 */
public class StateAccessComparisonTest {

    private static final Logger logger = LoggerFactory.getLogger(StateAccessComparisonTest.class);
    private static final int NUM_WORKERS = 10;
    private static final int OPERATIONS_PER_WORKER = 100_000;
    private static final int TOTAL_OPERATIONS = NUM_WORKERS * OPERATIONS_PER_WORKER;
    private static final String RESULTS_FILE = "state_access_comparison.txt";

    public static void main(String[] args) throws Exception {
        logger.info("Starting Actor vs Threads State Access Comparison");

        // Create a results file
        Path resultsPath = Paths.get(RESULTS_FILE);
        Files.deleteIfExists(resultsPath);
        Files.createFile(resultsPath);

        // Run actor-based implementation
        long actorTime = runActorImplementation();

        // Run thread-based implementation with synchronized methods
        long synchronizedThreadTime = runSynchronizedThreadImplementation();

        // Run thread-based implementation with locks
        long lockThreadTime = runLockThreadImplementation();

        // Run thread-based implementation without synchronization (unsafe)
        long unsafeThreadTime = runUnsafeThreadImplementation();

        // Calculate throughput
        double actorThroughput = TOTAL_OPERATIONS / (actorTime / 1000.0);
        double synchronizedThroughput = TOTAL_OPERATIONS / (synchronizedThreadTime / 1000.0);
        double lockThroughput = TOTAL_OPERATIONS / (lockThreadTime / 1000.0);
        double unsafeThroughput = TOTAL_OPERATIONS / (unsafeThreadTime / 1000.0);

        // Format results
        String results = String.format(
                "State Access Performance Comparison Results:\n" +
                        "Total operations: %d (across %d workers, %d operations each)\n\n" +
                        "Actor-based implementation (isolated state):\n" +
                        "  Total time: %d ms\n" +
                        "  Throughput: %.2f operations/second\n\n" +
                        "Thread-based implementation with synchronized methods (shared state):\n" +
                        "  Total time: %d ms\n" +
                        "  Throughput: %.2f operations/second\n\n" +
                        "Thread-based implementation with explicit locks (shared state):\n" +
                        "  Total time: %d ms\n" +
                        "  Throughput: %.2f operations/second\n\n" +
                        "Thread-based implementation without synchronization (unsafe, shared state):\n" +
                        "  Total time: %d ms\n" +
                        "  Throughput: %.2f operations/second\n\n" +
                        "Performance ratios:\n" +
                        "  Synchronized/Actor: %.2f\n" +
                        "  Lock/Actor: %.2f\n" +
                        "  Unsafe/Actor: %.2f\n",
                TOTAL_OPERATIONS, NUM_WORKERS, OPERATIONS_PER_WORKER,
                actorTime, actorThroughput,
                synchronizedThreadTime, synchronizedThroughput,
                lockThreadTime, lockThroughput,
                unsafeThreadTime, unsafeThroughput,
                (double) synchronizedThreadTime / actorTime,
                (double) lockThreadTime / actorTime,
                (double) unsafeThreadTime / actorTime);

        logger.info(results);

        // Write results to file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(RESULTS_FILE))) {
            writer.write(results);
        }

        logger.info("Results saved to {}", RESULTS_FILE);
    }

    /**
     * Runs the actor-based implementation and returns the execution time in milliseconds.
     * Each actor maintains its own isolated state, so no synchronization is needed.
     */
    private static long runActorImplementation() throws Exception {
        logger.info("Running actor-based implementation (isolated state)...");

        // Create actor system
        ActorSystem system = new ActorSystem();

        // Create completion latch
        CountDownLatch completionLatch = new CountDownLatch(NUM_WORKERS);

        // Create and register worker actors
        List<StatefulWorkerActor> workers = new ArrayList<>();
        for (int i = 0; i < NUM_WORKERS; i++) {
            StatefulWorkerActor worker = new StatefulWorkerActor(system, "worker-" + i, OPERATIONS_PER_WORKER, completionLatch);
            registerActor(system, "worker-" + i, worker);
            workers.add(worker);
        }

        // Start timing
        long startTime = System.currentTimeMillis();

        // Start all workers
        for (StatefulWorkerActor worker : workers) {
            worker.tell(new StartMessage());
        }

        // Wait for completion
        completionLatch.await();

        // Calculate execution time
        long executionTime = System.currentTimeMillis() - startTime;

        // Verify results
        int totalCounter = 0;
        for (StatefulWorkerActor worker : workers) {
            totalCounter += worker.getCounter();
        }
        logger.info("Actor implementation completed. Total counter value: {} (expected: {})", 
                totalCounter, TOTAL_OPERATIONS);

        // Shutdown actor system
        system.shutdown();

        logger.info("Actor-based implementation completed in {} ms", executionTime);
        return executionTime;
    }

    /**
     * Runs the thread-based implementation with synchronized methods and returns the execution time in milliseconds.
     * All threads share the same state object and use synchronized methods to access it.
     */
    private static long runSynchronizedThreadImplementation() throws Exception {
        logger.info("Running thread-based implementation with synchronized methods (shared state)...");

        // Create shared state object
        SynchronizedSharedState sharedState = new SynchronizedSharedState();

        // Create completion latch
        CountDownLatch completionLatch = new CountDownLatch(NUM_WORKERS);

        // Create executor service
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        // Start timing
        long startTime = System.currentTimeMillis();

        // Create and start worker threads
        for (int i = 0; i < NUM_WORKERS; i++) {
            executor.submit(new SynchronizedWorkerThread(sharedState, OPERATIONS_PER_WORKER, completionLatch));
        }

        // Wait for completion
        completionLatch.await();

        // Calculate execution time
        long executionTime = System.currentTimeMillis() - startTime;

        // Verify results
        logger.info("Synchronized thread implementation completed. Total counter value: {} (expected: {})", 
                sharedState.getCounter(), TOTAL_OPERATIONS);

        // Shutdown executor
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        logger.info("Thread-based implementation with synchronized methods completed in {} ms", executionTime);
        return executionTime;
    }

    /**
     * Runs the thread-based implementation with explicit locks and returns the execution time in milliseconds.
     * All threads share the same state object and use explicit locks to access it.
     */
    private static long runLockThreadImplementation() throws Exception {
        logger.info("Running thread-based implementation with explicit locks (shared state)...");

        // Create shared state object
        LockBasedSharedState sharedState = new LockBasedSharedState();

        // Create completion latch
        CountDownLatch completionLatch = new CountDownLatch(NUM_WORKERS);

        // Create executor service
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        // Start timing
        long startTime = System.currentTimeMillis();

        // Create and start worker threads
        for (int i = 0; i < NUM_WORKERS; i++) {
            executor.submit(new LockWorkerThread(sharedState, OPERATIONS_PER_WORKER, completionLatch));
        }

        // Wait for completion
        completionLatch.await();

        // Calculate execution time
        long executionTime = System.currentTimeMillis() - startTime;

        // Verify results
        logger.info("Lock-based thread implementation completed. Total counter value: {} (expected: {})", 
                sharedState.getCounter(), TOTAL_OPERATIONS);

        // Shutdown executor
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        logger.info("Thread-based implementation with explicit locks completed in {} ms", executionTime);
        return executionTime;
    }

    /**
     * Runs the thread-based implementation without synchronization (unsafe) and returns the execution time in milliseconds.
     * All threads share the same state object but do not synchronize access, which will likely lead to race conditions.
     */
    private static long runUnsafeThreadImplementation() throws Exception {
        logger.info("Running thread-based implementation without synchronization (unsafe, shared state)...");

        // Create shared state object
        UnsafeSharedState sharedState = new UnsafeSharedState();

        // Create completion latch
        CountDownLatch completionLatch = new CountDownLatch(NUM_WORKERS);

        // Create executor service
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        // Start timing
        long startTime = System.currentTimeMillis();

        // Create and start worker threads
        for (int i = 0; i < NUM_WORKERS; i++) {
            executor.submit(new UnsafeWorkerThread(sharedState, OPERATIONS_PER_WORKER, completionLatch));
        }

        // Wait for completion
        completionLatch.await();

        // Calculate execution time
        long executionTime = System.currentTimeMillis() - startTime;

        // Verify results (will likely not match expected due to race conditions)
        logger.info("Unsafe thread implementation completed. Total counter value: {} (expected: {})", 
                sharedState.getCounter(), TOTAL_OPERATIONS);

        // Shutdown executor
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        logger.info("Thread-based implementation without synchronization completed in {} ms", executionTime);
        return executionTime;
    }

    /**
     * Helper method to register an actor with the system and start it.
     */
    private static <T extends Actor<?>> void registerActor(ActorSystem system, String actorId, T actor) {
        try {
            // Use reflection to access the private actors map in ActorSystem
            java.lang.reflect.Field actorsField = ActorSystem.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<String, Actor<?>> actors =
                    (ConcurrentHashMap<String, Actor<?>>) actorsField.get(system);

            // Register the actor
            actors.put(actorId, actor);

            // Start the actor
            actor.start();

            logger.debug("Registered and started actor: {}", actorId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to register actor: " + actorId, e);
        }
    }

    // ===== Message classes =====

    /**
     * Message to start processing.
     */
    private static class StartMessage {
    }

    // ===== Actor implementation =====

    /**
     * Stateful worker actor that maintains its own isolated state.
     */
    public static class StatefulWorkerActor extends Actor<Object> {
        private final int operationsToPerform;
        private final CountDownLatch completionLatch;
        private int counter = 0;

        public StatefulWorkerActor(ActorSystem system, String actorId, int operationsToPerform, CountDownLatch completionLatch) {
            super(system, actorId);
            this.operationsToPerform = operationsToPerform;
            this.completionLatch = completionLatch;
        }

        @Override
        protected void receive(Object message) {
            if (message instanceof StartMessage) {
                // Perform operations on local state
                for (int i = 0; i < operationsToPerform; i++) {
                    // Increment counter (no synchronization needed as actors process messages sequentially)
                    counter++;
                    
                    // Simulate some work to make the operation more realistic
                    if (i % 1000 == 0) {
                        Thread.yield();
                    }
                }
                
                // Signal completion
                completionLatch.countDown();
            }
        }

        public int getCounter() {
            return counter;
        }
    }

    // ===== Thread implementations =====

    /**
     * Worker thread that uses synchronized methods to access shared state.
     */
    private static class SynchronizedWorkerThread implements Runnable {
        private final SynchronizedSharedState sharedState;
        private final int operationsToPerform;
        private final CountDownLatch completionLatch;

        public SynchronizedWorkerThread(SynchronizedSharedState sharedState, int operationsToPerform, CountDownLatch completionLatch) {
            this.sharedState = sharedState;
            this.operationsToPerform = operationsToPerform;
            this.completionLatch = completionLatch;
        }

        @Override
        public void run() {
            try {
                // Perform operations on shared state
                for (int i = 0; i < operationsToPerform; i++) {
                    // Increment counter using synchronized method
                    sharedState.incrementCounter();
                    
                    // Simulate some work to make the operation more realistic
                    if (i % 1000 == 0) {
                        Thread.yield();
                    }
                }
            } finally {
                // Signal completion
                completionLatch.countDown();
            }
        }
    }

    /**
     * Worker thread that uses explicit locks to access shared state.
     */
    private static class LockWorkerThread implements Runnable {
        private final LockBasedSharedState sharedState;
        private final int operationsToPerform;
        private final CountDownLatch completionLatch;

        public LockWorkerThread(LockBasedSharedState sharedState, int operationsToPerform, CountDownLatch completionLatch) {
            this.sharedState = sharedState;
            this.operationsToPerform = operationsToPerform;
            this.completionLatch = completionLatch;
        }

        @Override
        public void run() {
            try {
                // Perform operations on shared state
                for (int i = 0; i < operationsToPerform; i++) {
                    // Increment counter using explicit lock
                    sharedState.incrementCounter();
                    
                    // Simulate some work to make the operation more realistic
                    if (i % 1000 == 0) {
                        Thread.yield();
                    }
                }
            } finally {
                // Signal completion
                completionLatch.countDown();
            }
        }
    }

    /**
     * Worker thread that does not synchronize access to shared state (unsafe).
     */
    private static class UnsafeWorkerThread implements Runnable {
        private final UnsafeSharedState sharedState;
        private final int operationsToPerform;
        private final CountDownLatch completionLatch;

        public UnsafeWorkerThread(UnsafeSharedState sharedState, int operationsToPerform, CountDownLatch completionLatch) {
            this.sharedState = sharedState;
            this.operationsToPerform = operationsToPerform;
            this.completionLatch = completionLatch;
        }

        @Override
        public void run() {
            try {
                // Perform operations on shared state
                for (int i = 0; i < operationsToPerform; i++) {
                    // Increment counter without synchronization (unsafe)
                    sharedState.incrementCounter();
                    
                    // Simulate some work to make the operation more realistic
                    if (i % 1000 == 0) {
                        Thread.yield();
                    }
                }
            } finally {
                // Signal completion
                completionLatch.countDown();
            }
        }
    }

    // ===== Shared state implementations =====

    /**
     * Shared state with synchronized methods for thread safety.
     */
    private static class SynchronizedSharedState {
        private int counter = 0;

        public synchronized void incrementCounter() {
            counter++;
        }

        public synchronized int getCounter() {
            return counter;
        }
    }

    /**
     * Shared state with explicit locks for thread safety.
     */
    private static class LockBasedSharedState {
        private int counter = 0;
        private final Lock lock = new ReentrantLock();

        public void incrementCounter() {
            lock.lock();
            try {
                counter++;
            } finally {
                lock.unlock();
            }
        }

        public int getCounter() {
            lock.lock();
            try {
                return counter;
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Shared state without synchronization (unsafe).
     */
    private static class UnsafeSharedState {
        private int counter = 0;

        public void incrementCounter() {
            counter++; // This is not thread-safe!
        }

        public int getCounter() {
            return counter;
        }
    }
}
