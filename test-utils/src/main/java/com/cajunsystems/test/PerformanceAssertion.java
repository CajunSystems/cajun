package com.cajunsystems.test;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Utilities for performance assertions in actor tests.
 * Provides methods to assert timing and throughput requirements.
 * 
 * <p>Usage:
 * <pre>{@code
 * // Assert operation completes within time limit
 * String result = PerformanceAssertion.assertWithinTime(
 *     () -> expensiveOperation(),
 *     Duration.ofMillis(100)
 * );
 * 
 * // Assert throughput meets minimum requirement
 * PerformanceAssertion.assertThroughput(
 *     () -> actor.tell(new Message()),
 *     1000,  // iterations
 *     10000  // min ops/sec
 * );
 * }</pre>
 */
public class PerformanceAssertion {
    
    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private PerformanceAssertion() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Executes an operation and asserts it completes within the specified time.
     * 
     * @param <T> the return type
     * @param operation the operation to execute
     * @param maxTime the maximum allowed time
     * @return the result of the operation
     * @throws AssertionError if operation takes longer than maxTime
     */
    public static <T> T assertWithinTime(Supplier<T> operation, Duration maxTime) {
        Objects.requireNonNull(operation, "operation cannot be null");
        Objects.requireNonNull(maxTime, "maxTime cannot be null");
        
        long start = System.nanoTime();
        T result = operation.get();
        long elapsed = System.nanoTime() - start;
        
        Duration actualDuration = Duration.ofNanos(elapsed);
        if (actualDuration.compareTo(maxTime) > 0) {
            throw new AssertionError(String.format(
                "Operation took %s, expected under %s",
                actualDuration,
                maxTime
            ));
        }
        
        return result;
    }
    
    /**
     * Executes a void operation and asserts it completes within the specified time.
     * 
     * @param operation the operation to execute
     * @param maxTime the maximum allowed time
     * @throws AssertionError if operation takes longer than maxTime
     */
    public static void assertWithinTime(Runnable operation, Duration maxTime) {
        Objects.requireNonNull(operation, "operation cannot be null");
        Objects.requireNonNull(maxTime, "maxTime cannot be null");
        
        long start = System.nanoTime();
        operation.run();
        long elapsed = System.nanoTime() - start;
        
        Duration actualDuration = Duration.ofNanos(elapsed);
        if (actualDuration.compareTo(maxTime) > 0) {
            throw new AssertionError(String.format(
                "Operation took %s, expected under %s",
                actualDuration,
                maxTime
            ));
        }
    }
    
    /**
     * Executes an operation multiple times and asserts throughput meets minimum requirement.
     * 
     * @param operation the operation to execute
     * @param iterations the number of times to execute the operation
     * @param minOpsPerSecond the minimum required operations per second
     * @throws AssertionError if throughput is below minimum
     */
    public static void assertThroughput(Runnable operation, int iterations, double minOpsPerSecond) {
        Objects.requireNonNull(operation, "operation cannot be null");
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be positive");
        }
        if (minOpsPerSecond <= 0) {
            throw new IllegalArgumentException("minOpsPerSecond must be positive");
        }
        
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            operation.run();
        }
        long elapsed = System.nanoTime() - start;
        
        double actualOpsPerSecond = iterations / (elapsed / 1_000_000_000.0);
        
        if (actualOpsPerSecond < minOpsPerSecond) {
            throw new AssertionError(String.format(
                "Throughput was %.2f ops/sec, expected at least %.2f ops/sec",
                actualOpsPerSecond,
                minOpsPerSecond
            ));
        }
    }
    
    /**
     * Measures the execution time of an operation.
     * 
     * @param operation the operation to measure
     * @return the execution duration
     */
    public static Duration measureTime(Runnable operation) {
        Objects.requireNonNull(operation, "operation cannot be null");
        
        long start = System.nanoTime();
        operation.run();
        long elapsed = System.nanoTime() - start;
        
        return Duration.ofNanos(elapsed);
    }
    
    /**
     * Measures the throughput of an operation.
     * 
     * @param operation the operation to measure
     * @param iterations the number of times to execute the operation
     * @return the throughput in operations per second
     */
    public static double measureThroughput(Runnable operation, int iterations) {
        Objects.requireNonNull(operation, "operation cannot be null");
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be positive");
        }
        
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            operation.run();
        }
        long elapsed = System.nanoTime() - start;
        
        return iterations / (elapsed / 1_000_000_000.0);
    }
    
    /**
     * Result of a performance measurement.
     *
     * @param duration the total duration of the operation
     * @param iterations the number of iterations executed
     * @param opsPerSecond the throughput in operations per second
     */
    public record PerformanceResult(
        Duration duration,
        int iterations,
        double opsPerSecond
    ) {
        @Override
        public String toString() {
            return String.format(
                "PerformanceResult{duration=%s, iterations=%d, throughput=%.2f ops/sec}",
                duration, iterations, opsPerSecond
            );
        }
    }
    
    /**
     * Measures both time and throughput for an operation.
     * 
     * @param operation the operation to measure
     * @param iterations the number of times to execute the operation
     * @return the performance result
     */
    public static PerformanceResult measure(Runnable operation, int iterations) {
        Objects.requireNonNull(operation, "operation cannot be null");
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be positive");
        }
        
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            operation.run();
        }
        long elapsed = System.nanoTime() - start;
        
        Duration duration = Duration.ofNanos(elapsed);
        double opsPerSecond = iterations / (elapsed / 1_000_000_000.0);
        
        return new PerformanceResult(duration, iterations, opsPerSecond);
    }
}
