package com.cajunsystems.direct.internal;

import com.cajunsystems.ActorContext;
import com.cajunsystems.direct.DirectActorException;
import com.cajunsystems.direct.DirectContext;
import com.cajunsystems.direct.DirectHandler;
import com.cajunsystems.handler.Handler;

/**
 * Internal adapter that bridges a {@link DirectHandler} to the standard {@link Handler} interface.
 *
 * <p>This adapter:
 * <ol>
 *   <li>Calls {@link DirectHandler#handle} to get the reply value.</li>
 *   <li>Wraps the reply (or any thrown exception) in a {@link DirectWireReply}.</li>
 *   <li>Sends the wire reply to the ask-pattern sender (if present).</li>
 * </ol>
 *
 * <p>When the message arrives via fire-and-forget ({@code tell}), there is no sender,
 * so the reply is computed but discarded.
 *
 * @param <Message> The message type
 * @param <Reply>   The reply type
 */
public class DirectHandlerAdapter<Message, Reply> implements Handler<Message> {

    private final DirectHandler<Message, Reply> directHandler;

    public DirectHandlerAdapter(DirectHandler<Message, Reply> directHandler) {
        this.directHandler = directHandler;
    }

    @Override
    public void receive(Message message, ActorContext context) {
        DirectContext directContext = new DirectContextImpl(context);
        DirectWireReply<Reply> wireReply;

        try {
            Reply reply = directHandler.handle(message, directContext);
            wireReply = new DirectWireReply.Success<>(reply);
        } catch (Exception e) {
            wireReply = handleError(message, e, directContext);
        }

        sendReply(wireReply, context);
    }

    @Override
    public void preStart(ActorContext context) {
        directHandler.preStart(new DirectContextImpl(context));
    }

    @Override
    public void postStop(ActorContext context) {
        directHandler.postStop(new DirectContextImpl(context));
    }

    @Override
    public boolean onError(Message message, Throwable exception, ActorContext context) {
        // onError in the base Handler is called by the actor framework on unhandled exceptions
        // from receive(). Since we catch all exceptions in receive() ourselves, this path
        // is only reached if an unexpected error occurs in our own adapter code.
        DirectContext directContext = new DirectContextImpl(context);
        DirectWireReply<Reply> wireReply = handleError(message, exception, directContext);
        sendReply(wireReply, context);
        return false; // do not reprocess
    }

    private DirectWireReply<Reply> handleError(
            Message message, Throwable exception, DirectContext context) {
        try {
            Reply fallback = directHandler.onError(message, exception, context);
            return new DirectWireReply.Success<>(fallback);
        } catch (Exception e2) {
            return new DirectWireReply.Failure<>(e2.getMessage(), e2);
        }
    }

    private void sendReply(DirectWireReply<Reply> wireReply, ActorContext context) {
        context.getSender().ifPresent(sender -> context.tell(sender, wireReply));
    }
}
