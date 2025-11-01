package com.cajunsystems.test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Utilities for async assertions in actor tests.
 * Provides better alternatives to Thread.sleep() for waiting on async conditions.
 * 
 * <p>Usage:
 * <pre>{@code
 * // Wait for a condition to become true
 * AsyncAssertion.eventually(() -> actor.stateInspector().current() == 5, Duration.ofSeconds(2));
 * 
 * // Wait for a value to match
 * int result = AsyncAssertion.awaitValue(() -> actor.stateInspector().current(), 5, Duration.ofSeconds(2));
 * }</pre>
 */
public class AsyncAssertion {
    
    private static final long DEFAULT_POLL_INTERVAL_MS = 50;
    
    /**
     * Waits until the condition becomes true or timeout is reached.
     * 
     * @param condition the condition to check
     * @param timeout the maximum time to wait
     * @throws AssertionError if condition doesn't become true within timeout
     */
    public static void eventually(BooleanSupplier condition, Duration timeout) {
        Objects.requireNonNull(condition, "condition cannot be null");
        Objects.requireNonNull(timeout, "timeout cannot be null");
        eventually(condition, timeout, DEFAULT_POLL_INTERVAL_MS);
    }
    
    /**
     * Waits until the condition becomes true or timeout is reached.
     * 
     * @param condition the condition to check
     * @param timeout the maximum time to wait
     * @param pollIntervalMs the interval between checks in milliseconds
     * @throws AssertionError if condition doesn't become true within timeout
     */
    public static void eventually(BooleanSupplier condition, Duration timeout, long pollIntervalMs) {
        Objects.requireNonNull(condition, "condition cannot be null");
        Objects.requireNonNull(timeout, "timeout cannot be null");
        long endTime = System.currentTimeMillis() + timeout.toMillis();
        Throwable lastError = null;
        
        while (System.currentTimeMillis() < endTime) {
            try {
                if (condition.getAsBoolean()) {
                    return; // Success!
                }
            } catch (Throwable e) {
                lastError = e;
            }
            
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for condition", e);
            }
        }
        
        // Timeout - condition never became true
        String message = String.format(
            "Condition did not become true within %s",
            timeout
        );
        
        if (lastError != null) {
            throw new AssertionError(message + ". Last error: " + lastError.getMessage(), lastError);
        } else {
            throw new AssertionError(message);
        }
    }
    
    /**
     * Waits until the supplier returns the expected value or timeout is reached.
     * 
     * @param <T> the value type
     * @param supplier the value supplier
     * @param expected the expected value
     * @param timeout the maximum time to wait
     * @return the actual value (which equals expected)
     * @throws AssertionError if value doesn't match within timeout
     */
    public static <T> T awaitValue(Supplier<T> supplier, T expected, Duration timeout) {
        Objects.requireNonNull(supplier, "supplier cannot be null");
        Objects.requireNonNull(timeout, "timeout cannot be null");
        return awaitValue(supplier, expected, timeout, DEFAULT_POLL_INTERVAL_MS);
    }
    
    /**
     * Waits until the supplier returns the expected value or timeout is reached.
     * 
     * @param <T> the value type
     * @param supplier the value supplier
     * @param expected the expected value
     * @param timeout the maximum time to wait
     * @param pollIntervalMs the interval between checks in milliseconds
     * @return the actual value (which equals expected)
     * @throws AssertionError if value doesn't match within timeout
     */
    public static <T> T awaitValue(Supplier<T> supplier, T expected, Duration timeout, long pollIntervalMs) {
        Objects.requireNonNull(supplier, "supplier cannot be null");
        Objects.requireNonNull(timeout, "timeout cannot be null");
        
        long startTime = System.currentTimeMillis();
        long endTime = startTime + timeout.toMillis();
        T lastValue = null;
        List<T> valueHistory = new ArrayList<>();
        
        while (System.currentTimeMillis() < endTime) {
            try {
                lastValue = supplier.get();
                // Track value changes only
                if (valueHistory.isEmpty() || !Objects.equals(lastValue, valueHistory.get(valueHistory.size() - 1))) {
                    valueHistory.add(lastValue);
                }
                if (expected == null ? lastValue == null : expected.equals(lastValue)) {
                    return lastValue;
                }
            } catch (Exception e) {
                // Continue trying
            }
            
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for value", e);
            }
        }
        
        // Enhanced error message with value history
        throw new AssertionError(String.format(
            "Value did not become %s within %s. Value history: %s. Final value: %s",
            expected, timeout, valueHistory, lastValue
        ));
    }
    
    /**
     * Waits until the runnable completes without throwing an exception.
     * Useful for assertions that might fail initially but eventually succeed.
     * 
     * @param assertion the assertion to run
     * @param timeout the maximum time to wait
     * @throws AssertionError if assertion keeps failing within timeout
     */
    public static void eventuallyAssert(Runnable assertion, Duration timeout) {
        Objects.requireNonNull(assertion, "assertion cannot be null");
        Objects.requireNonNull(timeout, "timeout cannot be null");
        eventuallyAssert(assertion, timeout, DEFAULT_POLL_INTERVAL_MS);
    }
    
    /**
     * Waits until the runnable completes without throwing an exception.
     * 
     * @param assertion the assertion to run
     * @param timeout the maximum time to wait
     * @param pollIntervalMs the interval between checks in milliseconds
     * @throws AssertionError if assertion keeps failing within timeout
     */
    public static void eventuallyAssert(Runnable assertion, Duration timeout, long pollIntervalMs) {
        Objects.requireNonNull(assertion, "assertion cannot be null");
        Objects.requireNonNull(timeout, "timeout cannot be null");
        long endTime = System.currentTimeMillis() + timeout.toMillis();
        Throwable lastError = null;
        
        while (System.currentTimeMillis() < endTime) {
            try {
                assertion.run();
                return; // Success!
            } catch (Throwable e) {
                lastError = e;
            }
            
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for assertion", e);
            }
        }
        
        // Timeout - assertion kept failing
        if (lastError != null) {
            throw new AssertionError(
                "Assertion did not succeed within " + timeout + ". Last error: " + lastError.getMessage(),
                lastError
            );
        } else {
            throw new AssertionError("Assertion did not succeed within " + timeout);
        }
    }
}
