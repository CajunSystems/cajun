package com.cajunsystems.direct;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Utility class providing static entry-point methods for structured concurrency scopes.
 * Inspired by Ox for Scala, this class provides two modes of operation:
 *
 * <ul>
 *   <li><b>supervised</b> - fail-fast on any error, propagates the first exception</li>
 *   <li><b>scoped</b> - waits for all forked tasks to complete, collects errors</li>
 * </ul>
 *
 * Both methods block the calling thread until the scope completes and ensure all resources
 * are cleaned up.
 */
public final class Scoped {

    private Scoped() {
        // Utility class — no instantiation
    }

    /**
     * Creates an {@link ActorScope} in supervised mode (fail-fast on any error).
     * Blocks the calling thread until the scope completes. If any forked task fails,
     * the scope is cancelled and the first exception is propagated.
     *
     * @param body the scope body that receives a {@link ScopeContext} to fork tasks
     * @throws ScopeException if any forked task throws an exception
     */
    public static void supervised(Consumer<ScopeContext> body) {
        supervised(ctx -> {
            body.accept(ctx);
            return null;
        });
    }

    /**
     * Creates an {@link ActorScope} in supervised mode (fail-fast on any error).
     * Blocks the calling thread until the scope completes and returns the computed value.
     * If any forked task fails, the scope is cancelled and the first exception is propagated.
     *
     * @param body the scope body that receives a {@link ScopeContext} and returns a value
     * @param <T>  the return type
     * @return the value computed by the body
     * @throws ScopeException if any forked task throws an exception
     */
    public static <T> T supervised(Function<ScopeContext, T> body) {
        try (ActorScope scope = ActorScope.supervised()) {
            ScopeContext ctx = scope.context();
            T result = body.apply(ctx);
            scope.join();
            return result;
        }
    }

    /**
     * Creates an {@link ActorScope} in scoped mode (waits for all forked tasks to complete).
     * Blocks the calling thread until all forked tasks finish. Errors are collected and
     * thrown as a single {@link ScopeException} with suppressed exceptions after all tasks complete.
     *
     * @param body the scope body that receives a {@link ScopeContext} to fork tasks
     * @throws ScopeException if any forked task throws an exception (after all tasks complete)
     */
    public static void scoped(Consumer<ScopeContext> body) {
        scoped(ctx -> {
            body.accept(ctx);
            return null;
        });
    }

    /**
     * Creates an {@link ActorScope} in scoped mode (waits for all forked tasks to complete).
     * Blocks the calling thread until all forked tasks finish and returns the computed value.
     * Errors are collected and thrown as a single {@link ScopeException} with suppressed
     * exceptions after all tasks complete.
     *
     * @param body the scope body that receives a {@link ScopeContext} and returns a value
     * @param <T>  the return type
     * @return the value computed by the body
     * @throws ScopeException if any forked task throws an exception (after all tasks complete)
     */
    public static <T> T scoped(Function<ScopeContext, T> body) {
        try (ActorScope scope = ActorScope.scoped()) {
            ScopeContext ctx = scope.context();
            T result = body.apply(ctx);
            scope.join();
            return result;
        }
    }
}