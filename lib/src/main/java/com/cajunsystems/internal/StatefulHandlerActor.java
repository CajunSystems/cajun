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
import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.EffectRuntime;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Internal implementation of a StatefulActor that delegates to a {@link StatefulHandler}.
 *
 * <p>When a message arrives, {@link #processMessage} calls
 * {@link StatefulHandler#receive handler.receive()} which returns a Roux
 * {@link Effect}{@code <E, State>} describing the state transition.  The effect is
 * executed synchronously via {@link EffectRuntime#unsafeRun(Effect)} on the actor's
 * current virtual thread; the resulting value becomes the new actor state.
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
    private final EffectRuntime rouxRuntime;

    /**
     * Creates a new StatefulHandlerActor with the specified handler, initial state, thread pool
     * factory, and mailbox provider.  Uses default persistence.
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
        super(system, actorId, initialState, backpressureConfig, mailboxConfig, threadPoolFactory, mailboxProvider);
        this.handler = handler;
        this.rouxRuntime = CajunEffectRuntime.forSystem(system);
    }

    /**
     * Creates a new StatefulHandlerActor with the specified handler, initial state, persistence
     * components, thread pool factory, and mailbox provider.
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
        super(system, actorId, initialState, messageJournal, snapshotStore, backpressureConfig, mailboxConfig, threadPoolFactory, mailboxProvider);
        this.handler = handler;
        this.rouxRuntime = CajunEffectRuntime.forSystem(system);
    }

    /**
     * Processes a message by:
     * <ol>
     *   <li>Calling {@code handler.receive()} to obtain a Roux {@link Effect}</li>
     *   <li>Running the effect synchronously via {@link EffectRuntime#unsafeRun(Effect)}</li>
     *   <li>Returning the resulting state value</li>
     * </ol>
     *
     * <p>If the effect fails, the exception is thrown and propagated to
     * {@link StatefulActor}'s error handling machinery, which in turn calls
     * {@link #onError(Object, Throwable)}.
     */
    @Override
    protected State processMessage(State currentState, Message message) {
        ActorContext context = new StatefulActorContext(this);
        Effect<E, State> effect = handler.receive(message, currentState, context);
        try {
            return rouxRuntime.unsafeRun(effect);
        } catch (Throwable t) {
            // Rethrow so StatefulActor's error handling invokes onError()
            throw new RuntimeException("Effect execution failed for actor " + getActorId(), t);
        }
    }

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
