/**
 * Direct-style concurrency API for cajun actors, inspired by Scala's
 * <a href="https://github.com/softwaremill/ox">Ox</a> library.
 *
 * <p>This package provides a structured, direct-style programming model built on
 * Java 21+ virtual threads. Instead of writing callback-heavy or reactive-style
 * code, you can write straightforward blocking code that is automatically managed
 * within supervised scopes.
 *
 * <h2>Key Concepts</h2>
 * <ul>
 *   <li><b>Supervised scopes</b> — Structured concurrency regions ({@link com.cajunsystems.direct.Scope})
 *       that ensure all forked computations complete (or are cancelled) before the scope exits.
 *       Errors in any child fork propagate to the enclosing scope.</li>
 *   <li><b>Typed channels</b> — Bounded, blocking channels ({@link com.cajunsystems.direct.DirectChannel})
 *       for communicating between concurrent computations within a scope. Supports
 *       send/receive semantics with backpressure.</li>
 *   <li><b>Fork</b> — A handle ({@link com.cajunsystems.direct.Fork}) representing a
 *       computation running concurrently on a virtual thread within a supervised scope.</li>
 *   <li><b>Par</b> — A combinator ({@link com.cajunsystems.direct.Par}) for running
 *       multiple computations in parallel and collecting all of their results.</li>
 *   <li><b>Race</b> — A combinator ({@link com.cajunsystems.direct.Race}) for running
 *       multiple computations concurrently and returning the result of the first one
 *       to complete, cancelling the rest.</li>
 *   <li><b>Virtual-thread-based blocking</b> — All operations use virtual threads
 *       under the hood, so blocking calls (channel send/receive, fork joins) are
 *       lightweight and do not pin platform threads.</li>
 * </ul>
 *
 * <h2>Main Entry Point</h2>
 * <p>The primary entry point is the {@link com.cajunsystems.direct.Direct} class, which
 * provides static factory methods for creating supervised scopes and using the
 * combinators:
 *
 * <pre>{@code
 * import com.cajunsystems.direct.Direct;
 *
 * // Run two computations in parallel within a supervised scope
 * Direct.supervised(scope -> {
 *     var fork1 = scope.fork(() -> computeA());
 *     var fork2 = scope.fork(() -> computeB());
 *     return fork1.join() + fork2.join();
 * });
 *
 * // Race two computations, returning the faster result
 * String result = Direct.supervised(scope ->
 *     Race.of(scope, () -> fetchFromServiceA(), () -> fetchFromServiceB())
 * );
 *
 * // Communicate between forks via a typed channel
 * Direct.supervised(scope -> {
 *     DirectChannel<String> channel = new DirectChannel<>(10);
 *     scope.fork(() -> { channel.send("hello"); return null; });
 *     scope.fork(() -> { System.out.println(channel.receive()); return null; });
 *     return null;
 * });
 * }</pre>
 *
 * <h2>Key Types</h2>
 * <ul>
 *   <li>{@link com.cajunsystems.direct.Direct} — Static entry point with {@code supervised()} and convenience methods.</li>
 *   <li>{@link com.cajunsystems.direct.Scope} — A supervised structured concurrency scope.</li>
 *   <li>{@link com.cajunsystems.direct.DirectChannel} — A bounded, typed channel for inter-fork communication.</li>
 *   <li>{@link com.cajunsystems.direct.Fork} — A handle to a forked concurrent computation.</li>
 *   <li>{@link com.cajunsystems.direct.Par} — Parallel execution combinator.</li>
 *   <li>{@link com.cajunsystems.direct.Race} — Racing execution combinator.</li>
 * </ul>
 *
 * @see com.cajunsystems.direct.Direct
 * @see com.cajunsystems.direct.Scope
 * @see com.cajunsystems.direct.DirectChannel
 * @see com.cajunsystems.direct.Fork
 * @see com.cajunsystems.direct.Par
 * @see com.cajunsystems.direct.Race
 */
package com.cajunsystems.direct;