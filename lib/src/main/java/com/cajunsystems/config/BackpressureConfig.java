package com.cajunsystems.config;

import com.cajunsystems.backpressure.BackpressureStrategy;
import com.cajunsystems.backpressure.CustomBackpressureHandler;

/**
 * Configuration for backpressure in the actor system.
 */
public class BackpressureConfig {
    // Default values for backpressure configuration
    public static final int DEFAULT_MIN_CAPACITY = 16;
    public static final long DEFAULT_METRICS_UPDATE_INTERVAL_MS = 1000;
    public static final float DEFAULT_GROWTH_FACTOR = 1.5f;
    public static final float DEFAULT_SHRINK_FACTOR = 0.75f;
    public static final float DEFAULT_HIGH_WATERMARK = 0.8f;
    public static final float DEFAULT_LOW_WATERMARK = 0.2f;

    private int minCapacity;
    private long metricsUpdateIntervalMs;
    private float growthFactor;
    private float shrinkFactor;
    private float highWatermark;
    private float lowWatermark;
    private boolean enabled = true;
    private float warningThreshold = 0.7f;
    private float criticalThreshold = 0.9f;
    private float recoveryThreshold = 0.5f;
    private BackpressureStrategy strategy = BackpressureStrategy.BLOCK;
    private CustomBackpressureHandler<?> customHandler;
    private int maxEventsToKeep = 20;
    private int maxCapacity = Integer.MAX_VALUE;

    /**
     * Creates a new BackpressureConfig with default values.
     */
    public BackpressureConfig() {
        this.minCapacity = DEFAULT_MIN_CAPACITY;
        this.metricsUpdateIntervalMs = DEFAULT_METRICS_UPDATE_INTERVAL_MS;
        this.growthFactor = DEFAULT_GROWTH_FACTOR;
        this.shrinkFactor = DEFAULT_SHRINK_FACTOR;
        this.highWatermark = DEFAULT_HIGH_WATERMARK;
        this.lowWatermark = DEFAULT_LOW_WATERMARK;
        this.enabled = true;
        this.warningThreshold = 0.7f;
        this.criticalThreshold = 0.9f;
        this.recoveryThreshold = 0.5f;
        this.strategy = BackpressureStrategy.BLOCK;
        this.customHandler = null;
        this.maxEventsToKeep = 20;
        this.maxCapacity = Integer.MAX_VALUE;
    }

    /**
     * Sets whether backpressure is enabled.
     * 
     * @param enabled Whether backpressure is enabled
     * @return This BackpressureConfig instance
     */
    public BackpressureConfig setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * Gets whether backpressure is enabled.
     * 
     * @return Whether backpressure is enabled
     */
    public boolean isEnabled() {
        return enabled;
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

    // Initial capacity and max capacity are now part of MailboxConfig

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
    
    /**
     * Sets the warning threshold at which the actor signals a warning state.
     * 
     * @param warningThreshold The warning threshold (0.0 to 1.0)
     * @return This BackpressureConfig instance
     */
    public BackpressureConfig setWarningThreshold(float warningThreshold) {
        this.warningThreshold = warningThreshold;
        return this;
    }
    
    /**
     * Gets the warning threshold for the actor.
     * 
     * @return The warning threshold
     */
    public float getWarningThreshold() {
        return warningThreshold;
    }
    
    /**
     * Sets the critical threshold at which the actor signals a critical state.
     * 
     * @param criticalThreshold The critical threshold (0.0 to 1.0)
     * @return This BackpressureConfig instance
     */
    public BackpressureConfig setCriticalThreshold(float criticalThreshold) {
        this.criticalThreshold = criticalThreshold;
        return this;
    }
    
    /**
     * Gets the critical threshold for the actor.
     * 
     * @return The critical threshold
     */
    public float getCriticalThreshold() {
        return criticalThreshold;
    }
    
    /**
     * Sets the recovery threshold at which the actor returns to a normal state.
     * 
     * @param recoveryThreshold The recovery threshold (0.0 to 1.0)
     * @return This BackpressureConfig instance
     */
    public BackpressureConfig setRecoveryThreshold(float recoveryThreshold) {
        this.recoveryThreshold = recoveryThreshold;
        return this;
    }
    
    /**
     * Gets the recovery threshold for the actor.
     * 
     * @return The recovery threshold
     */
    public float getRecoveryThreshold() {
        return recoveryThreshold;
    }
    
    /**
     * Sets the backpressure strategy to use.
     * 
     * @param strategy The strategy to use
     * @return This BackpressureConfig instance
     */
    public BackpressureConfig setStrategy(BackpressureStrategy strategy) {
        this.strategy = strategy;
        return this;
    }
    
    /**
     * Gets the backpressure strategy.
     * 
     * @return The strategy
     */
    public BackpressureStrategy getStrategy() {
        return strategy;
    }
    
    /**
     * Sets the custom handler for backpressure.
     * 
     * @param customHandler The custom handler
     * @return This BackpressureConfig instance
     */
    public BackpressureConfig setCustomHandler(CustomBackpressureHandler<?> customHandler) {
        this.customHandler = customHandler;
        return this;
    }
    
    /**
     * Gets the custom backpressure handler.
     * 
     * @return The custom handler
     */
    public CustomBackpressureHandler<?> getCustomHandler() {
        return customHandler;
    }
    
    /**
     * Sets the maximum number of backpressure events to keep in history.
     * 
     * @param maxEventsToKeep The maximum number of events
     * @return This BackpressureConfig instance
     */
    public BackpressureConfig setMaxEventsToKeep(int maxEventsToKeep) {
        this.maxEventsToKeep = maxEventsToKeep;
        return this;
    }
    
    /**
     * Gets the maximum number of backpressure events to keep.
     * 
     * @return The maximum number of events
     */
    public int getMaxEventsToKeep() {
        return maxEventsToKeep;
    }
    
    /**
     * Sets the maximum capacity for the mailbox.
     * 
     * @param maxCapacity The maximum capacity
     * @return This BackpressureConfig instance
     */
    public BackpressureConfig setMaxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity;
        return this;
    }
    
