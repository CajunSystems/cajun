package com.cajunsystems.direct;

import com.cajunsystems.Pid;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * A helper/utility class that implements the bridge between blocking direct-style ask
 * and the underlying asynchronous CompletableFuture-based messaging.
 *
 * <p>Provides static methods like {@code ask(Pid, message, timeout)} that create a
 * CompletableFuture, send the message with a reply-to mechanism, and block the calling
 * (virtual) thread using {@code future.get(timeout)} — converting TimeoutException to
 * AskTimeoutException and ExecutionException to the underlying cause.</p>
 *
 * <p>This is designed to be used with virtual threads where blocking is cheap.</p>
 */
public final class AskPattern {

    private AskPattern() {
        // Utility class — no instantiation
    }

    /**
     * Sends a message to the target actor and blocks the calling thread until a reply
     * is received or the timeout expires.
     *
     * @param <T>             the message type of the target actor
     * @param <R>             the expected reply type
     * @param target          the Pid of the actor to send the message to
     * @param messageFactory  a function that takes a CompletableFuture (used as the reply-to
     *                        mechanism) and returns the message to send to the target actor
     * @param timeout         the maximum duration to wait for a reply
     * @return the reply from the target actor
     * @throws AskTimeoutException if the reply is not received within the timeout
     * @throws RuntimeException    if the target actor replies with an exception
     */
    @SuppressWarnings("unchecked")
    public static <T, R> R ask(Pid<T> target, Function<CompletableFuture<R>, T> messageFactory, Duration timeout) {
        CompletableFuture<R> future = new CompletableFuture<>();
        T message = messageFactory.apply(future);
        target.tell(message);
        return awaitResult(future, timeout);
    }

    /**
     * Sends a pre-constructed message to the target actor and blocks the calling thread
     * until the associated future completes or the timeout expires.
     *
     * <p>This variant is useful when the message and future have already been constructed.</p>
     *
     * @param <T>     the message type of the target actor
     * @param <R>     the expected reply type
     * @param target  the Pid of the actor to send the message to
     * @param message the message to send
     * @param future  the CompletableFuture that will be completed with the reply
     * @param timeout the maximum duration to wait for a reply
     * @return the reply from the target actor
     * @throws AskTimeoutException if the reply is not received within the timeout
     * @throws RuntimeException    if the target actor replies with an exception
     */
    public static <T, R> R ask(Pid<T> target, T message, CompletableFuture<R> future, Duration timeout) {
        target.tell(message);
        return awaitResult(future, timeout);
    }

    /**
     * Blocks the calling thread waiting for the CompletableFuture to complete,
     * translating exceptions appropriately.
     *
     * @param <R>     the expected result type
     * @param future  the future to await
     * @param timeout the maximum duration to wait
     * @return the result
     * @throws AskTimeoutException if the timeout expires
     * @throws RuntimeException    wrapping the underlying cause on execution failure
     */
    private static <R> R awaitResult(CompletableFuture<R> future, Duration timeout) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AskTimeoutException("Ask timed out after " + timeout, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            } else if (cause instanceof Error error) {
                throw error;
            } else {
                throw new RuntimeException("Ask failed", cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new RuntimeException("Ask interrupted", e);
        }
    }
}