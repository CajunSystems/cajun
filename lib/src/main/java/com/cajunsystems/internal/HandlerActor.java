package com.cajunsystems.internal;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorContextImpl;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.config.MailboxProvider;
import com.cajunsystems.config.ResizableMailboxConfig;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.handler.Handler;

/**
 * Internal implementation of an Actor that delegates to a Handler.
 * This class is not meant to be used directly by users.
 *
 * @param <Message> The type of messages this actor processes
 */
public class HandlerActor<Message> extends Actor<Message> {
    
    private final Handler<Message> handler;
    private final ActorContext context;
    
    /**
     * Creates a new HandlerActor with the specified handler.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param handler The handler to delegate to
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @param mailboxConfig The mailbox configuration
     */
    public HandlerActor(
            ActorSystem system,
            String actorId,
            Handler<Message> handler,
            BackpressureConfig backpressureConfig,
            ResizableMailboxConfig mailboxConfig) {
        super(system, 
              actorId, 
              backpressureConfig, 
              mailboxConfig, 
              system.getThreadPoolFactory(), 
              system.getMailboxProvider());
        this.handler = handler;
        this.context = new ActorContextImpl(this);
    }
    
    /**
     * Creates a new HandlerActor with the specified handler and thread pool factory.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param handler The handler to delegate to
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @param mailboxConfig The mailbox configuration
     * @param threadPoolFactory The thread pool factory, or null to use default
     * @param mailboxProvider The mailbox provider
     */
    public HandlerActor(
            ActorSystem system,
            String actorId,
            Handler<Message> handler,
            BackpressureConfig backpressureConfig,
            ResizableMailboxConfig mailboxConfig,
            ThreadPoolFactory threadPoolFactory,
            MailboxProvider<Message> mailboxProvider) {
        super(system, actorId, backpressureConfig, mailboxConfig, threadPoolFactory, mailboxProvider);
        this.handler = handler;
        this.context = new ActorContextImpl(this);
    }
    
    @Override
    protected void receive(Message message) {
        handler.receive(message, context);
    }
    
    @Override
    protected void preStart() {
        super.preStart();
        handler.preStart(context);
    }
    
    @Override
    protected void postStop() {
        handler.postStop(context);
        super.postStop();
    }
    
    @Override
    protected void handleException(Message message, Throwable exception) {
        boolean handled = handler.onError(message, exception, context);
        if (!handled) {
            super.handleException(message, exception);
        }
    }
}
