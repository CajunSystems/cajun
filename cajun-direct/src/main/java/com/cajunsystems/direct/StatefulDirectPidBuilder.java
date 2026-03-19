package com.cajunsystems.direct;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.SupervisionStrategy;
import com.cajunsystems.builder.ActorBuilder;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.config.ResizableMailboxConfig;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.direct.internal.StatefulDirectHandlerAdapter;

import java.time.Duration;

/**
 * Fluent builder for creating stateful direct-style actors.
 *
 * <p>Obtain an instance via {@link DirectActorSystem#statefulActorOf(StatefulDirectHandler, Object)}.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * DirectPid<CounterMsg, Integer> counter = system.statefulActorOf(new CounterHandler(), 0)
 *     .withId("counter")
 *     .withTimeout(Duration.ofSeconds(5))
 *     .spawn();
 * }</pre>
 *
 * @param <State>   The state type
 * @param <Message> The message type
 * @param <Reply>   The reply type
 */
public class StatefulDirectPidBuilder<State, Message, Reply> {

    private final ActorSystem system;
    private Duration timeout = Duration.ofSeconds(30);
    private final ActorBuilder<Message> underlying;

    StatefulDirectPidBuilder(
            ActorSystem system,
            StatefulDirectHandler<State, Message, Reply> handler,
            State initialState) {
        this.system = system;
        StatefulDirectHandlerAdapter<State, Message, Reply> adapter =
                new StatefulDirectHandlerAdapter<>(handler, initialState);
        this.underlying = system.actorOf(adapter);
    }

    /**
     * Sets the explicit actor ID.
     *
     * @param id The actor ID
     * @return This builder
     */
    public StatefulDirectPidBuilder<State, Message, Reply> withId(String id) {
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
    public StatefulDirectPidBuilder<State, Message, Reply> withTimeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * Sets the backpressure configuration.
     *
     * @param config The backpressure configuration
     * @return This builder
     */
    public StatefulDirectPidBuilder<State, Message, Reply> withBackpressureConfig(BackpressureConfig config) {
        underlying.withBackpressureConfig(config);
        return this;
    }

    /**
     * Sets the mailbox configuration.
     *
     * @param config The mailbox configuration
     * @return This builder
     */
    public StatefulDirectPidBuilder<State, Message, Reply> withMailboxConfig(ResizableMailboxConfig config) {
        underlying.withMailboxConfig(config);
        return this;
    }

    /**
     * Sets the supervision strategy.
     *
     * @param strategy The supervision strategy
     * @return This builder
     */
    public StatefulDirectPidBuilder<State, Message, Reply> withSupervisionStrategy(SupervisionStrategy strategy) {
        underlying.withSupervisionStrategy(strategy);
        return this;
    }

    /**
     * Sets the thread pool factory.
     *
     * @param factory The thread pool factory
     * @return This builder
     */
    public StatefulDirectPidBuilder<State, Message, Reply> withThreadPoolFactory(ThreadPoolFactory factory) {
        underlying.withThreadPoolFactory(factory);
        return this;
    }

    /**
     * Creates and starts the stateful actor.
     *
     * @return A type-safe {@link DirectPid} for communicating with the actor
     */
    public DirectPid<Message, Reply> spawn() {
        Pid pid = underlying.spawn();
        return new DirectPid<>(pid, timeout);
    }
}
