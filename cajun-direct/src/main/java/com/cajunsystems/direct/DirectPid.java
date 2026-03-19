package com.cajunsystems.direct;

import com.cajunsystems.Pid;
import com.cajunsystems.direct.internal.DirectWireReply;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * A typed process identifier for direct-style actors — Cajun's direct-style equivalent of
 * {@link Pid}.
 *
 * <p>{@code DirectPid} carries the message and reply types at compile time and provides
 * three interaction modes, inspired by
 * <a href="https://github.com/softwaremill/ox">ox</a>'s direct-style actor API:
 *
 * <ul>
 *   <li><b>{@link #call}</b> — blocking send-and-receive. Reads like a normal method call.
 *       Safe on Java virtual threads (no platform thread pinning).</li>
 *   <li><b>{@link #callAsync}</b> — non-blocking send, returns a {@link CompletableFuture}
 *       for async composition with existing reactive code.</li>
 *   <li><b>{@link #tell}</b> — fire-and-forget; no reply expected.</li>
 * </ul>
 *
 * <p>Obtain a {@code DirectPid} via {@link DirectActorSystem#actorOf} or
 * {@link DirectActorSystem#statefulActorOf}.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * DirectPid<String, String> echo = system.actorOf(new EchoHandler())
 *     .withId("echo")
 *     .spawn();
 *
 * // Blocking call on a virtual thread — reads like a normal method call
 * String reply = echo.call("hello");
 *
 * // Async call for CompletableFuture composition
 * echo.callAsync("world").thenAccept(System.out::println);
 *
 * // Fire-and-forget notification
 * echo.tell("done");
 * }</pre>
 *
 * @param <Message> The type of messages the actor accepts
 * @param <Reply>   The type of replies the actor returns
 */
public class DirectPid<Message, Reply> {

    private final Pid pid;
    private final Duration defaultTimeout;

    DirectPid(Pid pid, Duration defaultTimeout) {
        this.pid = pid;
        this.defaultTimeout = defaultTimeout;
    }

    /**
     * Sends a message and blocks until a reply is received, using the default timeout.
     *
     * <p>This method is safe to call on Java virtual threads (Java 21+). On platform threads
     * it blocks the thread for the duration of the call.
     *
     * @param message The message to send
     * @return The reply from the actor
     * @throws DirectActorException if the call fails, times out, or the handler throws
     */
    public Reply call(Message message) {
        return call(message, defaultTimeout);
    }

    /**
     * Sends a message and blocks until a reply is received, with a custom timeout.
     *
     * @param message The message to send
     * @param timeout The maximum time to wait for a reply
     * @return The reply from the actor
     * @throws DirectActorException if the call fails, times out, or the handler throws
     */
    @SuppressWarnings("unchecked")
    public Reply call(Message message, Duration timeout) {
        try {
            Object rawReply = pid.ask(message, timeout).get();
            return unwrapReply(rawReply);
        } catch (DirectActorException e) {
            throw e;
        } catch (Exception e) {
            throw new DirectActorException("Call to actor '" + pid.actorId() + "' failed", e);
        }
    }

    /**
     * Sends a message asynchronously and returns a {@link CompletableFuture} for the reply,
     * using the default timeout.
     *
     * @param message The message to send
     * @return A CompletableFuture that completes with the actor's reply
     */
    public CompletableFuture<Reply> callAsync(Message message) {
        return callAsync(message, defaultTimeout);
    }

    /**
     * Sends a message asynchronously and returns a {@link CompletableFuture} for the reply,
     * with a custom timeout.
     *
     * @param message The message to send
     * @param timeout The maximum time to wait for a reply
     * @return A CompletableFuture that completes with the actor's reply
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Reply> callAsync(Message message, Duration timeout) {
        return pid.ask(message, timeout)
                .future()
                .thenApply(raw -> unwrapReply(raw));
    }

    /**
     * Sends a fire-and-forget message. The actor processes the message and computes a reply,
     * but the reply is discarded since there is no caller waiting for it.
     *
     * @param message The message to send
     */
    public void tell(Message message) {
        pid.tell(message);
    }

    /**
     * Returns the underlying {@link Pid} for interoperability with the base Cajun API.
     *
     * @return The actor's PID
     */
    public Pid pid() {
        return pid;
    }

    /**
     * Returns the actor ID.
     *
     * @return The actor ID string
     */
    public String actorId() {
        return pid.actorId();
    }

    /**
     * Returns the default timeout used for blocking {@link #call} operations.
     *
     * @return The default timeout
     */
    public Duration defaultTimeout() {
        return defaultTimeout;
    }

    /**
     * Returns a new {@code ActorRef} with a different default timeout.
     *
     * @param timeout The new default timeout
     * @return A new ActorRef with the updated timeout
     */
    public DirectPid<Message, Reply> withTimeout(Duration timeout) {
        return new DirectPid<>(pid, timeout);
    }

    @SuppressWarnings("unchecked")
    private Reply unwrapReply(Object rawReply) {
        if (rawReply instanceof DirectWireReply<?> wireReply) {
            return switch ((DirectWireReply<Reply>) wireReply) {
                case DirectWireReply.Success<Reply> s -> s.value();
                case DirectWireReply.Failure<Reply> f ->
                        throw new DirectActorException(f.errorMessage(), f.cause());
            };
        }
        // Fallback for interoperability with non-direct actors
        return (Reply) rawReply;
    }

    @Override
    public String toString() {
        return "DirectPid[" + pid + ", timeout=" + defaultTimeout + "]";
    }
}
