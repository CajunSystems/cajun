package com.cajunsystems.direct;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.SupervisionStrategy;
import com.cajunsystems.builder.ActorBuilder;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.config.ResizableMailboxConfig;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.direct.internal.ActorChannelImpl;
import com.cajunsystems.direct.internal.ChannelHandlerAdapter;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A structured-concurrency scope for direct-style actors.
 *
 * <p>Actors spawned inside a scope are automatically stopped when the scope is closed, in
 * reverse spawn order. This prevents actor leaks and makes lifecycles explicit.
 *
 * <p>{@code ActorScope} implements {@link AutoCloseable} so it works naturally with Java's
 * try-with-resources:
 *
 * <pre>{@code
 * try (ActorScope scope = ActorScope.open()) {
 *
 *     // Generator-style actor: write message handling as a plain loop
 *     DirectPid<CalcMsg, Double> calc = scope.actor(channel -> {
 *         while (channel.isOpen()) {
 *             channel.handle(msg -> switch (msg) {
 *                 case CalcMsg.Add(double a, double b)      -> a + b;
 *                 case CalcMsg.Multiply(double a, double b) -> a * b;
 *             });
 *         }
 *     });
 *
 *     // Handler-style actor (classic interface, scope-tracked)
 *     DirectPid<Msg, Reply> pid = scope.actorOf(new MyHandler())
 *         .withId("my-actor")
 *         .spawn();
 *
 *     // Both actors are stopped automatically at the end of the try block
 * }
 * }</pre>
 *
 * <h2>Generator-style actors</h2>
 *
 * <p>{@link #actor(Consumer)} starts the loop body in a virtual thread and creates a Cajun
 * actor whose mailbox feeds messages through an {@link ActorChannel}. The loop runs until
 * the scope closes, at which point the channel is closed and {@link ActorChannel#isOpen()}
 * returns {@code false}.
 *
 * <h2>Sharing a system</h2>
 *
 * <p>By default, {@link #open()} creates a new {@link DirectActorSystem}. To share an
 * existing system (e.g. for interoperability), use {@link #open(DirectActorSystem)}. When
 * sharing, only the actors spawned by this scope are stopped on close — the shared system
 * itself is left running.
 */
public final class ActorScope implements AutoCloseable {

    private final DirectActorSystem system;
    private final boolean ownsSystem;
    private final List<DirectPid<?, ?>> spawnedActors = new CopyOnWriteArrayList<>();

    private ActorScope(DirectActorSystem system, boolean ownsSystem) {
        this.system = system;
        this.ownsSystem = ownsSystem;
    }

    /**
     * Opens a new scope backed by a freshly created {@link DirectActorSystem}.
     * The system is shut down when the scope is closed.
     *
     * @return A new {@code ActorScope}
     */
    public static ActorScope open() {
        return new ActorScope(new DirectActorSystem(), true);
    }

    /**
     * Opens a new scope that shares the given {@link DirectActorSystem}.
     * The system is <em>not</em> shut down when the scope is closed.
     *
     * @param system The system to share
     * @return A new {@code ActorScope}
     */
    public static ActorScope open(DirectActorSystem system) {
        return new ActorScope(system, false);
    }

    // -------------------------------------------------------------------------
    // Generator-style actor creation
    // -------------------------------------------------------------------------

    /**
     * Creates a generator-style actor backed by the given loop body.
     *
     * <p>The {@code loop} runs in a virtual thread. It receives messages via
     * {@link ActorChannel#handle} and sends replies by returning values from the supplied
     * function. The loop should continue while {@link ActorChannel#isOpen()} returns
     * {@code true}.
     *
     * <p>The actor is stopped (and the channel closed) when this scope is closed.
     *
     * @param <Message> The message type
     * @param <Reply>   The reply type
     * @param loop      The loop body; receives an {@link ActorChannel} as its argument
     * @return A {@link DirectPid} for communicating with the actor
     */
    public <Message, Reply> DirectPid<Message, Reply> actor(
            Consumer<ActorChannel<Message, Reply>> loop) {
        return actor(loop, Duration.ofSeconds(30));
    }

    /**
     * Creates a generator-style actor with a custom default call timeout.
     *
     * @param <Message>      The message type
     * @param <Reply>        The reply type
     * @param loop           The loop body
     * @param defaultTimeout Default timeout for {@link DirectPid#call} operations
     * @return A {@link DirectPid} for communicating with the actor
     */
    public <Message, Reply> DirectPid<Message, Reply> actor(
            Consumer<ActorChannel<Message, Reply>> loop,
            Duration defaultTimeout) {
        ActorChannelImpl<Message, Reply> channel = new ActorChannelImpl<>();
        ChannelHandlerAdapter<Message, Reply> adapter = new ChannelHandlerAdapter<>(channel);

        // Spawn the Cajun actor using the channel adapter as its Handler.
        // The actor's postStop will close the channel, signalling the loop to exit.
        ActorSystem underlying = system.underlying();
        ActorBuilder<Message> builder = underlying.actorOf(adapter);
        com.cajunsystems.Pid pid = builder.spawn();

        // Start the user's loop in a virtual thread AFTER the actor is registered,
        // so the loop sees a fully initialised channel.
        Thread.ofVirtual().start(() -> loop.accept(channel));

        DirectPid<Message, Reply> directPid = new DirectPid<>(pid, defaultTimeout);
        track(directPid);
        return directPid;
    }

    // -------------------------------------------------------------------------
    // Handler-style actor creation (scope-tracked)
    // -------------------------------------------------------------------------

    /**
     * Creates a scope-tracked builder for a stateless handler-style actor.
     *
     * <p>The actor is stopped when this scope is closed.
     *
     * @param <Message> The message type
     * @param <Reply>   The reply type
     * @param handler   The handler
     * @return A fluent builder whose {@link ScopedPidBuilder#spawn()} registers the actor
     */
    public <Message, Reply> ScopedPidBuilder<Message, Reply> actorOf(
            DirectHandler<Message, Reply> handler) {
        return new ScopedPidBuilder<>(this, system.actorOf(handler));
    }

    /**
     * Creates a scope-tracked builder for a stateful handler-style actor.
     *
     * <p>The actor is stopped when this scope is closed.
     *
     * @param <State>      The state type
     * @param <Message>    The message type
     * @param <Reply>      The reply type
     * @param handler      The handler
     * @param initialState The initial actor state
     * @return A fluent builder whose {@link ScopedStatefulPidBuilder#spawn()} registers the actor
     */
    public <State, Message, Reply> ScopedStatefulPidBuilder<State, Message, Reply> statefulActorOf(
            StatefulDirectHandler<State, Message, Reply> handler,
            State initialState) {
        return new ScopedStatefulPidBuilder<>(this, system.statefulActorOf(handler, initialState));
    }

    /**
     * Returns the underlying {@link DirectActorSystem}.
     *
     * @return The actor system
     */
    public DirectActorSystem system() {
        return system;
    }

    /**
     * Tracks a {@link DirectPid} so it is stopped when the scope closes.
     *
     * @param pid The pid to track
     */
    void track(DirectPid<?, ?> pid) {
        spawnedActors.add(pid);
    }

    /**
     * Stops all actors spawned within this scope in reverse order, then shuts down the
     * underlying system (if this scope owns it).
     */
    @Override
    public void close() {
        // Stop in reverse spawn order
        for (int i = spawnedActors.size() - 1; i >= 0; i--) {
            DirectPid<?, ?> pid = spawnedActors.get(i);
            try {
                system.underlying().stopActor(pid.pid());
            } catch (Exception ignored) {
                // Best-effort: stop remaining actors even if one fails
            }
        }
        if (ownsSystem) {
            system.shutdown();
        }
    }

    // =========================================================================
    // Scope-aware builder wrappers
    // =========================================================================

    /**
     * Fluent builder for a scope-tracked stateless actor.
     * Delegates to an underlying {@link DirectPidBuilder} and registers the actor on
     * {@link #spawn()}.
     *
     * @param <Message> The message type
     * @param <Reply>   The reply type
     */
    public static final class ScopedPidBuilder<Message, Reply> {

        private final ActorScope scope;
        private final DirectPidBuilder<Message, Reply> delegate;

        ScopedPidBuilder(ActorScope scope, DirectPidBuilder<Message, Reply> delegate) {
            this.scope = scope;
            this.delegate = delegate;
        }

        public ScopedPidBuilder<Message, Reply> withId(String id) {
            delegate.withId(id);
            return this;
        }

        public ScopedPidBuilder<Message, Reply> withTimeout(Duration timeout) {
            delegate.withTimeout(timeout);
            return this;
        }

        public ScopedPidBuilder<Message, Reply> withBackpressureConfig(BackpressureConfig config) {
            delegate.withBackpressureConfig(config);
            return this;
        }

        public ScopedPidBuilder<Message, Reply> withMailboxConfig(ResizableMailboxConfig config) {
            delegate.withMailboxConfig(config);
            return this;
        }

        public ScopedPidBuilder<Message, Reply> withSupervisionStrategy(SupervisionStrategy strategy) {
            delegate.withSupervisionStrategy(strategy);
            return this;
        }

        public ScopedPidBuilder<Message, Reply> withThreadPoolFactory(ThreadPoolFactory factory) {
            delegate.withThreadPoolFactory(factory);
            return this;
        }

        /**
         * Spawns the actor and registers it with the enclosing scope.
         *
         * @return A type-safe {@link DirectPid}
         */
        public DirectPid<Message, Reply> spawn() {
            DirectPid<Message, Reply> pid = delegate.spawn();
            scope.track(pid);
            return pid;
        }
    }

    /**
     * Fluent builder for a scope-tracked stateful actor.
     *
     * @param <State>   The state type
     * @param <Message> The message type
     * @param <Reply>   The reply type
     */
    public static final class ScopedStatefulPidBuilder<State, Message, Reply> {

        private final ActorScope scope;
        private final StatefulDirectPidBuilder<State, Message, Reply> delegate;

        ScopedStatefulPidBuilder(
                ActorScope scope,
                StatefulDirectPidBuilder<State, Message, Reply> delegate) {
            this.scope = scope;
            this.delegate = delegate;
        }

        public ScopedStatefulPidBuilder<State, Message, Reply> withId(String id) {
            delegate.withId(id);
            return this;
        }

        public ScopedStatefulPidBuilder<State, Message, Reply> withTimeout(Duration timeout) {
            delegate.withTimeout(timeout);
            return this;
        }

        public ScopedStatefulPidBuilder<State, Message, Reply> withBackpressureConfig(
                BackpressureConfig config) {
            delegate.withBackpressureConfig(config);
            return this;
        }

        public ScopedStatefulPidBuilder<State, Message, Reply> withMailboxConfig(
                ResizableMailboxConfig config) {
            delegate.withMailboxConfig(config);
            return this;
        }

        public ScopedStatefulPidBuilder<State, Message, Reply> withSupervisionStrategy(
                SupervisionStrategy strategy) {
            delegate.withSupervisionStrategy(strategy);
            return this;
        }

        public ScopedStatefulPidBuilder<State, Message, Reply> withThreadPoolFactory(
                ThreadPoolFactory factory) {
            delegate.withThreadPoolFactory(factory);
            return this;
        }

        /**
         * Spawns the actor and registers it with the enclosing scope.
         *
         * @return A type-safe {@link DirectPid}
         */
        public DirectPid<Message, Reply> spawn() {
            DirectPid<Message, Reply> pid = delegate.spawn();
            scope.track(pid);
            return pid;
        }
    }
}
