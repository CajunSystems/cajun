package com.cajunsystems.internal;

import com.cajunsystems.*;
import com.cajunsystems.builder.ActorBuilder;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.mailbox.config.MailboxProvider;
import com.cajunsystems.config.ResizableMailboxConfig;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.SnapshotStore;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Internal implementation of a StatefulActor that delegates to a StatefulHandler.
 * This class is not meant to be used directly by users.
 *
 * @param <State> The type of the actor's state
 * @param <Message> The type of messages this actor processes
 */
public class StatefulHandlerActor<State, Message> extends StatefulActor<State, Message> {
    
    private final StatefulHandler<State, Message> handler;
    // Note: context is created fresh for each message in processMessage() to capture correct sender

    /**
     * Creates a new StatefulHandlerActor with the specified handler, initial state, thread pool factory, and mailbox provider.
     * This constructor is for actors using default persistence.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param handler The handler to delegate to
     * @param initialState The initial state
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @param mailboxConfig The mailbox configuration
     * @param threadPoolFactory The thread pool factory
     * @param mailboxProvider The mailbox provider
     */
    public StatefulHandlerActor(
            ActorSystem system,
            String actorId,
            StatefulHandler<State, Message> handler,
            State initialState,
            BackpressureConfig backpressureConfig,
            ResizableMailboxConfig mailboxConfig,
            ThreadPoolFactory threadPoolFactory,
            MailboxProvider<Message> mailboxProvider) {
        super(system, actorId, initialState, backpressureConfig, mailboxConfig, threadPoolFactory, mailboxProvider);
        this.handler = handler;
    }
    
    /**
     * Creates a new StatefulHandlerActor with the specified handler, initial state, persistence components, thread pool factory, and mailbox provider.
     * This constructor is for actors using custom persistence.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param handler The handler to delegate to
     * @param initialState The initial state
     * @param messageJournal The message journal to use
     * @param snapshotStore The snapshot store to use
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @param mailboxConfig The mailbox configuration
     * @param threadPoolFactory The thread pool factory
     * @param mailboxProvider The mailbox provider
     */
    public StatefulHandlerActor(
            ActorSystem system,
            String actorId,
            StatefulHandler<State, Message> handler,
            State initialState,
            BatchedMessageJournal<Message> messageJournal,
            SnapshotStore<State> snapshotStore,
            BackpressureConfig backpressureConfig,
            ResizableMailboxConfig mailboxConfig,
            ThreadPoolFactory threadPoolFactory,
            MailboxProvider<Message> mailboxProvider) {
        super(system, actorId, initialState, messageJournal, snapshotStore, backpressureConfig, mailboxConfig, threadPoolFactory, mailboxProvider);
        this.handler = handler;
    }
    
    @Override
    protected State processMessage(State currentState, Message message) {
        // Create context - sender will be retrieved from asyncSenderContext ThreadLocal
        ActorContext context = new StatefulActorContext(this);
        return handler.receive(message, currentState, context);
    }
    
    @Override
    protected void preStart() {
        super.preStart();
        // No sender context during preStart
        ActorContext context = new StatefulActorContext(this);
        handler.preStart(getState(), context);
    }
    
    @Override
    protected void postStop() {
        // No sender context during postStop
        ActorContext context = new StatefulActorContext(this);
        handler.postStop(getState(), context);
        super.postStop();
    }
    
    @Override
    protected boolean onError(Message message, Throwable exception) {
        // Create context - sender will be retrieved from asyncSenderContext ThreadLocal
        ActorContext context = new StatefulActorContext(this);
        // Delegate to handler to get shouldReprocess flag
        return handler.onError(message, getState(), exception, context);
    }

    /**
     * Specialized ActorContext for StatefulActor that retrieves sender from
     * the asyncSenderContext ThreadLocal instead of the parent Actor's senderContext.
     * This ensures sender context is preserved across async boundaries.
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
            // Get sender from StatefulActor's asyncSenderContext ThreadLocal
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
            // Forward with captured sender context from asyncSenderContext
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
