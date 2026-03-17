package com.cajunsystems.direct;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Public convenience class that aggregates the direct-style API surface for Cajun actors.
 *
 * <p>This class provides static factory and utility methods inspired by Ox for Scala,
 * enabling a concise, direct-style programming model for concurrent operations.
 *
 * <p>Example usage:
 * <pre>{@code
 * import static com.cajunsystems.direct.Direct.*;
 *
 * String result = supervised(scope -> {
 *     var future = scope.fork(() -> "hello");
 *     return future.get();
 * });
 * }</pre>
 *
 * <p>This class is not instantiable.
 */
public final class Direct {

    private Direct() {
        throw new AssertionError("Direct is a utility class and cannot be instantiated");
    }

    // ---- Supervised scopes ----

    /**
     * Creates a supervised scope, executes the given block within it, and returns the result.
     * The scope ensures that all forked computations complete (or are cancelled) before
     * the method returns.
     *
     * @param <T>  the result type
     * @param body the computation to run within the scope
     * @return the result of the computation
     * @throws Exception if the computation fails
     */
    public static <T> T supervised(ThrowingFunction<Scope, T> body) throws Exception {
        return Scope.supervised(body);
    }

    /**
     * Creates a supervised scope and executes the given block within it.
     * The scope ensures that all forked computations complete (or are cancelled) before
     * the method returns.
     *
     * @param body the computation to run within the scope
     * @throws Exception if the computation fails
     */
    public static void supervised(ThrowingConsumer<Scope> body) throws Exception {
        Scope.supervised(body);
    }

    // ---- Channel factories ----

    /**
     * Creates a new unbounded {@link DirectChannel}.
     *
     * @param <T> the element type
     * @return a new unbounded channel
     */
    public static <T> DirectChannel<T> channel() {
        return new DirectChannel<>();
    }

    /**
     * Creates a new bounded {@link DirectChannel} with the specified capacity.
     *
     * @param <T>      the element type
     * @param capacity the maximum number of elements the channel can buffer
     * @return a new bounded channel
     * @throws IllegalArgumentException if capacity is not positive
     */
    public static <T> DirectChannel<T> channel(int capacity) {
        return new DirectChannel<>(capacity);
    }

    // ---- Fork ----

    /**
     * Forks a computation within the given scope. The computation runs concurrently
     * and its lifecycle is managed by the scope.
     *
     * @param <T>   the result type
     * @param scope the scope in which to fork
     * @param task  the computation to run
     * @return a {@link Fork} representing the running computation
     */
    public static <T> Fork<T> fork(Scope scope, Callable<T> task) {
        return scope.fork(task);
    }

    // ---- Parallel execution ----

    /**
     * Executes two computations in parallel and returns both results as a {@link Par.Pair}.
     * If either computation fails, the other is cancelled.
     *
     * @param <A>   the result type of the first computation
     * @param <B>   the result type of the second computation
     * @param taskA the first computation
     * @param taskB the second computation
     * @return a pair containing both results
     * @throws Exception if either computation fails
     */
    public static <A, B> Par.Pair<A, B> par(Callable<A> taskA, Callable<B> taskB) throws Exception {
        return Par.par(taskA, taskB);
    }

    /**
     * Executes a list of computations in parallel and returns all results.
     * If any computation fails, the others are cancelled.
     *
     * @param <T>   the result type
     * @param tasks the computations to run in parallel
     * @return a list of results in the same order as the input tasks
     * @throws Exception if any computation fails
     */
    public static <T> List<T> par(List<Callable<T>> tasks) throws Exception {
        return Par.par(tasks);
    }

    // ---- Racing ----

    /**
     * Races multiple computations against each other. Returns the result of the first
     * computation to complete successfully. All other computations are cancelled.
     *
     * @param <T>   the result type
     * @param tasks the computations to race
     * @return the result of the first successful computation
     * @throws Exception if all computations fail
     */
    @SafeVarargs
    public static <T> T race(Callable<T>... tasks) throws Exception {
        return Race.race(tasks);
    }

    /**
     * Races a list of computations against each other. Returns the result of the first
     * computation to complete successfully. All other computations are cancelled.
     *
     * @param <T>   the result type
     * @param tasks the computations to race
     * @return the result of the first successful computation
     * @throws Exception if all computations fail
     */
    public static <T> T race(List<Callable<T>> tasks) throws Exception {
        return Race.race(tasks);
    }
}