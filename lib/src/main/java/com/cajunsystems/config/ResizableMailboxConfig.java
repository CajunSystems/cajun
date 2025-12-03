package com.cajunsystems.config;

/**
 * Configuration for a resizable mailbox that can grow or shrink based on load.
 * This extends the basic MailboxConfig with additional parameters for dynamic sizing.
 * 
 * @deprecated This class has been moved to {@link com.cajunsystems.mailbox.config.ResizableMailboxConfig}
 *             as part of the modularization effort. Please migrate to the new package.
 *             This class will be removed in v0.5.0.
 */
@Deprecated(since = "0.4.0", forRemoval = true)
public class ResizableMailboxConfig extends MailboxConfig {

    private static final float DEFAULT_HIGH_WATERMARK = 0.8f; // 80% capacity triggers grow
    private static final float DEFAULT_LOW_WATERMARK = 0.2f;  // 20% capacity triggers shrink
    private static final float DEFAULT_GROWTH_FACTOR = 2.0f;  // Double capacity when growing
    private static final float DEFAULT_SHRINK_FACTOR = 0.5f;  // Halve capacity when shrinking
    private static final long DEFAULT_METRICS_UPDATE_INTERVAL_MS = 1000; // 1 second
    private static final int DEFAULT_MIN_CAPACITY = 10; // Minimum size of the mailbox

    private int minCapacity;
    private float highWatermark;
    private float lowWatermark;
    private float growthFactor;
    private float shrinkFactor;
    private long metricsUpdateIntervalMs;
    private boolean resizable = true;

    /**
     * Creates a new ResizableMailboxConfig with default values.
     */
    public ResizableMailboxConfig() {
        this.minCapacity = DEFAULT_MIN_CAPACITY;
        this.highWatermark = DEFAULT_HIGH_WATERMARK;
        this.lowWatermark = DEFAULT_LOW_WATERMARK;
        this.growthFactor = DEFAULT_GROWTH_FACTOR;
        this.shrinkFactor = DEFAULT_SHRINK_FACTOR;
        this.metricsUpdateIntervalMs = DEFAULT_METRICS_UPDATE_INTERVAL_MS;
    }

    /**
     * Creates a new ResizableMailboxConfig with custom values.
     *
     * @param minCapacity The minimum capacity of the mailbox
     * @param highWatermark The high watermark level that triggers growth (0.0-1.0)
     * @param lowWatermark The low watermark level that triggers shrinking (0.0-1.0)
     * @param growthFactor The factor by which to grow the mailbox
     * @param shrinkFactor The factor by which to shrink the mailbox
     * @param metricsUpdateIntervalMs The interval at which to update metrics and check for resize
     */
    public ResizableMailboxConfig(
            int minCapacity,
            float highWatermark,
            float lowWatermark,
            float growthFactor,
            float shrinkFactor,
            long metricsUpdateIntervalMs
    ) {
        this.minCapacity = minCapacity;
        this.highWatermark = highWatermark;
        this.lowWatermark = lowWatermark;
        this.growthFactor = growthFactor;
        this.shrinkFactor = shrinkFactor;
        this.metricsUpdateIntervalMs = metricsUpdateIntervalMs;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isResizable() {
        return resizable;
    }

    /**
     * Sets whether this mailbox is resizable.
     *
     * @param resizable Whether the mailbox is resizable
     * @return This instance for method chaining
     */
    public ResizableMailboxConfig setResizable(boolean resizable) {
        this.resizable = resizable;
        return this;
    }

    /**
     * Gets the minimum capacity of the mailbox.
     *
     * @return The minimum capacity
     */
    public int getMinCapacity() {
        return minCapacity;
    }

    /**
     * Sets the minimum capacity of the mailbox.
     *
     * @param minCapacity The minimum capacity
     * @return This instance for method chaining
     */
    public ResizableMailboxConfig setMinCapacity(int minCapacity) {
        this.minCapacity = minCapacity;
        return this;
    }

    /**
     * Gets the high watermark level that triggers growth.
     *
     * @return The high watermark level (0.0-1.0)
     */
    public float getHighWatermark() {
        return highWatermark;
    }

    /**
     * Sets the high watermark level that triggers growth.
     *
     * @param highWatermark The high watermark level (0.0-1.0)
     * @return This instance for method chaining
     */
    public ResizableMailboxConfig setHighWatermark(float highWatermark) {
        this.highWatermark = highWatermark;
        return this;
    }

    /**
     * Gets the low watermark level that triggers shrinking.
     *
     * @return The low watermark level (0.0-1.0)
     */
    public float getLowWatermark() {
        return lowWatermark;
    }

    /**
     * Sets the low watermark level that triggers shrinking.
     *
     * @param lowWatermark The low watermark level (0.0-1.0)
     * @return This instance for method chaining
     */
    public ResizableMailboxConfig setLowWatermark(float lowWatermark) {
        this.lowWatermark = lowWatermark;
        return this;
    }

    /**
     * Gets the factor by which to grow the mailbox.
     *
     * @return The growth factor
     */
    public float getGrowthFactor() {
        return growthFactor;
    }

    /**
     * Sets the factor by which to grow the mailbox.
     *
     * @param growthFactor The growth factor
     * @return This instance for method chaining
     */
    public ResizableMailboxConfig setGrowthFactor(float growthFactor) {
        this.growthFactor = growthFactor;
        return this;
    }

    /**
     * Gets the factor by which to shrink the mailbox.
     *
     * @return The shrink factor
     */
    public float getShrinkFactor() {
        return shrinkFactor;
    }

    /**
     * Sets the factor by which to shrink the mailbox.
     *
     * @param shrinkFactor The shrink factor
     * @return This instance for method chaining
     */
    public ResizableMailboxConfig setShrinkFactor(float shrinkFactor) {
        this.shrinkFactor = shrinkFactor;
        return this;
    }

    /**
     * Gets the interval at which to update metrics and check for resize.
     *
     * @return The metrics update interval in milliseconds
     */
    public long getMetricsUpdateIntervalMs() {
        return metricsUpdateIntervalMs;
    }

    /**
     * Sets the interval at which to update metrics and check for resize.
     *
     * @param metricsUpdateIntervalMs The metrics update interval in milliseconds
     * @return This instance for method chaining
     */
    public ResizableMailboxConfig setMetricsUpdateIntervalMs(long metricsUpdateIntervalMs) {
        this.metricsUpdateIntervalMs = metricsUpdateIntervalMs;
        return this;
    }
    
    /**
     * Overrides the setInitialCapacity method to return ResizableMailboxConfig instead of MailboxConfig
     * for proper method chaining.
     * 
     * @param initialCapacity The initial capacity
     * @return This ResizableMailboxConfig instance
     */
    @Override
    public ResizableMailboxConfig setInitialCapacity(int initialCapacity) {
        super.setInitialCapacity(initialCapacity);
        return this;
    }
    
    /**
     * Overrides the setMaxCapacity method to return ResizableMailboxConfig instead of MailboxConfig
     * for proper method chaining.
     * 
     * @param maxCapacity The maximum capacity
     * @return This ResizableMailboxConfig instance
     */
    @Override
    public ResizableMailboxConfig setMaxCapacity(int maxCapacity) {
        super.setMaxCapacity(maxCapacity);
        return this;
    }
}
