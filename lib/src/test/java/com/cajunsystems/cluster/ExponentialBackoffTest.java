package com.cajunsystems.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ExponentialBackoffTest {

    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void testDelayForAttemptZeroWithNoJitter() {
        ExponentialBackoff backoff = new ExponentialBackoff(100, 30_000, 5, 0.0);
        assertEquals(100, backoff.delayForAttempt(0));
    }

    @Test
    void testDelayGrowsExponentiallyWithNoJitter() {
        ExponentialBackoff backoff = new ExponentialBackoff(100, 30_000, 5, 0.0);
        assertTrue(backoff.delayForAttempt(1) > backoff.delayForAttempt(0));
        assertTrue(backoff.delayForAttempt(2) > backoff.delayForAttempt(1));
    }

    @Test
    void testDelayCapAtMaxDelay() {
        ExponentialBackoff backoff = new ExponentialBackoff(100, 500, 5, 0.0);
        // attempt 10 would be 100 * 1024 = 102400 > 500
        assertTrue(backoff.delayForAttempt(10) <= 500);
    }

    @Test
    void testNoJitterWhenFactorIsZero() {
        ExponentialBackoff backoff = new ExponentialBackoff(100, 30_000, 5, 0.0);
        // With jitterFactor=0, delay should be exactly 100 * 2^0 = 100
        assertEquals(100, backoff.delayForAttempt(0));
        assertEquals(200, backoff.delayForAttempt(1));
        assertEquals(400, backoff.delayForAttempt(2));
    }

    @Test
    void testWithRetrySucceedsOnFirstAttempt() throws Exception {
        ExponentialBackoff backoff = new ExponentialBackoff(10, 1000, 3, 0.0);
        AtomicInteger callCount = new AtomicInteger(0);
        String result = backoff.withRetry(() -> {
            callCount.incrementAndGet();
            return CompletableFuture.completedFuture("ok");
        }, scheduler).get();
        assertEquals("ok", result);
        assertEquals(1, callCount.get());
    }

    @Test
    void testWithRetryRetriesOnFailure() throws Exception {
        ExponentialBackoff backoff = new ExponentialBackoff(10, 1000, 3, 0.0);
        AtomicInteger callCount = new AtomicInteger(0);
        String result = backoff.withRetry(() -> {
            int n = callCount.incrementAndGet();
            if (n < 3) return CompletableFuture.failedFuture(new RuntimeException("fail " + n));
            return CompletableFuture.completedFuture("success");
        }, scheduler).get();
        assertEquals("success", result);
        assertEquals(3, callCount.get());
    }

    @Test
    void testWithRetryExhaustsMaxAttempts() {
        ExponentialBackoff backoff = new ExponentialBackoff(10, 1000, 3, 0.0);
        AtomicInteger callCount = new AtomicInteger(0);
        CompletableFuture<String> future = backoff.withRetry(() -> {
            callCount.incrementAndGet();
            return CompletableFuture.failedFuture(new RuntimeException("always fails"));
        }, scheduler);
        assertThrows(Exception.class, () -> future.get());
        // Should have attempted maxAttempts (3) times
        assertEquals(3, callCount.get());
    }
}
