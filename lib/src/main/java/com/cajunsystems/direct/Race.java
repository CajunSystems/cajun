package com.cajunsystems.direct;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * Race utility class providing combinators for racing concurrent computations
 * and applying timeouts. Inspired by Ox for Scala.
 *
 * <p>Uses structured concurrency with virtual threads for safe concurrent execution.
 */
public final class Race {

    private Race() {
        // Utility class
    }

    /**
     * Launches all callables concurrently and returns the result of the first to complete
     * successfully, cancelling the rest.
     *
     * @param callables the callables to race
     * @param <T>       the result type
     * @return the result of the first callable to complete successfully
     * @throws ExecutionException   if all callables fail
     * @throws InterruptedException if the current thread is interrupted
     */
    @SafeVarargs
    public static <T> T race(Callable<T>... callables) throws ExecutionException, InterruptedException {
        if (callables == null || callables.length == 0) {
            throw new IllegalArgumentException("At least one callable must be provided");
        }

        try (var scope = new StructuredTaskScope.ShutdownOnSuccess<T>()) {
            for (Callable<T> callable : callables) {
                scope.fork(callable);
            }
            scope.join();
            return scope.result();
        }
    }

    /**
     * Launches all callables concurrently and returns the result of the first to complete
     * successfully, cancelling the rest. Throws if all callables fail.
     *
     * @param callables the callables to race
     * @param <T>       the result type
     * @return the result of the first callable to complete successfully
     * @throws ExecutionException   if all callables fail
     * @throws InterruptedException if the current thread is interrupted
     */
    @SafeVarargs
    public static <T> T raceOrThrow(Callable<T>... callables) throws ExecutionException, InterruptedException {
        if (callables == null || callables.length == 0) {
            throw new IllegalArgumentException("At least one callable must be provided");
        }

        try (var scope = new StructuredTaskScope.ShutdownOnSuccess<T>()) {
            for (Callable<T> callable : callables) {
                scope.fork(callable);
            }
            scope.join();
            try {
                return scope.result();
            } catch (ExecutionException e) {
                throw new ExecutionException("All callables failed", e.getCause());
            }
        }
    }

    /**
     * Runs a callable with a timeout. Throws {@link TimeoutException} if the callable
     * does not complete within the specified duration.
     *
     * @param callable the callable to execute
     * @param duration the maximum time to wait
     * @param <T>      the result type
     * @return the result of the callable
     * @throws TimeoutException     if the callable does not complete in time
     * @throws ExecutionException   if the callable fails
     * @throws InterruptedException if the current thread is interrupted
     */
    public static <T> T timeout(Callable<T> callable, Duration duration)
            throws TimeoutException, ExecutionException, InterruptedException {
        if (callable == null) {
            throw new IllegalArgumentException("Callable must not be null");
        }
        if (duration == null) {
            throw new IllegalArgumentException("Duration must not be null");
        }

        CompletableFuture<T> future = new CompletableFuture<>();

        Thread virtualThread = Thread.ofVirtual().start(() -> {
            try {
                T result = callable.call();
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            return future.get(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            virtualThread.interrupt();
            future.cancel(true);
            throw new TimeoutException("Callable did not complete within " + duration);
        } catch (ExecutionException e) {
            throw e;
        }
    }

    /**
     * Runs a callable with a timeout. Returns the default value if the callable
     * does not complete within the specified duration.
     *
     * @param callable     the callable to execute
     * @param duration     the maximum time to wait
     * @param defaultValue the value to return on timeout
     * @param <T>          the result type
     * @return the result of the callable, or the default value on timeout
     * @throws ExecutionException   if the callable fails with an exception (not timeout)
     * @throws InterruptedException if the current thread is interrupted
     */
    public static <T> T timeoutOrDefault(Callable<T> callable, Duration duration, T defaultValue)
            throws ExecutionException, InterruptedException {
        if (callable == null) {
            throw new IllegalArgumentException("Callable must not be null");
        }
        if (duration == null) {
            throw new IllegalArgumentException("Duration must not be null");
        }

        try {
            return timeout(callable, duration);
        } catch (TimeoutException e) {
            return defaultValue;
        }
    }
}