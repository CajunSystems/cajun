package com.cajunsystems.backpressure;


import com.cajunsystems.Actor;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.config.BackpressureConfig;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Builder for configuring backpressure settings on an actor.
 * This provides a fluent API for backpressure configuration.
 * Supports both direct Actor configuration and PID-based configuration through the ActorSystem.
 *
 * @param <T> The message type of the actor
 */
public class BackpressureBuilder<T> {
    private final Actor<T> actor;
    private final Pid pid;
    private final BackpressureConfig backpressureConfig;
    private Consumer<BackpressureEvent> eventCallback;
    private BackpressureManager<T> manager;
    
    /**
     * Creates a new builder for the specified actor.
     *
     * @param actor The actor to configure
     */
    public BackpressureBuilder(Actor<T> actor) {
        this.actor = actor;
        this.pid = null;
        this.backpressureConfig = new BackpressureConfig();
    }
    
    /**
     * Creates a new builder for the actor identified by the specified PID.
     *
     * @param system The actor system
     * @param pid The PID of the actor to configure
     */
    public BackpressureBuilder(ActorSystem system, Pid pid) {
        this.actor = null;
        this.pid = pid;
        this.backpressureConfig = new BackpressureConfig();
        this.manager = system.getBackpressureMonitor().getBackpressureManager(pid);
    }
    
    /**
     * Enables backpressure on the actor.
     * Backpressure is enabled by default.
     *
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> enable() {
        backpressureConfig.setEnabled(true);
        return this;
    }
    
    /**
     * Disables backpressure on the actor.
     *
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> disable() {
        backpressureConfig.setEnabled(false);
        return this;
    }
    
    /**
     * Sets the warning threshold as a fill ratio (0.0 to 1.0).
     * When the mailbox fill ratio exceeds this threshold, the actor
     * will transition to the WARNING state.
     *
     * @param threshold The warning threshold (default: 0.7)
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> withWarningThreshold(float threshold) {
        backpressureConfig.setWarningThreshold(threshold);
        return this;
    }
    
    /**
     * Sets the critical threshold as a fill ratio (0.0 to 1.0).
     * When the mailbox fill ratio exceeds this threshold, the actor
     * will transition to the CRITICAL state.
     *
     * @param threshold The critical threshold (default: 0.9)
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> withCriticalThreshold(float threshold) {
        backpressureConfig.setCriticalThreshold(threshold);
        return this;
    }
    
    /**
     * Sets the recovery threshold as a fill ratio (0.0 to 1.0).
     * When the mailbox fill ratio falls below this threshold after
     * being in WARNING or CRITICAL state, the actor will transition
     * to the RECOVERY state.
     *
     * @param threshold The recovery threshold (default: 0.5)
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> withRecoveryThreshold(float threshold) {
        backpressureConfig.setRecoveryThreshold(threshold);
        return this;
    }
    
    /**
     * Sets the maximum number of backpressure events to keep in history.
     *
     * @param maxEvents The maximum number of events to keep (default: 20)
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> withMaxEventsToKeep(int maxEvents) {
        backpressureConfig.setMaxEventsToKeep(maxEvents);
        return this;
    }
    
    /**
     * Sets the backpressure strategy to use.
     *
     * @param strategy The strategy to use (default: BLOCK)
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> withStrategy(BackpressureStrategy strategy) {
        backpressureConfig.setStrategy(strategy);
        return this;
    }
    
    /**
     * Sets a custom handler for backpressure.
     * This is only used if the strategy is set to CUSTOM.
     *
     * @param handler The custom handler to use
     * @return This builder for method chaining
     */
    @SuppressWarnings("unchecked")
    public BackpressureBuilder<T> withCustomHandler(CustomBackpressureHandler<T> handler) {
        backpressureConfig.setCustomHandler(handler);
        backpressureConfig.setStrategy(BackpressureStrategy.CUSTOM);
        return this;
    }
    
