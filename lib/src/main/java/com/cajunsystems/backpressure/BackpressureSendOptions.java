package com.cajunsystems.backpressure;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Configuration options for sending messages with backpressure awareness.
 * This class provides a fluent API for configuring how a message should be
 * sent when the actor might be under backpressure.
 */
public class BackpressureSendOptions {
    private Duration timeout = Duration.ofSeconds(1);
    private RetryPolicy retryPolicy = RetryPolicy.NONE;
    private int retryAttempts = 0;
    private Duration retryDelay = Duration.ofMillis(100);
    private boolean blockUntilAccepted = true;
    private BackpressureStrategy fallbackStrategy = BackpressureStrategy.BLOCK;
    private boolean highPriority = false;
    
    /**
     * Creates a new instance with default options.
     * - 1 second timeout
     * - No retries
     * - Blocking until accepted
     * - BLOCK fallback strategy
     * - Normal priority
     */
    public BackpressureSendOptions() {
    }
    
    /**
     * Creates a new instance with specified priority.
     *
     * @param highPriority Whether the message is high priority
     */
    public BackpressureSendOptions(boolean highPriority) {
        this.highPriority = highPriority;
    }
    
    /**
     * Sets the timeout for sending a message.
     *
     * @param timeout The maximum time to wait
     * @return This instance for method chaining
     */
    public BackpressureSendOptions withTimeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }
    
    /**
     * Sets the timeout for sending a message.
     *
     * @param timeout The maximum time to wait
     * @param unit The time unit
     * @return This instance for method chaining
     */
    public BackpressureSendOptions withTimeout(long timeout, TimeUnit unit) {
        this.timeout = Duration.ofNanos(unit.toNanos(timeout));
        return this;
    }
    
    /**
     * Sets the retry policy for sending a message.
     *
     * @param retryPolicy The retry policy to use
     * @return This instance for method chaining
     */
    public BackpressureSendOptions withRetryPolicy(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
        return this;
    }
    
    /**
     * Sets the number of retry attempts.
     *
     * @param retryAttempts The number of retry attempts
     * @return This instance for method chaining
     */
    public BackpressureSendOptions withRetryAttempts(int retryAttempts) {
        this.retryAttempts = retryAttempts;
        return this;
    }
    
    /**
     * Sets the delay between retry attempts.
     *
     * @param retryDelay The delay between retry attempts
     * @return This instance for method chaining
     */
    public BackpressureSendOptions withRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
        return this;
    }
    
    /**
     * Sets whether to block until the message is accepted.
     *
     * @param blockUntilAccepted Whether to block until the message is accepted
     * @return This instance for method chaining
     */
    public BackpressureSendOptions withBlockUntilAccepted(boolean blockUntilAccepted) {
        this.blockUntilAccepted = blockUntilAccepted;
        return this;
    }
    
    /**
     * Sets whether to block until the message is accepted.
     * This is an alias for withBlockUntilAccepted for backward compatibility.
     *
     * @param blockUntilAccepted Whether to block until the message is accepted
     * @return This instance for method chaining
     */
    public BackpressureSendOptions setBlockUntilAccepted(boolean blockUntilAccepted) {
        return withBlockUntilAccepted(blockUntilAccepted);
    }
    
    /**
     * Sets the fallback strategy to use when the message cannot be sent.
     *
     * @param fallbackStrategy The fallback strategy
     * @return This instance for method chaining
     */
    public BackpressureSendOptions withFallbackStrategy(BackpressureStrategy fallbackStrategy) {
        this.fallbackStrategy = fallbackStrategy;
        return this;
    }
    
    /**
     * Sets the timeout for sending a message.
     * This is an alias for withTimeout for backward compatibility.
     *
     * @param timeout The maximum time to wait
     * @return This instance for method chaining
     */
    public BackpressureSendOptions setTimeout(Duration timeout) {
        return withTimeout(timeout);
    }
    
    /**
     * Sets whether this message is high priority.
     * High priority messages may bypass some backpressure restrictions.
     *
     * @param highPriority Whether the message is high priority
     * @return This instance for method chaining
     */
    public BackpressureSendOptions withHighPriority(boolean highPriority) {
        this.highPriority = highPriority;
        return this;
    }
    
    /**
     * Sets whether this message is high priority.
     * This is an alias for withHighPriority for backward compatibility.
     * High priority messages may bypass some backpressure restrictions.
     *
     * @param highPriority Whether the message is high priority
     * @return This instance for method chaining
     */
    public BackpressureSendOptions setHighPriority(boolean highPriority) {
        return withHighPriority(highPriority);
    }
    
    /**
     * Gets the timeout for sending a message.
     *
     * @return The timeout
     */
    public Duration getTimeout() {
        return timeout;
    }
    
    /**
     * Gets the retry policy for sending a message.
     *
     * @return The retry policy
     */
    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }
    
    /**
     * Gets the number of retry attempts.
     *
     * @return The number of retry attempts
     */
    public int getRetryAttempts() {
        return retryAttempts;
    }
    
    /**
     * Gets the delay between retry attempts.
     *
     * @return The retry delay
     */
    public Duration getRetryDelay() {
        return retryDelay;
    }
    
    /**
     * Checks if the send operation should block until the message is accepted.
     *
     * @return true if the send operation should block, false otherwise
     */
    public boolean isBlockUntilAccepted() {
        return blockUntilAccepted;
    }
    
    /**
     * Gets the fallback strategy to use when the message cannot be sent.
     *
     * @return The fallback strategy
     */
    public BackpressureStrategy getFallbackStrategy() {
        return fallbackStrategy;
    }
    
    /**
     * Checks if this message is high priority.
     * High priority messages may bypass some backpressure restrictions.
     *
     * @return true if the message is high priority, false otherwise
     */
    public boolean isHighPriority() {
        return highPriority;
    }
    
    /**
     * Creates a default set of options for a non-blocking send.
     *
     * @return Options configured for non-blocking send
     */
    public static BackpressureSendOptions nonBlocking() {
        return new BackpressureSendOptions()
                .withBlockUntilAccepted(false)
                .withFallbackStrategy(BackpressureStrategy.DROP_NEW);
    }
    
    /**
     * Creates a default set of options for a high priority message.
     *
     * @return Options configured for high priority
     */
    public static BackpressureSendOptions highPriority() {
        return new BackpressureSendOptions(true);
    }
    
    /**
     * Creates a default set of options for a blocking send with timeout.
     *
     * @param timeout The timeout duration
     * @return Options configured for blocking send with timeout
     */
    public static BackpressureSendOptions blockingWithTimeout(Duration timeout) {
        return new BackpressureSendOptions()
                .withTimeout(timeout)
                .withBlockUntilAccepted(true);
    }
    
    /**
     * Creates a default set of options for retrying with exponential backoff.
     *
     * @param maxAttempts The maximum number of attempts
     * @return Options configured for retrying with exponential backoff
     */
    public static BackpressureSendOptions withExponentialBackoff(int maxAttempts) {
        return new BackpressureSendOptions()
                .withRetryPolicy(RetryPolicy.EXPONENTIAL_BACKOFF)
                .withRetryAttempts(maxAttempts)
                .withRetryDelay(Duration.ofMillis(100));
    }
    
    /**
     * Policies for retrying message sends under backpressure.
     */
    public enum RetryPolicy {
        /**
         * No retries - if the message cannot be sent, it is rejected.
         */
        NONE,
        
        /**
         * Fixed delay between retries.
         */
        FIXED_DELAY,
        
        /**
         * Exponential backoff between retries.
         */
        EXPONENTIAL_BACKOFF,
        
        /**
         * Random jitter between retries to avoid thundering herd problem.
         */
        JITTER
    }
}