    /**
     * Gets the maximum capacity for the mailbox.
     * 
     * @return The maximum capacity
     */
    public int getMaxCapacity() {
        return maxCapacity;
    }
    
    /**
     * Builder for creating BackpressureConfig instances.
     */
    public static class Builder {
        private final BackpressureConfig config = new BackpressureConfig();
        
        /**
         * Sets whether backpressure is enabled.
         * 
         * @param enabled Whether backpressure is enabled
         * @return This builder instance
         */
        public Builder enabled(boolean enabled) {
            config.setEnabled(enabled);
            return this;
        }
        
        /**
         * Sets the minimum capacity for the backpressure buffer.
         * 
         * @param minCapacity The minimum capacity
         * @return This builder instance
         */
        public Builder minCapacity(int minCapacity) {
            config.setMinCapacity(minCapacity);
            return this;
        }
        
        /**
         * Sets the metrics update interval in milliseconds.
         * 
         * @param metricsUpdateIntervalMs The metrics update interval
         * @return This builder instance
         */
        public Builder metricsUpdateIntervalMs(long metricsUpdateIntervalMs) {
            config.setMetricsUpdateIntervalMs(metricsUpdateIntervalMs);
            return this;
        }
        
        /**
         * Sets the growth factor for the backpressure buffer.
         * 
         * @param growthFactor The growth factor
         * @return This builder instance
         */
        public Builder growthFactor(float growthFactor) {
            config.setGrowthFactor(growthFactor);
            return this;
        }
        
        /**
         * Sets the shrink factor for the backpressure buffer.
         * 
         * @param shrinkFactor The shrink factor
         * @return This builder instance
         */
        public Builder shrinkFactor(float shrinkFactor) {
            config.setShrinkFactor(shrinkFactor);
            return this;
        }
        
        /**
         * Sets the high watermark for the backpressure buffer.
         * 
         * @param highWatermark The high watermark
         * @return This builder instance
         */
        public Builder highWatermark(float highWatermark) {
            config.setHighWatermark(highWatermark);
            return this;
        }
        
        /**
         * Sets the low watermark for the backpressure buffer.
         * 
         * @param lowWatermark The low watermark
         * @return This builder instance
         */
        public Builder lowWatermark(float lowWatermark) {
            config.setLowWatermark(lowWatermark);
            return this;
        }
        
        /**
         * Sets the warning threshold at which the actor signals a warning state.
         * 
         * @param warningThreshold The warning threshold (0.0 to 1.0)
         * @return This builder instance
         */
        public Builder warningThreshold(float warningThreshold) {
            config.setWarningThreshold(warningThreshold);
            return this;
        }
        
        /**
         * Sets the critical threshold at which the actor signals a critical state.
         * 
         * @param criticalThreshold The critical threshold (0.0 to 1.0)
         * @return This builder instance
         */
        public Builder criticalThreshold(float criticalThreshold) {
            config.setCriticalThreshold(criticalThreshold);
            return this;
        }
        
        /**
         * Sets the recovery threshold at which the actor returns to a normal state.
         * 
         * @param recoveryThreshold The recovery threshold (0.0 to 1.0)
         * @return This builder instance
         */
        public Builder recoveryThreshold(float recoveryThreshold) {
            config.setRecoveryThreshold(recoveryThreshold);
            return this;
        }
        
        /**
         * Sets the backpressure strategy to use.
         * 
         * @param strategy The strategy to use
         * @return This builder instance
         */
        public Builder strategy(BackpressureStrategy strategy) {
            config.setStrategy(strategy);
            return this;
        }
        
        /**
         * Sets the custom handler for backpressure.
         * 
         * @param customHandler The custom handler
         * @return This builder instance
         */
        public Builder customHandler(CustomBackpressureHandler<?> customHandler) {
            config.setCustomHandler(customHandler);
            return this;
        }
        
        /**
         * Sets the maximum number of backpressure events to keep in history.
         * 
         * @param maxEventsToKeep The maximum number of events
         * @return This builder instance
         */
        public Builder maxEventsToKeep(int maxEventsToKeep) {
            config.setMaxEventsToKeep(maxEventsToKeep);
            return this;
        }
        
        /**
         * Sets the maximum capacity for the mailbox.
         * 
         * @param maxCapacity The maximum capacity
         * @return This builder instance
         */
        public Builder maxCapacity(int maxCapacity) {
            config.setMaxCapacity(maxCapacity);
            return this;
        }
        
        /**
         * Builds the BackpressureConfig instance.
         * 
         * @return The built BackpressureConfig
         */
        public BackpressureConfig build() {
            return config;
        }
    }
}
