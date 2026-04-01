package com.cajunsystems.internal;

import com.cajunsystems.*;
import com.cajunsystems.builder.ActorBuilder;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.mailbox.config.MailboxProvider;
import com.cajunsystems.config.ResizableMailboxConfig;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.functional.CajunEffectRuntime;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.loop.ActorBehavior;
import com.cajunsystems.loop.BehaviorMiddleware;
import com.cajunsystems.loop.LoopStep;
import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Internal implementation of a StatefulActor that delegates to a {@link StatefulHandler}.
 *
 * <h2>Phase 2: Actor loop as a Roux Effect</h2>
 *
 * <p>Each message-processing step is described by an {@link ActorBehavior} — a
 * functional interface that returns a Roux {@link Effect}{@code <E, LoopStep<State>>}.
 * Executing the effect yields one of three {@link LoopStep} variants that control
 * the actor's lifecycle:
 * <ul>
 *   <li>{@link LoopStep.Continue} — update state and keep processing</li>
 *   <li>{@link LoopStep.Stop}     — update state and gracefully stop the actor</li>
 *   <li>{@link LoopStep.Restart}  — update state and restart the actor</li>
 * </ul>
 *
 * <p>Cross-cutting concerns (logging, metrics, retry, dead-letter capture, …) are
 * composed by stacking {@link BehaviorMiddleware} instances around the base behavior
 * <em>before</em> this actor is spawned:
 *
 * <pre>{@code
 * Pid pid = system.statefulActorOf(MyHandler.class, initialState)
 *     .withMiddleware(new LoggingMiddleware<>("order-processor"))
 *     .withMiddleware(new MetricsMiddleware<>("order-processor"))
 *     .withMiddleware(RetryMiddleware.withExponentialBackoff(3, Duration.ofMillis(50)))
 *     .spawn();
 * }</pre>
 *
 * <p>The base behavior is assembled automatically from the {@link StatefulHandler#receive}
 * method, wrapping its returned {@code Effect<E, State>} into a {@link LoopStep.Continue}:
 * <pre>
 * handler.receive(msg, state, ctx) : Effect&lt;E, State&gt;
 *    .map(LoopStep::continue_)     : Effect&lt;E, LoopStep&lt;State&gt;&gt;
 * </pre>
 *
 * <p>Handlers that need explicit lifecycle control can call
 * {@link ActorContext#stop()} inside an effect (which produces a {@link LoopStep.Stop})
 * or use {@link com.cajunsystems.loop.SupervisionStrategies} when building the middleware
 * stack.
 *
 * <p>This class is not meant to be used directly by users.
 *
 * @param <E>       the error type declared by the handler
 * @param <State>   the type of the actor's state
 * @param <Message> the type of messages this actor processes
 */
public class StatefulHandlerActor<E extends Throwable, State, Message>
        extends StatefulActor<State, Message> {

    private final StatefulHandler<E, State, Message> handler;
    private final CajunEffectRuntime rouxRuntime;
    /** The composed behavior pipeline (base + all middleware layers). */
    private final ActorBehavior<E, State, Message> pipeline;
    /** Optional capability handler; when non-null, effects are run via {@code unsafeRunWithHandler}. */
    private CapabilityHandler<Capability<?>> capabilityHandler;

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Creates a new StatefulHandlerActor with no middleware.
     */
    public StatefulHandlerActor(
            ActorSystem system,
            String actorId,
            StatefulHandler<E, State, Message> handler,
            State initialState,
            BackpressureConfig backpressureConfig,
            ResizableMailboxConfig mailboxConfig,
            ThreadPoolFactory threadPoolFactory,
            MailboxProvider<Message> mailboxProvider) {
        this(system, actorId, handler, initialState, backpressureConfig, mailboxConfig,
             threadPoolFactory, mailboxProvider, List.of());
    }

    /**
     * Creates a new StatefulHandlerActor with a middleware stack.
     *
     * @param middlewares ordered list of middlewares applied outermost-last;
     *                    the last middleware in the list wraps the pipeline outermost
     */
    public StatefulHandlerActor(
            ActorSystem system,
            String actorId,
            StatefulHandler<E, State, Message> handler,
            State initialState,
            BackpressureConfig backpressureConfig,
            ResizableMailboxConfig mailboxConfig,
            ThreadPoolFactory threadPoolFactory,
            MailboxProvider<Message> mailboxProvider,
            List<BehaviorMiddleware<E, State, Message>> middlewares) {
        super(system, actorId, initialState, backpressureConfig, mailboxConfig, threadPoolFactory, mailboxProvider);
        this.handler = handler;
        this.rouxRuntime = CajunEffectRuntime.forSystem(system);
        this.pipeline = buildPipeline(middlewares);
    }

    /**
     * Creates a new StatefulHandlerActor with persistence and no middleware.
     */
    public StatefulHandlerActor(
            ActorSystem system,
            String actorId,
            StatefulHandler<E, State, Message> handler,
            State initialState,
            BatchedMessageJournal<Message> messageJournal,
            SnapshotStore<State> snapshotStore,
            BackpressureConfig backpressureConfig,
            ResizableMailboxConfig mailboxConfig,
            ThreadPoolFactory threadPoolFactory,
            MailboxProvider<Message> mailboxProvider) {
        this(system, actorId, handler, initialState, messageJournal, snapshotStore,
             backpressureConfig, mailboxConfig, threadPoolFactory, mailboxProvider, List.of());
    }

    /**
     * Creates a new StatefulHandlerActor with persistence and a middleware stack.
     *
     * @param middlewares ordered list of middlewares applied outermost-last
     */
    public StatefulHandlerActor(
            ActorSystem system,
            String actorId,
            StatefulHandler<E, State, Message> handler,
            State initialState,
            BatchedMessageJournal<Message> messageJournal,
            SnapshotStore<State> snapshotStore,
            BackpressureConfig backpressureConfig,
            ResizableMailboxConfig mailboxConfig,
            ThreadPoolFactory threadPoolFactory,
            MailboxProvider<Message> mailboxProvider,
            List<BehaviorMiddleware<E, State, Message>> middlewares) {
        super(system, actorId, initialState, messageJournal, snapshotStore,
              backpressureConfig, mailboxConfig, threadPoolFactory, mailboxProvider);
        this.handler = handler;
        this.rouxRuntime = CajunEffectRuntime.forSystem(system);
        this.pipeline = buildPipeline(middlewares);
    }

    // =========================================================================
    // Pipeline assembly
    // =========================================================================

    /**
     * Assembles the base behavior and wraps it with all middlewares.
     *
     * <p>The base behavior maps {@code handler.receive()} (which returns
     * {@code Effect<E, State>}) to {@code Effect<E, LoopStep.Continue<State>>}.
     * Middlewares are applied in list order, so the first middleware in the list
     * is the innermost wrapper and the last is the outermost.
     */
    private ActorBehavior<E, State, Message> buildPipeline(
            List<BehaviorMiddleware<E, State, Message>> middlewares) {
        // Base behavior: delegate to handler and wrap the resulting state in Continue
        ActorBehavior<E, State, Message> base =
            (msg, state, ctx) -> handler.receive(msg, state, ctx).map(LoopStep::continue_);

        // Apply middlewares in order (first = innermost, last = outermost)
        ActorBehavior<E, State, Message> current = base;
        for (BehaviorMiddleware<E, State, Message> mw : middlewares) {
            current = mw.wrap(current);
        }
        return current;
    }

    /**
     * Sets the capability handler used to resolve Roux capabilities when executing each
     * message's effect. When non-null, {@code processMessage} calls
     * {@code unsafeRunWithHandler} instead of {@code unsafeRun}.
     *
     * <p>Must be called before the actor is started (i.e., before {@link #start()}).
     *
     * @param capabilityHandler the handler to use; {@code null} disables capability injection
     */
    public void setCapabilityHandler(CapabilityHandler<Capability<?>> capabilityHandler) {
        this.capabilityHandler = capabilityHandler;
    }

    // =========================================================================
    // Core message processing
    // =========================================================================

    /**
     * Processes a message by executing the composed behavior pipeline and
     * interpreting the resulting {@link LoopStep}.
     *
     * <ul>
     *   <li>{@link LoopStep.Continue} — returns the new state; actor keeps running.</li>
     *   <li>{@link LoopStep.Stop}     — calls {@link #stop()} and returns the final state.</li>
     *   <li>{@link LoopStep.Restart}  — schedules a restart via {@link #scheduleRestart()}
     *                                   and returns the restart state.</li>
     * </ul>
     *
     * <p>If the effect fails (the error propagates past all middleware), the exception
     * is rethrown so {@link StatefulActor}'s error handling invokes {@link #onError}.
     */
    @Override
    protected State processMessage(State currentState, Message message) {
        ActorContext context = new StatefulActorContext(this);
        Effect<E, LoopStep<State>> stepEffect = pipeline.step(message, currentState, context);
        try {
            LoopStep<State> step = (capabilityHandler != null)
                    ? rouxRuntime.unsafeRunWithHandler(stepEffect, capabilityHandler)
                    : rouxRuntime.unsafeRun(stepEffect);
            return applyLoopStep(step);
        } catch (RuntimeException t) {
            // Rethrow as-is so onError() receives the original exception type
            throw t;
        } catch (Error t) {
            throw t;
        } catch (Throwable t) {
            // E is a checked exception — use sneaky throw to preserve the original type
            // so that handler.onError() implementations can inspect it without unwrapping
            throw sneakyThrow(t);
        }
    }

    /**
     * Rethrows {@code t} without wrapping, bypassing the checked-exception compiler check.
     * This preserves the original exception type so {@code handler.onError()} receives exactly
     * the exception that the Effect produced.
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    /**
     * Applies a {@link LoopStep} to the actor lifecycle and returns the new state.
     */
    private State applyLoopStep(LoopStep<State> step) {
        return switch (step) {
            case LoopStep.Continue<State> c -> c.state();
            case LoopStep.Stop<State>     s -> {
                stop();
                yield s.state();
            }
            case LoopStep.Restart<State>  r -> {
                scheduleRestart();
                yield r.state();
            }
        };
    }

    // =========================================================================
    // Lifecycle hooks
    // =========================================================================

    @Override
    protected void preStart() {
        super.preStart();
        ActorContext context = new StatefulActorContext(this);
        handler.preStart(getState(), context);
    }

    @Override
    protected void postStop() {
        ActorContext context = new StatefulActorContext(this);
        handler.postStop(getState(), context);
        rouxRuntime.close();
        super.postStop();
    }

    @Override
    protected boolean onError(Message message, Throwable exception) {
        ActorContext context = new StatefulActorContext(this);
        return handler.onError(message, getState(), exception, context);
    }

    // =========================================================================
    // Inner class: ActorContext backed by this StatefulActor
    // =========================================================================

    /**
     * Specialized ActorContext for StatefulActor that retrieves sender from
     * the asyncSenderContext ThreadLocal instead of the parent Actor's senderContext.
     */
    private static class StatefulActorContext implements ActorContext {
        private final StatefulActor<?, ?> statefulActor;

        StatefulActorContext(StatefulActor<?, ?> statefulActor) {
            this.statefulActor = statefulActor;
        }

        @Override
        public Pid self() {
            return statefulActor.self();
        }

        @Override
        public String getActorId() {
            return statefulActor.getActorId();
        }

        @Override
        public <T> void tell(Pid target, T message) {
            statefulActor.getSystem().tell(target, message);
        }

        @Override
        public <T> void reply(ReplyingMessage request, T response) {
            tell(request.replyTo(), response);
        }

        @Override
        public <T> void tellSelf(T message, long delay, TimeUnit timeUnit) {
            statefulActor.getSystem().tell(statefulActor.self(), message, delay, timeUnit);
        }

        @Override
        public <T> void tellSelf(T message) {
            statefulActor.getSystem().tell(statefulActor.self(), message);
        }

        @Override
        public <Message> ActorBuilder<Message> childBuilder(Class<? extends Handler<Message>> handlerClass) {
            ActorSystem system = statefulActor.getSystem();
            if (!Handler.class.isAssignableFrom(handlerClass)) {
                throw new IllegalArgumentException("Only Handler-based actors can be created as children. Use Handler or StatefulHandler interface.");
            }
            return system.actorOf(handlerClass)
                    .withParent(statefulActor);
        }

        @Override
        public <T> Pid createChild(Class<?> handlerClass, String childId) {
            if (Handler.class.isAssignableFrom(handlerClass)) {
                @SuppressWarnings("unchecked")
                Class<? extends Handler<Object>> handlerType =
                    (Class<? extends Handler<Object>>) handlerClass;
                return childBuilder(handlerType)
                        .withId(childId)
                        .spawn();
            } else {
                throw new IllegalArgumentException("Only Handler-based actors can be created as children. Use Handler or StatefulHandler interface.");
            }
        }

        @Override
        public <T> Pid createChild(Class<?> handlerClass) {
            if (Handler.class.isAssignableFrom(handlerClass)) {
                @SuppressWarnings("unchecked")
                Class<? extends Handler<Object>> handlerType =
                    (Class<? extends Handler<Object>>) handlerClass;
                return childBuilder(handlerType).spawn();
            } else {
                throw new IllegalArgumentException("Only Handler-based actors can be created as children. Use Handler or StatefulHandler interface.");
            }
        }

        @Override
        public Pid getParent() {
            Actor<?> parent = statefulActor.getParent();
            return parent != null ? parent.self() : null;
        }

        @Override
        public Map<String, Pid> getChildren() {
            Map<String, Pid> result = new java.util.HashMap<>();
            for (Map.Entry<String, Actor<?>> entry : statefulActor.getChildren().entrySet()) {
                result.put(entry.getKey(), entry.getValue().self());
            }
            return result;
        }

        @Override
        public ActorSystem getSystem() {
            return statefulActor.getSystem();
        }

        @Override
        public void stop() {
            statefulActor.stop();
        }

        @Override
        public Optional<Pid> getSender() {
            String senderActorId = statefulActor.getAsyncSenderContext();
            if (senderActorId == null) {
                return Optional.empty();
            }
            ActorSystem system = statefulActor.getSystem();
            if (system == null) {
                statefulActor.getLogger().error("ActorSystem is null when creating sender Pid for actor {}", senderActorId);
                return Optional.empty();
            }
            return Optional.of(new Pid(senderActorId, system));
        }

        @Override
        public <T> void forward(Pid target, T message) {
            String senderActorId = statefulActor.getAsyncSenderContext();
            if (senderActorId != null) {
                Object wrapped = ActorSystem.wrapWithSender(message, senderActorId);
                statefulActor.getSystem().routeMessage(target.actorId(), wrapped);
            } else {
                statefulActor.getSystem().routeMessage(target.actorId(), message);
            }
        }

        @Override
        public Logger getLogger() {
            return statefulActor.getLogger();
        }
    }
}
