package com.cajunsystems.cluster;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class ExponentialBackoff {

    private final long initialDelayMs;
    private final long maxDelayMs;
    private final int maxAttempts;
    private final double jitterFactor;
    private final Random random = new Random();

    public ExponentialBackoff(long initialDelayMs, long maxDelayMs, int maxAttempts, double jitterFactor) {
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.maxAttempts = maxAttempts;
        this.jitterFactor = jitterFactor;
    }

    public long delayForAttempt(int attempt) {
        long base = Math.min(initialDelayMs * (1L << attempt), maxDelayMs);
        long jitter = (long) (base * jitterFactor * (random.nextDouble() * 2 - 1));
        return Math.max(0, base + jitter);
    }

    public <T> CompletableFuture<T> withRetry(
            Supplier<CompletableFuture<T>> operation,
            ScheduledExecutorService scheduler) {
        return attempt(operation, scheduler, 0);
    }

    private <T> CompletableFuture<T> attempt(
            Supplier<CompletableFuture<T>> op,
            ScheduledExecutorService scheduler,
            int attemptNumber) {
        return op.get().exceptionallyCompose(ex -> {
            if (attemptNumber >= maxAttempts - 1) {
                return CompletableFuture.failedFuture(ex);
            }
            long delay = delayForAttempt(attemptNumber);
            CompletableFuture<T> future = new CompletableFuture<>();
            scheduler.schedule(() ->
                attempt(op, scheduler, attemptNumber + 1)
                    .whenComplete((r, e) -> {
                        if (e != null) future.completeExceptionally(e);
                        else future.complete(r);
                    }),
                delay, TimeUnit.MILLISECONDS);
            return future;
        });
    }
}
