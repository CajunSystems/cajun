package com.cajunsystems.direct;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.SupervisionStrategy;
import com.cajunsystems.builder.ActorBuilder;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.config.ResizableMailboxConfig;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.direct.internal.DirectHandlerAdapter;

import java.time.Duration;

/**
 * Fluent builder for creating stateless direct-style actors.
 *
 * <p>Obtain an instance via {@link DirectActorSystem#actorOf(DirectHandler)}.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * DirectPid<String, String> echo = system.actorOf(new EchoHandler())
 *     .withId("echo")
 *     .withTimeout(Duration.ofSeconds(10))
 *     .spawn();
 * }</pre>
 *
 * @param <Message> The message type
 * @param <Reply>   The reply type
 */
public class DirectPidBuilder<Message, Reply> {

    private final ActorSystem system;
    private final DirectHandler<Message, Reply> handler;
    private Duration timeout = Duration.ofSeconds(30);
    private final ActorBuilder<Message> underlying;

    DirectPidBuilder(ActorSystem system, DirectHandler<Message, Reply> handler) {
        this.system = system;
        this.handler = handler;
        DirectHandlerAdapter<Message, Reply> adapter = new DirectHandlerAdapter<>(handler);
        this.underlying = system.actorOf(adapter);
    }

    /**
     * Sets the explicit actor ID.
     *
     * @param id The actor ID
     * @return This builder
     */
    public DirectPidBuilder<Message, Reply> withId(String id) {
        underlying.withId(id);
        return this;
    }

    /**
     * Sets the default timeout for blocking {@link DirectPid#call} operations.
     * Defaults to 30 seconds.
     *
     * @param timeout The timeout duration
     * @return This builder
     */
    public DirectPidBuilder<Message, Reply> withTimeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * Sets the backpressure configuration.
     *
     * @param config The backpressure configuration
     * @return This builder
     */
    public DirectPidBuilder<Message, Reply> withBackpressureConfig(BackpressureConfig config) {
        underlying.withBackpressureConfig(config);
        return this;
    }

    /**
     * Sets the mailbox configuration.
     *
     * @param config The mailbox configuration
     * @return This builder
     */
    public DirectPidBuilder<Message, Reply> withMailboxConfig(ResizableMailboxConfig config) {
        underlying.withMailboxConfig(config);
        return this;
    }

    /**
     * Sets the supervision strategy.
     *
     * @param strategy The supervision strategy
     * @return This builder
     */
    public DirectPidBuilder<Message, Reply> withSupervisionStrategy(SupervisionStrategy strategy) {
        underlying.withSupervisionStrategy(strategy);
        return this;
    }

    /**
     * Sets the thread pool factory.
     *
     * @param factory The thread pool factory
     * @return This builder
     */
    public DirectPidBuilder<Message, Reply> withThreadPoolFactory(ThreadPoolFactory factory) {
        underlying.withThreadPoolFactory(factory);
        return this;
    }

    /**
     * Creates and starts the actor.
     *
     * @return A type-safe {@link DirectPid} for communicating with the actor
     */
    public DirectPid<Message, Reply> spawn() {
        Pid pid = underlying.spawn();
        return new DirectPid<>(pid, timeout);
    }
}
