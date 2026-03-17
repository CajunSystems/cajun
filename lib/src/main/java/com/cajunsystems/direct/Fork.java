package com.cajunsystems.direct;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.StructuredTaskScope;

/**
 * Utility class for forking computations on virtual threads.
 * Inspired by Ox for Scala, provides structured concurrency primitives
 * for launching and managing concurrent computations.
 */
public final class Fork {

    private Fork() {
        // Utility class, no instantiation
    }

    /**
     * A handle to a forked computation, wrapping a {@link Future}.
     * Provides blocking {@link #join()} and {@link #cancel()} methods.
     *
     * @param <T> the result type of the computation
     */
    public static final class ForkHandle<T> {
        private final Future<T> future;
        private final Thread thread;

        ForkHandle(Future<T> future, Thread thread) {
            this.future = future;
            this.thread = thread;
        }

        /**
         * Blocks until the computation completes and returns the result.
         *
         * @return the result of the computation
         * @throws InterruptedException if the current thread is interrupted while waiting
         * @throws ExecutionException   if the computation threw an exception
         */
        public T join() throws InterruptedException, ExecutionException {
            return future.get();
        }

        /**
         * Attempts to cancel the computation.
         *
         * @param mayInterruptIfRunning {@code true} if the thread executing the task
         *                              should be interrupted; otherwise, in-progress tasks
         *                              are allowed to complete
         * @return {@code true} if the task was successfully cancelled
         */
        public boolean cancel(boolean mayInterruptIfRunning) {
            return future.cancel(mayInterruptIfRunning);
        }

        /**
         * Attempts to cancel the computation, interrupting the thread if running.
         *
         * @return {@code true} if the task was successfully cancelled
         */
        public boolean cancel() {
            return cancel(true);
        }

        /**
         * Returns {@code true} if the computation has completed.
         *
         * @return {@code true} if the computation is done
         */
        public boolean isDone() {
            return future.isDone();
        }

        /**
         * Returns {@code true} if the computation was cancelled.
         *
         * @return {@code true} if cancelled
         */
        public boolean isCancelled() {
            return future.isCancelled();
        }
    }

    /**
     * Launches a computation on a virtual thread and returns a {@link ForkHandle}
     * that can be used to retrieve the result.
     *
     * @param callable the computation to execute
     * @param <T>      the result type
     * @return a handle to the forked computation
     */
    public static <T> ForkHandle<T> fork(Callable<T> callable) {
        var future = new java.util.concurrent.CompletableFuture<T>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                T result = callable.call();
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return new ForkHandle<>(future, thread);
    }

    /**
     * Runs multiple callables concurrently in a {@link StructuredTaskScope},
     * waiting for all to complete, and returns all results.
     * <p>
     * If any subtask fails, the scope is shut down and the exception is propagated.
     *
     * @param callables the computations to execute concurrently
     * @param <T>       the result type (all callables must return the same type)
     * @return a list of results in the same order as the input callables
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws ExecutionException   if any computation threw an exception
     */
    @SafeVarargs
    public static <T> List<T> forkScoped(Callable<T>... callables) throws InterruptedException, ExecutionException {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            List<StructuredTaskScope.Subtask<T>> subtasks = new ArrayList<>(callables.length);
            for (Callable<T> callable : callables) {
                subtasks.add(scope.fork(callable));
            }
            scope.join();
            scope.throwIfFailed();

            List<T> results = new ArrayList<>(callables.length);
            for (StructuredTaskScope.Subtask<T> subtask : subtasks) {
                results.add(subtask.get());
            }
            return results;
        }
    }

    /**
     * Launches a fire-and-forget background task on a daemon virtual thread.
     * The task runs independently and its result (or failure) is not tracked.
     *
     * @param runnable the background task to execute
     * @return the daemon thread that was started
     */
    public static Thread forkDaemon(Runnable runnable) {
        Thread thread = Thread.ofVirtual().name("fork-daemon-", 0).start(runnable);
        return thread;
    }
}