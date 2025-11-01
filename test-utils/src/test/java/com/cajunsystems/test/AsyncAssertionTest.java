package com.cajunsystems.test;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AsyncAssertion functionality.
 */
class AsyncAssertionTest {
    
    @Test
    void shouldWaitForConditionToBecomeTrue() {
        AtomicBoolean flag = new AtomicBoolean(false);
        
        // Set flag to true after delay
        new Thread(() -> {
            try {
                Thread.sleep(100);
                flag.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
        
        // Should wait and succeed
        AsyncAssertion.eventually(() -> flag.get(), Duration.ofSeconds(1));
        
        assertTrue(flag.get());
    }
    
    @Test
    void shouldThrowIfConditionNeverBecomesTrue() {
        assertThrows(AssertionError.class, () -> {
            AsyncAssertion.eventually(() -> false, Duration.ofMillis(200));
        });
    }
    
    @Test
    void shouldAwaitValue() {
        AtomicInteger counter = new AtomicInteger(0);
        
        // Increment counter after delay
        new Thread(() -> {
            try {
                Thread.sleep(100);
                counter.set(42);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
        
        // Should wait for value
        int result = AsyncAssertion.awaitValue(counter::get, 42, Duration.ofSeconds(1));
        
        assertEquals(42, result);
    }
    
    @Test
    void shouldThrowIfValueNeverMatches() {
        AtomicInteger counter = new AtomicInteger(0);
        
        assertThrows(AssertionError.class, () -> {
            AsyncAssertion.awaitValue(counter::get, 99, Duration.ofMillis(200));
        });
    }
    
    @Test
    void shouldEventuallyAssert() {
        AtomicInteger counter = new AtomicInteger(0);
        
        // Increment counter gradually
        new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                try {
                    Thread.sleep(20);
                    counter.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }).start();
        
        // Should eventually succeed
        AsyncAssertion.eventuallyAssert(
            () -> assertTrue(counter.get() >= 5),
            Duration.ofSeconds(1)
        );
    }
    
    @Test
    void shouldThrowIfAssertionKeepsFailing() {
        assertThrows(AssertionError.class, () -> {
            AsyncAssertion.eventuallyAssert(
                () -> fail("Always fails"),
                Duration.ofMillis(200)
            );
        });
    }
    
    @Test
    void shouldSupportCustomPollInterval() {
        AtomicInteger pollCount = new AtomicInteger(0);
        
        AsyncAssertion.eventually(
            () -> {
                pollCount.incrementAndGet();
                return pollCount.get() >= 3;
            },
            Duration.ofSeconds(1),
            100 // 100ms poll interval
        );
        
        // Should have polled at least 3 times
        assertTrue(pollCount.get() >= 3);
    }
    
    @Test
    void shouldHandleNullValues() {
        AtomicInteger value = new AtomicInteger(0);
        
        // Set to null-equivalent after delay
        new Thread(() -> {
            try {
                Thread.sleep(100);
                // We can't actually set AtomicInteger to null, so test with supplier
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
        
        // Test with supplier that returns null
        String result = AsyncAssertion.awaitValue(() -> null, null, Duration.ofMillis(200));
        assertNull(result);
    }
    
    @Test
    void shouldProvideHelpfulErrorMessages() {
        AtomicInteger counter = new AtomicInteger(10);
        
        AssertionError error = assertThrows(AssertionError.class, () -> {
            AsyncAssertion.awaitValue(counter::get, 99, Duration.ofMillis(200));
        });
        
        // Error message should include expected and actual values
        assertTrue(error.getMessage().contains("99"));
        assertTrue(error.getMessage().contains("10"));
    }
}
