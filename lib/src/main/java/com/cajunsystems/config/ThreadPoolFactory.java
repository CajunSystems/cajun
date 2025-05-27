package com.cajunsystems.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Factory for creating thread pools used in the actor system.
 * This class provides centralized creation and configuration for all thread pools used in the system,
 * making it easier to tune performance and resource usage.
 */
public class ThreadPoolFactory {
    // Default values
    private static final int DEFAULT_SCHEDULER_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    private static final int DEFAULT_SCHEDULER_SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final boolean DEFAULT_USE_SHARED_EXECUTOR = true;
    private static final boolean DEFAULT_PREFER_VIRTUAL_THREADS = true;
    private static final boolean DEFAULT_USE_STRUCTURED_CONCURRENCY = true;
    private static final int DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_ACTOR_BATCH_SIZE = 10;

    // Scheduler configuration
    private int schedulerThreads = DEFAULT_SCHEDULER_THREADS;
    private int schedulerShutdownTimeoutSeconds = DEFAULT_SCHEDULER_SHUTDOWN_TIMEOUT_SECONDS;
    private boolean useNamedThreads = true;

    // Actor execution configuration
    private boolean useSharedExecutor = DEFAULT_USE_SHARED_EXECUTOR;
    private boolean preferVirtualThreads = DEFAULT_PREFER_VIRTUAL_THREADS;
    private boolean useStructuredConcurrency = DEFAULT_USE_STRUCTURED_CONCURRENCY;
    private int actorShutdownTimeoutSeconds = DEFAULT_SHUTDOWN_TIMEOUT_SECONDS;
    private int actorBatchSize = DEFAULT_ACTOR_BATCH_SIZE;

    // Thread pool type configuration
    private ThreadPoolType executorType = ThreadPoolType.VIRTUAL;
    private int fixedPoolSize = Runtime.getRuntime().availableProcessors();
    private int workStealingParallelism = Runtime.getRuntime().availableProcessors();

    /**
     * Enum defining the types of thread pools that can be used.
     */
    public enum ThreadPoolType {
        /**
         * Uses virtual threads (Java 21+) for high concurrency with low overhead.
         * Best for IO-bound workloads with many actors.
         */
        VIRTUAL,

        /**
         * Uses a fixed thread pool with a specified number of threads.
         * Good for CPU-bound workloads with a known optimal thread count.
         */
        FIXED,

        /**
         * Uses a work-stealing thread pool for balanced workloads.
         * Good for mixed workloads with varying CPU/IO characteristics.
         */
        WORK_STEALING
    }

    /**
     * Enum defining the types of workloads that the actor system can be optimized for.
     */
    public enum WorkloadType {
        /**
         * Many actors doing mostly IO operations.
         * Optimizes for high concurrency with virtual threads.
         */
        IO_BOUND,

        /**
         * Fewer actors doing intensive computation.
         * Optimizes for CPU utilization with a fixed thread pool.
         */
        CPU_BOUND,

        /**
         * A mix of IO and CPU operations.
         * Uses a work-stealing pool for balanced performance.
         */
        MIXED
    }

    /**
     * Optimizes the thread pool configuration for a specific workload type.
     *
     * @param workloadType The type of workload to optimize for
     * @return This ThreadPoolFactory instance for method chaining
     */
    public ThreadPoolFactory optimizeFor(WorkloadType workloadType) {
        switch (workloadType) {
            case IO_BOUND:
                return setExecutorType(ThreadPoolType.VIRTUAL)
                        .setPreferVirtualThreads(true)
                        .setUseStructuredConcurrency(true);
            case CPU_BOUND:
                return setExecutorType(ThreadPoolType.FIXED)
                        .setFixedPoolSize(Runtime.getRuntime().availableProcessors())
                        .setPreferVirtualThreads(false);
            case MIXED:
                return setExecutorType(ThreadPoolType.WORK_STEALING)
                        .setPreferVirtualThreads(true);
            default:
                throw new IllegalArgumentException("Unknown workload type: " + workloadType);
        }
    }

    /**
     * Creates a new ThreadPoolFactory with default settings.
     */
    public ThreadPoolFactory() {
        // Use defaults
    }

    /**
     * Creates a scheduled executor service based on the current configuration.
     *
     * @param poolName Name prefix for the threads in this pool
     * @return A new scheduled executor service
     */
    public ScheduledExecutorService createScheduledExecutorService(String poolName) {
        if (useNamedThreads) {
            return Executors.newScheduledThreadPool(schedulerThreads,
                    createNamedThreadFactory(poolName + "-scheduler"));
        } else {
            return Executors.newScheduledThreadPool(schedulerThreads);
        }
    }

