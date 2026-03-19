package com.cajunsystems.direct;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.mailbox.config.MailboxProvider;

/**
 * Entry point for creating direct-style actors.
 *
 * <p>{@code DirectActorSystem} wraps an {@link ActorSystem} and provides a fluent API for
 * spawning actors that can be called synchronously (blocking on virtual threads) or
 * asynchronously via {@link DirectPid}.
 *
 * <h2>Quick Start</h2>
 * <pre>{@code
 * // Create the system
 * DirectActorSystem system = new DirectActorSystem();
 *
 * // Define a handler
 * public class CalculatorHandler implements DirectHandler<CalculatorMsg, Double> {
 *     public Double handle(CalculatorMsg msg, DirectContext ctx) {
 *         return switch (msg) {
 *             case Add(double a, double b)      -> a + b;
 *             case Multiply(double a, double b) -> a * b;
 *         };
 *     }
 * }
 *
 * // Spawn an actor
 * DirectPid<CalculatorMsg, Double> calc = system.actorOf(new CalculatorHandler())
 *     .withId("calculator")
 *     .spawn();
 *
 * // Call it like a method
 * double result = calc.call(new CalculatorMsg.Add(3, 4));  // 7.0
 *
 * system.shutdown();
 * }</pre>
 *
 * <h2>Stateful Actors</h2>
 * <pre>{@code
 * DirectPid<CounterMsg, Integer> counter = system.statefulActorOf(new CounterHandler(), 0)
 *     .withId("counter")
 *     .spawn();
 *
 * counter.call(new CounterMsg.Increment());   // 1
 * counter.call(new CounterMsg.Increment());   // 2
 * int value = counter.call(new CounterMsg.GetValue()); // 2
 * }</pre>
 */
public class DirectActorSystem {

    private final ActorSystem underlying;

    /**
     * Creates a new {@code DirectActorSystem} with default settings.
     * Uses virtual threads for actor execution.
     */
    public DirectActorSystem() {
        this.underlying = new ActorSystem();
    }

    /**
     * Creates a new {@code DirectActorSystem} with a custom thread pool factory.
     *
     * @param threadPoolFactory The thread pool factory to use system-wide
     */
    public DirectActorSystem(ThreadPoolFactory threadPoolFactory) {
        this.underlying = new ActorSystem(threadPoolFactory);
    }

    /**
     * Creates a new {@code DirectActorSystem} with a custom thread pool factory and
     * default backpressure configuration.
     *
     * @param threadPoolFactory The thread pool factory
     * @param backpressureConfig The default backpressure configuration
     */
    public DirectActorSystem(ThreadPoolFactory threadPoolFactory,
                             BackpressureConfig backpressureConfig) {
        this.underlying = new ActorSystem(threadPoolFactory, backpressureConfig);
    }

    /**
     * Wraps an existing {@link ActorSystem}, allowing the direct API to coexist with
     * the standard actor API in the same system.
     *
     * @param actorSystem The existing actor system to wrap
     */
    public DirectActorSystem(ActorSystem actorSystem) {
        this.underlying = actorSystem;
    }

    /**
     * Creates a builder for a new stateless direct-style actor.
     *
     * <p>The handler instance is used directly — it will not be reinstantiated by the
     * framework. This allows pre-configured or injected handlers.
     *
     * @param <Message> The message type
     * @param <Reply>   The reply type
     * @param handler   The handler instance
     * @return A fluent builder for spawning the actor
     */
    public <Message, Reply> DirectPidBuilder<Message, Reply> actorOf(
            DirectHandler<Message, Reply> handler) {
        return new DirectPidBuilder<>(underlying, handler);
    }

    /**
     * Creates a builder for a new stateful direct-style actor with the given initial state.
     *
     * @param <State>      The state type
     * @param <Message>    The message type
     * @param <Reply>      The reply type
     * @param handler      The handler instance
     * @param initialState The initial state
     * @return A fluent builder for spawning the actor
     */
    public <State, Message, Reply> StatefulDirectPidBuilder<State, Message, Reply> statefulActorOf(
            StatefulDirectHandler<State, Message, Reply> handler,
            State initialState) {
        return new StatefulDirectPidBuilder<>(underlying, handler, initialState);
    }

    /**
     * Returns the underlying {@link ActorSystem} for interoperability with the standard API.
     *
     * @return The underlying ActorSystem
     */
    public ActorSystem underlying() {
        return underlying;
    }

    /**
     * Shuts down the actor system, stopping all actors gracefully.
     */
    public void shutdown() {
        underlying.shutdown();
    }
}
