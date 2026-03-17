package com.cajunsystems.direct;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ForkTest {

    @Test
    void forkReturnsCorrectResultViaJoin() throws Exception {
        ForkHandle<Integer> handle = Fork.fork(() -> 42);
        Integer result = handle.join();
        assertEquals(42, result);
    }

    @Test
    void forkExecutesSupplierAsynchronously() throws Exception {
        AtomicBoolean executed = new AtomicBoolean(false);
        ForkHandle<String> handle = Fork.fork(() -> {
            executed.set(true);
            return "done";
        });
        String result = handle.join();
        assertTrue(executed.get());
        assertEquals("done", result);
    }

    @Test
    void forkScopedRunsMultipleTasksAndCollectsAllResults() throws Exception {
        List<Object> results = Fork.forkScoped(List.of(
                () -> "hello",
                () -> 123,
                () -> 3.14
        ));
        assertEquals(3, results.size());
        assertEquals("hello", results.get(0));
        assertEquals(123, results.get(1));
        assertEquals(3.14, results.get(2));
    }

    @Test
    void forkScopedWithEmptyListReturnsEmptyResults() throws Exception {
        List<Object> results = Fork.forkScoped(List.of());
        assertTrue(results.isEmpty());
    }

    @Test
    void forkScopedRunsTasksConcurrently() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        List<Object> results = Fork.forkScoped(List.of(
                () -> {
                    int count = concurrentCount.incrementAndGet();
                    maxConcurrent.updateAndGet(curr -> Math.max(curr, count));
                    latch.countDown();
                    latch.await(5, TimeUnit.SECONDS);
                    concurrentCount.decrementAndGet();
                    return "a";
                },
                () -> {
                    int count = concurrentCount.incrementAndGet();
                    maxConcurrent.updateAndGet(curr -> Math.max(curr, count));
                    latch.countDown();
                    latch.await(5, TimeUnit.SECONDS);
                    concurrentCount.decrementAndGet();
                    return "b";
                }
        ));

        assertEquals(2, results.size());
        assertEquals("a", results.get(0));
        assertEquals("b", results.get(1));
        assertTrue(maxConcurrent.get() >= 2, "Tasks should run concurrently");
    }

    @Test
    void forkDaemonRunsWithoutBlocking() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(false);

        ForkHandle<Void> handle = Fork.forkDaemon(() -> {
            running.set(true);
            started.countDown();
            // Simulate long-running daemon task
            Thread.sleep(5000);
            return null;
        });

        // forkDaemon should return immediately without blocking
        assertTrue(started.await(2, TimeUnit.SECONDS), "Daemon task should have started");
        assertTrue(running.get(), "Daemon task should be running");

        // Clean up
        handle.cancel();
    }

    @Test
    void forkDaemonDoesNotPreventCompletion() throws Exception {
        AtomicBoolean daemonStarted = new AtomicBoolean(false);
        AtomicBoolean daemonInterrupted = new AtomicBoolean(false);

        ForkHandle<Void> daemonHandle = Fork.forkDaemon(() -> {
            daemonStarted.set(true);
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                daemonInterrupted.set(true);
                throw e;
            }
            return null;
        });

        // Give daemon time to start
        Thread.sleep(100);
        assertTrue(daemonStarted.get(), "Daemon task should have started");

        // We should be able to cancel it
        daemonHandle.cancel();
        assertTrue(daemonHandle.isCancelled(), "Daemon handle should be marked as cancelled");

        // Give a moment for interruption to propagate
        Thread.sleep(100);
        assertTrue(daemonInterrupted.get(), "Daemon task should have been interrupted on cancel");
    }

    @Test
    void cancellationWorksOnForkHandle() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        ForkHandle<String> handle = Fork.fork(() -> {
            started.countDown();
            try {
                Thread.sleep(10000);
                return "completed";
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw e;
            }
        });

        assertTrue(started.await(2, TimeUnit.SECONDS));
        handle.cancel();

        assertTrue(handle.isCancelled(), "Handle should be marked as cancelled");
        // Give a moment for interruption to propagate
        Thread.sleep(100);
        assertTrue(interrupted.get(), "Forked task should have been interrupted");
    }

    @Test
    void cancelOnAlreadyCompletedTaskIsSafe() throws Exception {
        ForkHandle<Integer> handle = Fork.fork(() -> 99);
        Integer result = handle.join();
        assertEquals(99, result);

        // Cancelling after completion should be safe (no-op)
        handle.cancel();
        assertFalse(handle.isCancelled(), "Already completed task should not be marked as cancelled");
    }

    @Test
    void forkPropagatesExceptionOnJoin() {
        ForkHandle<String> handle = Fork.fork(() -> {
            throw new RuntimeException("task failed");
        });

        Exception exception = assertThrows(Exception.class, handle::join);
        assertTrue(exception.getMessage().contains("task failed") ||
                        (exception.getCause() != null && exception.getCause().getMessage().contains("task failed")),
                "Exception message should contain the original error");
    }

    @Test
    void forkHandleCancelInterruptsTheTask() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean wasInterrupted = new AtomicBoolean(false);
        CountDownLatch finished = new CountDownLatch(1);

        ForkHandle<Void> handle = Fork.fork(() -> {
            started.countDown();
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
                finished.countDown();
                throw e;
            }
            return null;
        });

        assertTrue(started.await(2, TimeUnit.SECONDS), "Task should have started");
        handle.cancel();
        assertTrue(finished.await(2, TimeUnit.SECONDS), "Task should have been interrupted and finished");
        assertTrue(wasInterrupted.get(), "Task should have received an InterruptedException");
        assertTrue(handle.isCancelled(), "Handle should report cancelled");
    }

    @Test
    void forkScopedPropagatesFirstExceptionWhenTaskFails() {
        Exception exception = assertThrows(Exception.class, () -> {
            Fork.forkScoped(List.of(
                    () -> {
                        Thread.sleep(50);
                        return "ok";
                    },
                    () -> {
                        throw new RuntimeException("first failure");
                    },
                    () -> {
                        Thread.sleep(100);
                        return "also ok";
                    }
            ));
        });

        // The exception should originate from the failing task
        String message = exception.getMessage();
        String causeMessage = exception.getCause() != null ? exception.getCause().getMessage() : "";
        assertTrue(message.contains("first failure") || causeMessage.contains("first failure"),
                "Should propagate the exception from the failing task, got: " + message);
    }

    @Test
    void errorPropagationWhenForkedTaskThrowsException() {
        ForkHandle<String> handle = Fork.fork(() -> {
            throw new RuntimeException("task failed");
        });

        Exception exception = assertThrows(Exception.class, handle::join);
        assertTrue(exception.getMessage().contains("task failed") ||
                        (exception.getCause() != null && exception.getCause().getMessage().contains("task failed")),
                "Exception message should contain the original error");
    }

    @Test
    void errorPropagationInForkScoped() {
        assertThrows(Exception.class, () -> {
            Fork.forkScoped(List.of(
                    () -> "ok",
                    () -> {
                        throw new RuntimeException("scoped task failed");
                    },
                    () -> "also ok"
            ));
        });
    }

    @Test
    void forkHandleJoinMultipleTimesReturnsSameResult() throws Exception {
        ForkHandle<Integer> handle = Fork.fork(() -> 7);
        assertEquals(7, handle.join());
        assertEquals(7, handle.join());
        assertEquals(7, handle.join());
    }

    @Test
    void forkWithNullResultReturnsNull() throws Exception {
        ForkHandle<String> handle = Fork.fork(() -> null);
        assertNull(handle.join(), "Fork with null-returning supplier should return null on join");
    }

    @Test
    void forkWithSlowTaskEventuallyCompletes() throws Exception {
        ForkHandle<String> handle = Fork.fork(() -> {
            Thread.sleep(200);
            return "slow result";
        });
        assertEquals("slow result", handle.join());
    }

    @Test
    void forkWithSlowTaskVerifiesAsyncBehavior() throws Exception {
        long startTime = System.nanoTime();

        ForkHandle<String> handle = Fork.fork(() -> {
            Thread.sleep(500);
            return "async result";
        });

        long forkReturnTime = System.nanoTime();
        long forkDurationMs = TimeUnit.NANOSECONDS.toMillis(forkReturnTime - startTime);

        // fork() should return almost immediately, not wait for the task to complete
        assertTrue(forkDurationMs < 200,
                "fork() should return quickly (was " + forkDurationMs + "ms), task runs asynchronously");

        // join() should block until the result is available
        String result = handle.join();
        long joinReturnTime = System.nanoTime();
        long totalDurationMs = TimeUnit.NANOSECONDS.toMillis(joinReturnTime - startTime);

        assertEquals("async result", result);
        assertTrue(totalDurationMs >= 400,
                "Total time including join should reflect the task's sleep (was " + totalDurationMs + "ms)");
    }

    @Test
    void forkScopedPreservesOrderOfResults() throws Exception {
        List<Object> results = Fork.forkScoped(List.of(
                () -> {
                    Thread.sleep(200);
                    return "first";
                },
                () -> {
                    Thread.sleep(50);
                    return "second";
                },
                () -> {
                    Thread.sleep(100);
                    return "third";
                }
        ));

        assertEquals(3, results.size());
        assertEquals("first", results.get(0));
        assertEquals("second", results.get(1));
        assertEquals("third", results.get(2));
    }
}