    /**
     * Sets the interval for updating metrics and checking for resize,
     * in milliseconds.
     *
     * @param intervalMs The interval in milliseconds (default: 1000)
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> withMetricsUpdateInterval(long intervalMs) {
        backpressureConfig.setMetricsUpdateIntervalMs(intervalMs);
        return this;
    }
    
    /**
     * Sets the high watermark for the backpressure buffer.
     *
     * @param highWatermark The high watermark (0.0 to 1.0)
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> withHighWatermark(float highWatermark) {
        backpressureConfig.setHighWatermark(highWatermark);
        return this;
    }
    
    /**
     * Sets the low watermark for the backpressure buffer.
     *
     * @param lowWatermark The low watermark (0.0 to 1.0)
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> withLowWatermark(float lowWatermark) {
        backpressureConfig.setLowWatermark(lowWatermark);
        return this;
    }
    
    /**
     * Sets the callback to be notified of backpressure events.
     * This callback will receive detailed information about the state
     * of the mailbox and can be used to adapt sending behavior.
     *
     * @param callback The callback to invoke with event information
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> withCallback(Consumer<BackpressureEvent> callback) {
        this.eventCallback = callback;
        return this;
    }
    
    /**
     * Sets the growth factor for the backpressure buffer.
     *
     * @param factor The growth factor (default: 2.0)
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> withGrowthFactor(float factor) {
        backpressureConfig.setGrowthFactor(factor);
        return this;
    }
    
    /**
     * Sets the shrink factor for the backpressure buffer.
     *
     * @param factor The shrink factor (default: 0.5)
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> withShrinkFactor(float factor) {
        backpressureConfig.setShrinkFactor(factor);
        return this;
    }
    
    /**
     * Sets the minimum capacity for the backpressure buffer.
     *
     * @param capacity The minimum capacity
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> withMinCapacity(int capacity) {
        backpressureConfig.setMinCapacity(capacity);
        return this;
    }
    
    /**
     * Sets the maximum capacity for the mailbox.
     *
     * @param capacity The maximum capacity
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> withMaxCapacity(int capacity) {
        backpressureConfig.setMaxCapacity(capacity);
        return this;
    }
    
    /**
     * Sets the interval at which backpressure metrics are updated.
     *
     * @param duration The update interval
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> withMetricsUpdateInterval(Duration duration) {
        return withMetricsUpdateInterval(duration.toMillis());
    }

    /**
     * Sets the interval at which backpressure metrics are updated.
     *
     * @param time The update interval time
     * @param unit The time unit
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> withMetricsUpdateInterval(long time, TimeUnit unit) {
        return withMetricsUpdateInterval(unit.toMillis(time));
    }

    /**
     * Creates a preset configuration for a time-critical actor that
     * prioritizes newer messages over older ones when under backpressure.
     *
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> presetTimeCritical() {
        return this
            .withStrategy(BackpressureStrategy.DROP_OLDEST)
            .withHighWatermark(0.9f)
            .withLowWatermark(0.6f)
            .withWarningThreshold(0.75f)
            .withMetricsUpdateInterval(500, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a preset configuration for a reliable actor that
     * never drops messages but may apply backpressure aggressively.
     *
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> presetReliable() {
        return this
            .withStrategy(BackpressureStrategy.BLOCK)
            .withHighWatermark(0.7f)
            .withLowWatermark(0.4f)
            .withWarningThreshold(0.5f)
            .withMetricsUpdateInterval(200, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a preset configuration for a high-throughput actor that
     * focuses on processing as many messages as possible, potentially
     * dropping new messages when overloaded.
     *
     * @return This builder for method chaining
     */
    public BackpressureBuilder<T> presetHighThroughput() {
        return this
            .withStrategy(BackpressureStrategy.DROP_NEW)
            .withHighWatermark(0.95f)
            .withLowWatermark(0.7f)
            .withWarningThreshold(0.85f)
            .withGrowthFactor(2.0f)
            .withMetricsUpdateInterval(1, TimeUnit.SECONDS);
    }
    
    /**
     * Applies the configuration to the actor and returns the actor.
     * This method finalizes the backpressure configuration.
     *
     * @return The configured actor or null if using PID-based configuration
     */
    public Actor<T> apply() {
        if (actor != null) {
            // Direct actor configuration
            actor.initializeBackpressure(backpressureConfig, eventCallback);
            return actor;
        } else if (manager != null) {
            // PID-based configuration through ActorSystem
            if (backpressureConfig.isEnabled()) {
                manager.enable(backpressureConfig);
            } else {
                manager.disable();
            }
            
            // Apply strategy if set
            if (backpressureConfig.getStrategy() != null) {
                manager.setStrategy(backpressureConfig.getStrategy());
            }
            
            // Apply custom handler if set
            if (backpressureConfig.getStrategy() == BackpressureStrategy.CUSTOM && 
                backpressureConfig.getCustomHandler() != null) {
                // We need to use raw types here because of Java's generic type erasure
                // The CustomBackpressureHandler interface is parameterized but we can't check
                // the actual type parameter at runtime
                @SuppressWarnings("unchecked") // This is actually necessary here
                CustomBackpressureHandler<T> handler = 
                    (CustomBackpressureHandler<T>) backpressureConfig.getCustomHandler();
                manager.setCustomHandler(handler);
            }
            
            // Apply callback if set
            if (eventCallback != null) {
                manager.setCallback(eventCallback);
            }
            
            // Apply max events to keep if set
            if (backpressureConfig.getMaxEventsToKeep() > 0) {
                manager.setMaxEventsToKeep(backpressureConfig.getMaxEventsToKeep());
            }
            
            return null;
        }
        
        return null;
    }
    
    /**
     * Gets the current backpressure status for the actor.
     * Only available when using PID-based configuration.
     *
     * @return The current backpressure status, or null if not using PID-based configuration
     */
    public BackpressureStatus getStatus() {
        return manager != null ? manager.getStatus() : null;
    }
    
    /**
     * Gets the PID of the actor being configured.
     * Only available when using PID-based configuration.
     *
     * @return The actor's PID, or null if not using PID-based configuration
     */
    public Pid getPid() {
        return pid;
    }
}
