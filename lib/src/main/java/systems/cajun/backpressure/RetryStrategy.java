package systems.cajun.backpressure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Implements retry mechanisms with exponential backoff for transient failures.
 * This class provides a way to retry operations that might fail due to temporary issues.
 */
public class RetryStrategy implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(RetryStrategy.class);
    
    private final int maxRetries;
    private final long initialDelayMs;
    private final long maxDelayMs;
    private final double backoffMultiplier;
    private final Predicate<Throwable> retryableExceptionPredicate;
    
    /**
     * Creates a new RetryStrategy with default settings.
     */
    public RetryStrategy() {
        this(3, 100, 5000, 2.0, ex -> true);
    }
    
    /**
     * Creates a new RetryStrategy with custom settings.
     *
     * @param maxRetries Maximum number of retry attempts
     * @param initialDelayMs Initial delay between retries in milliseconds
     * @param maxDelayMs Maximum delay between retries in milliseconds
     * @param backoffMultiplier Multiplier for exponential backoff
     * @param retryableExceptionPredicate Predicate to determine if an exception is retryable
     */
    public RetryStrategy(
            int maxRetries,
            long initialDelayMs,
            long maxDelayMs,
            double backoffMultiplier,
            Predicate<Throwable> retryableExceptionPredicate) {
        this.maxRetries = maxRetries;
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.backoffMultiplier = backoffMultiplier;
        this.retryableExceptionPredicate = retryableExceptionPredicate;
    }
    
    /**
     * Executes an operation with retry logic.
     *
     * @param operation The operation to execute
     * @param executor The executor to use for scheduling retries
     * @param <T> The return type of the operation
     * @return A CompletableFuture that completes with the result of the operation or exceptionally if all retries fail
     */
    public <T> CompletableFuture<T> executeWithRetry(
            Supplier<CompletableFuture<T>> operation,
            Executor executor) {
        CompletableFuture<T> result = new CompletableFuture<>();
        executeWithRetry(operation, 0, result, executor);
        return result;
    }
    
    private <T> void executeWithRetry(
            Supplier<CompletableFuture<T>> operation,
            int attempt,
            CompletableFuture<T> result,
            Executor executor) {
        operation.get()
            .thenAccept(result::complete)
            .exceptionally(ex -> {
                if (attempt < maxRetries && retryableExceptionPredicate.test(ex)) {
                    long delay = calculateDelay(attempt);
                    logger.debug("Operation failed with exception: {}. Retrying in {}ms (attempt {}/{})",
                            ex.getMessage(), delay, attempt + 1, maxRetries);
                    
                    // Schedule retry after delay
                    CompletableFuture.runAsync(() -> {
                        try {
                            Thread.sleep(delay);
                            executeWithRetry(operation, attempt + 1, result, executor);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            result.completeExceptionally(e);
                        }
                    }, executor);
                } else {
                    logger.warn("Operation failed after {} attempts with exception: {}",
                            attempt + 1, ex.getMessage());
                    result.completeExceptionally(ex);
                }
                return null;
            });
    }
    
    /**
     * Calculates the delay for a given retry attempt using exponential backoff.
     *
     * @param attempt The current retry attempt (0-based)
     * @return The delay in milliseconds
     */
    private long calculateDelay(int attempt) {
        double delay = initialDelayMs * Math.pow(backoffMultiplier, attempt);
        return Math.min(maxDelayMs, (long) delay);
    }
    
    /**
     * Creates a builder for RetryStrategy.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for RetryStrategy.
     */
    public static class Builder {
        private int maxRetries = 3;
        private long initialDelayMs = 100;
        private long maxDelayMs = 5000;
        private double backoffMultiplier = 2.0;
        private Predicate<Throwable> retryableExceptionPredicate = ex -> true;
        
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }
        
        public Builder initialDelayMs(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
            return this;
        }
        
        public Builder maxDelayMs(long maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
            return this;
        }
        
        public Builder backoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
            return this;
        }
        
        public Builder retryableExceptionPredicate(Predicate<Throwable> predicate) {
            this.retryableExceptionPredicate = predicate;
            return this;
        }
        
        public Builder retryableExceptions(Class<?>... exceptionClasses) {
            this.retryableExceptionPredicate = ex -> {
                for (Class<?> exClass : exceptionClasses) {
                    if (exClass.isInstance(ex)) {
                        return true;
                    }
                }
                return false;
            };
            return this;
        }
        
        public RetryStrategy build() {
            return new RetryStrategy(
                    maxRetries,
                    initialDelayMs,
                    maxDelayMs,
                    backoffMultiplier,
                    retryableExceptionPredicate);
        }
    }
}
