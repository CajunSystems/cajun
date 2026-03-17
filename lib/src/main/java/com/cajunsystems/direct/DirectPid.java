package com.cajunsystems.direct;

import com.cajunsystems.Pid;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A generic wrapper around the existing {@link Pid} that adds direct-style operations.
 * <p>
 * This class provides a convenient API for interacting with actors in a direct (blocking) style,
 * intended for use with virtual threads. It wraps an underlying {@code Pid<S>} and adds:
 * <ul>
 *   <li>{@link #tell(Object)} — fire-and-forget message passing (pass-through to the underlying Pid)</li>
 *   <li>{@link #ask(Function, Duration)} — sends a message containing a reply callback and blocks
 *       the current (virtual) thread until a response arrives or the timeout expires</li>
 *   <li>{@link #ask(Function)} — ask with a default timeout</li>
 * </ul>
 *
 * @param <S> the message type accepted by the underlying actor
 */
public class DirectPid<S> {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final Pid<S> underlying;

    /**
     * Creates a new DirectPid wrapping the given underlying Pid.
     *
     * @param underlying the underlying Pid to wrap
     * @throws NullPointerException if underlying is null
     */
    public DirectPid(Pid<S> underlying) {
        this.underlying = Objects.requireNonNull(underlying, "underlying Pid must not be null");
    }

    /**
     * Sends a fire-and-forget message to the underlying actor.
     *
     * @param message the message to send
     */
    public void tell(S message) {
        underlying.tell(message);
    }

    /**
     * Sends a message to the underlying actor that includes a reply callback, and blocks
     * the current thread until a response is received or the timeout expires.
     * <p>
     * The {@code messageFactory} function receives a {@link Consumer} that the actor should
     * invoke with its reply. The function must return the message to send to the actor.
     * <p>
     * This method is designed to be used on virtual threads for efficient blocking.
     *
     * <pre>{@code
     * // Example: actor handles messages of type MyProtocol
     * // where MyProtocol.GetValue has a Consumer<String> replyTo field
     * DirectPid<MyProtocol> pid = new DirectPid<>(actorPid);
     * String result = pid.ask(replyTo -> new MyProtocol.GetValue(replyTo), Duration.ofSeconds(3));
     * }</pre>
     *
     * @param messageFactory a function that takes a reply callback and produces the message to send
     * @param timeout        the maximum duration to wait for a reply
     * @param <R>            the reply type
     * @return the reply from the actor
     * @throws AskTimeoutException if no reply is received within the timeout
     * @throws NullPointerException if messageFactory or timeout is null
     */
    public <R> R ask(Function<Consumer<R>, S> messageFactory, Duration timeout) {
        Objects.requireNonNull(messageFactory, "messageFactory must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        return AskPattern.ask(underlying, messageFactory, timeout);
    }

    /**
     * Sends a message to the underlying actor that includes a reply callback, and blocks
     * the current thread until a response is received or the default timeout (5 seconds) expires.
     *
     * @param messageFactory a function that takes a reply callback and produces the message to send
     * @param <R>            the reply type
     * @return the reply from the actor
     * @throws AskTimeoutException if no reply is received within the default timeout
     * @throws NullPointerException if messageFactory is null
     * @see #ask(Function, Duration)
     */
    public <R> R ask(Function<Consumer<R>, S> messageFactory) {
        return ask(messageFactory, DEFAULT_TIMEOUT);
    }

    /**
     * Returns the underlying {@link Pid} that this DirectPid wraps.
     *
     * @return the underlying Pid
     */
    public Pid<S> underlying() {
        return underlying;
    }
}