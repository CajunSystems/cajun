package com.cajunsystems.direct;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Function;

/**
 * Utility class for parallel execution of tasks using structured concurrency.
 * <p>
 * Inspired by Ox for Scala, this class provides combinators for running
 * multiple tasks in parallel with automatic cancellation on first failure.
 */
public final class Par {

    private Par() {
        // Utility class, no instantiation
    }

    /**
     * A pair of two values.
     */
    public record Pair<A, B>(A first, B second) {
    }

    /**
     * A triple of three values.
     */
    public record Triple<A, B, C>(A first, B second, C third) {
    }

    /**
     * A tuple of four values.
     */
    public record Tuple4<A, B, C, D>(A first, B second, C third, D fourth) {
    }

    /**
     * Runs two callables in parallel and returns both results as a {@link Pair}.
     * If either task fails, the other is cancelled and the exception is propagated.
     *
     * @param a   the first callable
     * @param b   the second callable
     * @param <A> the type of the first result
     * @param <B> the type of the second result
     * @return a Pair containing both results
     * @throws Exception if any task fails
     */
    public static <A, B> Pair<A, B> par(Callable<A> a, Callable<B> b) throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            StructuredTaskScope.Subtask<A> futureA = scope.fork(a);
            StructuredTaskScope.Subtask<B> futureB = scope.fork(b);

            scope.join();
            scope.throwIfFailed();

            return new Pair<>(futureA.get(), futureB.get());
        }
    }

    /**
     * Runs three callables in parallel and returns all results as a {@link Triple}.
     * If any task fails, the others are cancelled and the exception is propagated.
     *
     * @param a   the first callable
     * @param b   the second callable
     * @param c   the third callable
     * @param <A> the type of the first result
     * @param <B> the type of the second result
     * @param <C> the type of the third result
     * @return a Triple containing all results
     * @throws Exception if any task fails
     */
    public static <A, B, C> Triple<A, B, C> par(Callable<A> a, Callable<B> b, Callable<C> c) throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            StructuredTaskScope.Subtask<A> futureA = scope.fork(a);
            StructuredTaskScope.Subtask<B> futureB = scope.fork(b);
            StructuredTaskScope.Subtask<C> futureC = scope.fork(c);

            scope.join();
            scope.throwIfFailed();

            return new Triple<>(futureA.get(), futureB.get(), futureC.get());
        }
    }

    /**
     * Runs four callables in parallel and returns all results as a {@link Tuple4}.
     * If any task fails, the others are cancelled and the exception is propagated.
     *
     * @param a   the first callable
     * @param b   the second callable
     * @param c   the third callable
     * @param d   the fourth callable
     * @param <A> the type of the first result
     * @param <B> the type of the second result
     * @param <C> the type of the third result
     * @param <D> the type of the fourth result
     * @return a Tuple4 containing all results
     * @throws Exception if any task fails
     */
    public static <A, B, C, D> Tuple4<A, B, C, D> par(
            Callable<A> a, Callable<B> b, Callable<C> c, Callable<D> d) throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            StructuredTaskScope.Subtask<A> futureA = scope.fork(a);
            StructuredTaskScope.Subtask<B> futureB = scope.fork(b);
            StructuredTaskScope.Subtask<C> futureC = scope.fork(c);
            StructuredTaskScope.Subtask<D> futureD = scope.fork(d);

            scope.join();
            scope.throwIfFailed();

            return new Tuple4<>(futureA.get(), futureB.get(), futureC.get(), futureD.get());
        }
    }

    /**
     * Runs a list of callables in parallel and returns a list of all results.
     * The order of results matches the order of the input callables.
     * If any task fails, the others are cancelled and the exception is propagated.
     *
     * @param callables the list of callables to execute in parallel
     * @param <T>       the type of the results
     * @return a list of results in the same order as the input callables
     * @throws Exception if any task fails
     */
    public static <T> List<T> par(List<Callable<T>> callables) throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            List<StructuredTaskScope.Subtask<T>> subtasks = new ArrayList<>(callables.size());

            for (Callable<T> callable : callables) {
                subtasks.add(scope.fork(callable));
            }

            scope.join();
            scope.throwIfFailed();

            List<T> results = new ArrayList<>(subtasks.size());
            for (StructuredTaskScope.Subtask<T> subtask : subtasks) {
                results.add(subtask.get());
            }
            return results;
        }
    }

    /**
     * Applies a function to each item in the list in parallel and returns the results.
     * The order of results matches the order of the input items.
     * If any mapping fails, the others are cancelled and the exception is propagated.
     *
     * @param items    the list of items to map over
     * @param function the function to apply to each item
     * @param <T>      the type of the input items
     * @param <R>      the type of the results
     * @return a list of results in the same order as the input items
     * @throws Exception if any mapping fails
     */
    public static <T, R> List<R> parMap(List<T> items, Function<T, R> function) throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            List<StructuredTaskScope.Subtask<R>> subtasks = new ArrayList<>(items.size());

            for (T item : items) {
                subtasks.add(scope.fork(() -> function.apply(item)));
            }

            scope.join();
            scope.throwIfFailed();

            List<R> results = new ArrayList<>(subtasks.size());
            for (StructuredTaskScope.Subtask<R> subtask : subtasks) {
                results.add(subtask.get());
            }
            return results;
        }
    }
}