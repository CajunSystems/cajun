package com.cajunsystems.dispatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Dispatcher schedules actor runners on a shared thread pool.
 * By default, uses virtual threads (Project Loom) for lightweight, efficient scheduling.
 * Can optionally use a fixed-size platform thread pool for environments without virtual thread support.
 */
public final class Dispatcher {
    
    private static final Logger logger = LoggerFactory.getLogger(Dispatcher.class);
    
    private final ExecutorService executor;
    private final String name;
    private volatile boolean shutdown = false;
    
    private Dispatcher(ExecutorService executor, String name) {
        this.executor = executor;
        this.name = name;
    }
    
    /**
     * Creates a dispatcher backed by virtual threads (recommended).
     * Virtual threads are lightweight and can handle millions of concurrent actors efficiently.
     * 
     * @return A new Dispatcher using virtual threads
     */
    public static Dispatcher virtualThreadDispatcher() {
        return virtualThreadDispatcher("dispatcher");
    }
    
    /**
     * Creates a dispatcher backed by virtual threads with a custom name.
     * 
     * @param name The name prefix for virtual threads
     * @return A new Dispatcher using virtual threads
     */
    public static Dispatcher virtualThreadDispatcher(String name) {
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual()
                .name(name + "-", 0)
                .factory()
        );
        logger.info("Created virtual thread dispatcher: {}", name);
        return new Dispatcher(executor, name);
    }
    
    /**
     * Creates a dispatcher backed by a fixed-size platform thread pool.
     * Use this as a fallback for environments without virtual thread support.
     * 
     * @param threads The number of platform threads in the pool
     * @return A new Dispatcher using platform threads
     */
    public static Dispatcher fixedThreadPoolDispatcher(int threads) {
        return fixedThreadPoolDispatcher(threads, "dispatcher");
    }
    
    /**
     * Creates a dispatcher backed by a fixed-size platform thread pool with a custom name.
     * 
     * @param threads The number of platform threads in the pool
     * @param name The name prefix for threads
     * @return A new Dispatcher using platform threads
     */
    public static Dispatcher fixedThreadPoolDispatcher(int threads, String name) {
        int poolSize = Math.max(1, threads);
        ExecutorService executor = Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(name + "-" + thread.threadId()); // Use threadId() instead of deprecated getId()
            thread.setDaemon(true);
            return thread;
        });
        logger.info("Created fixed thread pool dispatcher: {} with {} threads", name, poolSize);
        return new Dispatcher(executor, name);
    }
    
    /**
     * Schedules an actor runner for execution.
     * The runner will be executed on the dispatcher's thread pool.
     * 
     * @param task The runnable task (typically an ActorRunner) to schedule
     */
    public void schedule(Runnable task) {
        if (shutdown) {
            logger.warn("Attempted to schedule task on shutdown dispatcher: {}", name);
            return;
        }
        try {
            executor.execute(task);
        } catch (Exception e) {
            logger.error("Failed to schedule task on dispatcher {}: {}", name, e.getMessage(), e);
        }
    }
    
    /**
     * Initiates shutdown of the dispatcher.
     * No new tasks will be accepted after this call.
     * Currently executing tasks will be allowed to complete.
     */
    public void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        logger.info("Shutting down dispatcher: {}", name);
        executor.shutdown();
    }
    
    /**
     * Attempts to stop all actively executing tasks and halts the processing of waiting tasks.
     * 
     * @return true if all tasks terminated, false if timeout elapsed
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        try {
            return executor.awaitTermination(timeout, unit);
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for dispatcher {} termination", name);
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    /**
     * Returns whether this dispatcher has been shut down.
     * 
     * @return true if shutdown has been initiated
     */
    public boolean isShutdown() {
        return shutdown;
    }
    
    /**
     * Returns the name of this dispatcher.
     * 
     * @return The dispatcher name
     */
    public String getName() {
        return name;
    }
}
