package systems.cajun.config;

/**
 * Configuration for backpressure in the actor system.
 */
public class BackpressureConfig {
    // Default values for backpressure configuration
    public static final int DEFAULT_INITIAL_CAPACITY = 64;
    public static final int DEFAULT_MAX_CAPACITY = 10_000;
    public static final int DEFAULT_MIN_CAPACITY = 16;
    public static final long DEFAULT_METRICS_UPDATE_INTERVAL_MS = 1000;
    public static final float DEFAULT_GROWTH_FACTOR = 1.5f;
    public static final float DEFAULT_SHRINK_FACTOR = 0.75f;
    public static final float DEFAULT_HIGH_WATERMARK = 0.8f;
    public static final float DEFAULT_LOW_WATERMARK = 0.2f;

    private int initialCapacity;
    private int maxCapacity;
    private int minCapacity;
    private long metricsUpdateIntervalMs;
    private float growthFactor;
    private float shrinkFactor;
    private float highWatermark;
    private float lowWatermark;

    /**
     * Creates a new BackpressureConfig with default values.
     */
    public BackpressureConfig() {
        this.initialCapacity = DEFAULT_INITIAL_CAPACITY;
        this.maxCapacity = DEFAULT_MAX_CAPACITY;
        this.minCapacity = DEFAULT_MIN_CAPACITY;
        this.metricsUpdateIntervalMs = DEFAULT_METRICS_UPDATE_INTERVAL_MS;
        this.growthFactor = DEFAULT_GROWTH_FACTOR;
        this.shrinkFactor = DEFAULT_SHRINK_FACTOR;
        this.highWatermark = DEFAULT_HIGH_WATERMARK;
        this.lowWatermark = DEFAULT_LOW_WATERMARK;
    }

    /**
     * Sets the initial capacity for the backpressure buffer.
     * 
     * @param initialCapacity The initial capacity
     * @return This BackpressureConfig instance
     */
    public BackpressureConfig setInitialCapacity(int initialCapacity) {
        this.initialCapacity = initialCapacity;
        return this;
    }

    /**
     * Sets the maximum capacity for the backpressure buffer.
     * 
     * @param maxCapacity The maximum capacity
     * @return This BackpressureConfig instance
     */
    public BackpressureConfig setMaxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity;
        return this;
    }

    /**
     * Sets the minimum capacity for the backpressure buffer.
     * 
     * @param minCapacity The minimum capacity
     * @return This BackpressureConfig instance
     */
    public BackpressureConfig setMinCapacity(int minCapacity) {
        this.minCapacity = minCapacity;
        return this;
    }

    /**
     * Sets the metrics update interval in milliseconds.
     * 
     * @param metricsUpdateIntervalMs The metrics update interval
     * @return This BackpressureConfig instance
     */
    public BackpressureConfig setMetricsUpdateIntervalMs(long metricsUpdateIntervalMs) {
        this.metricsUpdateIntervalMs = metricsUpdateIntervalMs;
        return this;
    }

    /**
     * Sets the growth factor for the backpressure buffer.
     * 
     * @param growthFactor The growth factor
     * @return This BackpressureConfig instance
     */
    public BackpressureConfig setGrowthFactor(float growthFactor) {
        this.growthFactor = growthFactor;
        return this;
    }

    /**
     * Sets the shrink factor for the backpressure buffer.
     * 
     * @param shrinkFactor The shrink factor
     * @return This BackpressureConfig instance
     */
    public BackpressureConfig setShrinkFactor(float shrinkFactor) {
        this.shrinkFactor = shrinkFactor;
        return this;
    }

    /**
     * Sets the high watermark for the backpressure buffer.
     * 
     * @param highWatermark The high watermark
     * @return This BackpressureConfig instance
     */
    public BackpressureConfig setHighWatermark(float highWatermark) {
        this.highWatermark = highWatermark;
        return this;
    }

    /**
     * Sets the low watermark for the backpressure buffer.
     * 
     * @param lowWatermark The low watermark
     * @return This BackpressureConfig instance
     */
    public BackpressureConfig setLowWatermark(float lowWatermark) {
        this.lowWatermark = lowWatermark;
        return this;
    }

    /**
     * Gets the initial capacity for the backpressure buffer.
     * 
     * @return The initial capacity
     */
    public int getInitialCapacity() {
        return initialCapacity;
    }

    /**
     * Gets the maximum capacity for the backpressure buffer.
     * 
     * @return The maximum capacity
     */
    public int getMaxCapacity() {
        return maxCapacity;
    }

    /**
     * Gets the minimum capacity for the backpressure buffer.
     * 
     * @return The minimum capacity
     */
    public int getMinCapacity() {
        return minCapacity;
    }

    /**
     * Gets the metrics update interval in milliseconds.
     * 
     * @return The metrics update interval
     */
    public long getMetricsUpdateIntervalMs() {
        return metricsUpdateIntervalMs;
    }

    /**
     * Gets the growth factor for the backpressure buffer.
     * 
     * @return The growth factor
     */
    public float getGrowthFactor() {
        return growthFactor;
    }

    /**
     * Gets the shrink factor for the backpressure buffer.
     * 
     * @return The shrink factor
     */
    public float getShrinkFactor() {
        return shrinkFactor;
    }

    /**
     * Gets the high watermark for the backpressure buffer.
     * 
     * @return The high watermark
     */
    public float getHighWatermark() {
        return highWatermark;
    }

    /**
     * Gets the low watermark for the backpressure buffer.
     * 
     * @return The low watermark
     */
    public float getLowWatermark() {
        return lowWatermark;
    }
}