    /**
     * Creates an executor service based on the current configuration.
     *
     * @param poolName Name prefix for the threads in this pool
     * @return A new executor service
     */
    public ExecutorService createExecutorService(String poolName) {
        switch (executorType) {
            case VIRTUAL:
                if (useNamedThreads) {
                    return Executors.newThreadPerTaskExecutor(
                            Thread.ofVirtual().name(poolName + "-", 0).factory());
                } else {
                    return Executors.newVirtualThreadPerTaskExecutor();
                }
            case FIXED:
                if (preferVirtualThreads) {
                    // Use platform threads with virtual thread characteristics
                    if (useNamedThreads) {
                        return Executors.newFixedThreadPool(fixedPoolSize,
                                createNamedThreadFactory(STR."\{poolName}-worker"));
                    } else {
                        return Executors.newFixedThreadPool(fixedPoolSize);
                    }
                } else {
                    if (useNamedThreads) {
                        return Executors.newFixedThreadPool(fixedPoolSize,
                                createNamedThreadFactory(STR."\{poolName}-worker"));
                    } else {
                        return Executors.newFixedThreadPool(fixedPoolSize);
                    }
                }
            case WORK_STEALING:
                return Executors.newWorkStealingPool(workStealingParallelism);
            default:
                throw new IllegalStateException("Unknown executor type: " + executorType);
        }
    }

    /**
     * Creates a named thread factory for better thread identification in logs and profilers.
     *
     * @param prefix The prefix for thread names
     * @return A thread factory that creates named threads
     */
    private ThreadFactory createNamedThreadFactory(String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                if (executorType == ThreadPoolType.VIRTUAL || preferVirtualThreads) {
                    return Thread.ofVirtual()
                            .name(STR."\{prefix}-\{threadNumber.getAndIncrement()}")
                            .unstarted(r);
                } else {
                    return new Thread(r, STR."\{prefix}-\{threadNumber.getAndIncrement()}");
                }
            }
        };
    }

    // Getters and setters

    public int getSchedulerThreads() {
        return schedulerThreads;
    }

    public ThreadPoolFactory setSchedulerThreads(int schedulerThreads) {
        this.schedulerThreads = schedulerThreads;
        return this;
    }

    public int getSchedulerShutdownTimeoutSeconds() {
        return schedulerShutdownTimeoutSeconds;
    }

    public ThreadPoolFactory setSchedulerShutdownTimeoutSeconds(int schedulerShutdownTimeoutSeconds) {
        this.schedulerShutdownTimeoutSeconds = schedulerShutdownTimeoutSeconds;
        return this;
    }

    public boolean isUseNamedThreads() {
        return useNamedThreads;
    }

    public ThreadPoolFactory setUseNamedThreads(boolean useNamedThreads) {
        this.useNamedThreads = useNamedThreads;
        return this;
    }

    public boolean isUseSharedExecutor() {
        return useSharedExecutor;
    }

    public ThreadPoolFactory setUseSharedExecutor(boolean useSharedExecutor) {
        this.useSharedExecutor = useSharedExecutor;
        return this;
    }

    public boolean isPreferVirtualThreads() {
        return preferVirtualThreads;
    }

    public ThreadPoolFactory setPreferVirtualThreads(boolean preferVirtualThreads) {
        this.preferVirtualThreads = preferVirtualThreads;
        return this;
    }

    public boolean isUseStructuredConcurrency() {
        return useStructuredConcurrency;
    }

    public ThreadPoolFactory setUseStructuredConcurrency(boolean useStructuredConcurrency) {
        this.useStructuredConcurrency = useStructuredConcurrency;
        return this;
    }

    public int getActorShutdownTimeoutSeconds() {
        return actorShutdownTimeoutSeconds;
    }

    public ThreadPoolFactory setActorShutdownTimeoutSeconds(int actorShutdownTimeoutSeconds) {
        this.actorShutdownTimeoutSeconds = actorShutdownTimeoutSeconds;
        return this;
    }

    public int getActorBatchSize() {
        return actorBatchSize;
    }

    public ThreadPoolFactory setActorBatchSize(int actorBatchSize) {
        this.actorBatchSize = actorBatchSize;
        return this;
    }

    public ThreadPoolType getExecutorType() {
        return executorType;
    }

    public ThreadPoolFactory setExecutorType(ThreadPoolType executorType) {
        this.executorType = executorType;
        return this;
    }

    public int getFixedPoolSize() {
        return fixedPoolSize;
    }

    public ThreadPoolFactory setFixedPoolSize(int fixedPoolSize) {
        this.fixedPoolSize = fixedPoolSize;
        return this;
    }

    public int getWorkStealingParallelism() {
        return workStealingParallelism;
    }

    public ThreadPoolFactory setWorkStealingParallelism(int workStealingParallelism) {
        this.workStealingParallelism = workStealingParallelism;
        return this;
    }
}
