package com.cajunsystems.internal;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorContextImpl;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.StatefulActor;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.config.MailboxProvider;
import com.cajunsystems.config.ResizableMailboxConfig;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.SnapshotStore;

/**
 * Internal implementation of a StatefulActor that delegates to a StatefulHandler.
 * This class is not meant to be used directly by users.
 *
 * @param <State> The type of the actor's state
 * @param <Message> The type of messages this actor processes
 */
public class StatefulHandlerActor<State, Message> extends StatefulActor<State, Message> {
    
    private final StatefulHandler<State, Message> handler;
    private final ActorContext context;
    
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
        this.context = new ActorContextImpl(this);
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
        this.context = new ActorContextImpl(this);
    }
    
    @Override
    protected State processMessage(State currentState, Message message) {
        return handler.receive(message, currentState, context);
    }
    
    @Override
    protected void preStart() {
        super.preStart();
        handler.preStart(getState(), context);
    }
    
    @Override
    protected void postStop() {
        handler.postStop(getState(), context);
        super.postStop();
    }
    
    @Override
    protected void handleException(Message message, Throwable exception) {
        boolean handled = handler.onError(message, getState(), exception, context);
        if (!handled) {
            super.handleException(message, exception);
        }
    }
}
