package com.cajunsystems.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Configuration properties for the Cajun actor system Spring integration.
 *
 * <p>These properties can be set in {@code application.yml} or {@code application.properties}
 * using the {@code cajun} prefix:
 *
 * <pre>{@code
 * cajun:
 *   thread-pool:
 *     workload: IO_BOUND
 *     executor-type: VIRTUAL
 *   backpressure:
 *     enabled: true
 *     strategy: DROP_OLDEST
 *     warning-threshold: 0.7
 * }</pre>
 */
@ConfigurationProperties(prefix = "cajun")
public class CajunProperties {

    @NestedConfigurationProperty
    private ThreadPool threadPool = new ThreadPool();

    @NestedConfigurationProperty
    private Backpressure backpressure = new Backpressure();

    public ThreadPool getThreadPool() {
        return threadPool;
    }

    public void setThreadPool(ThreadPool threadPool) {
        this.threadPool = threadPool;
    }

    public Backpressure getBackpressure() {
        return backpressure;
    }

    public void setBackpressure(Backpressure backpressure) {
        this.backpressure = backpressure;
    }

    /**
     * Thread pool configuration for the actor system.
     */
    public static class ThreadPool {

        /**
         * Workload optimization preset. One of: IO_BOUND, CPU_BOUND, MIXED.
         * When set, overrides executor-type and related settings.
         */
        private String workload;

        /**
         * Executor type. One of: VIRTUAL, FIXED, WORK_STEALING.
         * Defaults to VIRTUAL (Java 21 virtual threads).
         */
        private String executorType = "VIRTUAL";

        /**
         * Number of threads for a FIXED thread pool.
         * Defaults to the number of available processors.
         */
        private int fixedPoolSize = Runtime.getRuntime().availableProcessors();

        /**
         * Parallelism level for a WORK_STEALING thread pool.
         * Defaults to the number of available processors.
         */
        private int workStealingParallelism = Runtime.getRuntime().availableProcessors();

        /**
         * Whether to prefer virtual threads when possible.
         */
        private boolean preferVirtualThreads = true;

        /**
         * Number of threads in the scheduler (for delayed messages etc.).
         */
        private int schedulerThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

        /**
         * Whether actors share an executor or each gets their own.
         */
        private boolean useSharedExecutor = true;

        public String getWorkload() {
            return workload;
        }

        public void setWorkload(String workload) {
            this.workload = workload;
        }

        public String getExecutorType() {
            return executorType;
        }

        public void setExecutorType(String executorType) {
            this.executorType = executorType;
        }

        public int getFixedPoolSize() {
            return fixedPoolSize;
        }

        public void setFixedPoolSize(int fixedPoolSize) {
            this.fixedPoolSize = fixedPoolSize;
        }

        public int getWorkStealingParallelism() {
            return workStealingParallelism;
        }

        public void setWorkStealingParallelism(int workStealingParallelism) {
            this.workStealingParallelism = workStealingParallelism;
        }

        public boolean isPreferVirtualThreads() {
            return preferVirtualThreads;
        }

        public void setPreferVirtualThreads(boolean preferVirtualThreads) {
            this.preferVirtualThreads = preferVirtualThreads;
        }

        public int getSchedulerThreads() {
            return schedulerThreads;
        }

        public void setSchedulerThreads(int schedulerThreads) {
            this.schedulerThreads = schedulerThreads;
        }

        public boolean isUseSharedExecutor() {
            return useSharedExecutor;
        }

        public void setUseSharedExecutor(boolean useSharedExecutor) {
            this.useSharedExecutor = useSharedExecutor;
        }
    }

    /**
     * Default backpressure configuration applied to all actors unless overridden per-actor.
     */
    public static class Backpressure {

        /**
         * Whether to enable default backpressure configuration on all actors.
         */
        private boolean enabled = false;

        /**
         * Backpressure strategy. One of: BLOCK, DROP_NEW, DROP_OLDEST.
         */
        private String strategy = "BLOCK";

        /**
         * Mailbox fill ratio at which the WARNING state is triggered (0.0–1.0).
         */
        private float warningThreshold = 0.7f;

        /**
         * Mailbox fill ratio at which the CRITICAL state is triggered (0.0–1.0).
         */
        private float criticalThreshold = 0.9f;

        /**
         * Mailbox fill ratio at which the system returns to NORMAL state (0.0–1.0).
         */
        private float recoveryThreshold = 0.5f;

        /**
         * High watermark for adaptive mailbox resizing (0.0–1.0).
         */
        private float highWatermark = 0.8f;

        /**
         * Low watermark for adaptive mailbox resizing (0.0–1.0).
         */
        private float lowWatermark = 0.2f;

        /**
         * Minimum mailbox capacity.
         */
        private int minCapacity = 16;

        /**
         * Maximum mailbox capacity. 0 means unbounded.
         */
        private int maxCapacity = 0;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public float getWarningThreshold() {
            return warningThreshold;
        }

        public void setWarningThreshold(float warningThreshold) {
            this.warningThreshold = warningThreshold;
        }

        public float getCriticalThreshold() {
            return criticalThreshold;
        }

        public void setCriticalThreshold(float criticalThreshold) {
            this.criticalThreshold = criticalThreshold;
        }

        public float getRecoveryThreshold() {
            return recoveryThreshold;
        }

        public void setRecoveryThreshold(float recoveryThreshold) {
            this.recoveryThreshold = recoveryThreshold;
        }

        public float getHighWatermark() {
            return highWatermark;
        }

        public void setHighWatermark(float highWatermark) {
            this.highWatermark = highWatermark;
        }

        public float getLowWatermark() {
            return lowWatermark;
        }

        public void setLowWatermark(float lowWatermark) {
            this.lowWatermark = lowWatermark;
        }

        public int getMinCapacity() {
            return minCapacity;
        }

        public void setMinCapacity(int minCapacity) {
            this.minCapacity = minCapacity;
        }

        public int getMaxCapacity() {
            return maxCapacity;
        }

        public void setMaxCapacity(int maxCapacity) {
            this.maxCapacity = maxCapacity;
        }
    }
}
