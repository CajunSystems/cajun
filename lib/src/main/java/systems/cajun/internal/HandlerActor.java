package systems.cajun.internal;

import systems.cajun.Actor;
import systems.cajun.ActorContext;
import systems.cajun.ActorContextImpl;
import systems.cajun.ActorSystem;
import systems.cajun.config.BackpressureConfig;
import systems.cajun.config.ResizableMailboxConfig;
import systems.cajun.handler.Handler;

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
        super(system, actorId, backpressureConfig, mailboxConfig);
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
