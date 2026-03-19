package com.cajunsystems.direct.internal;

import com.cajunsystems.ActorContext;
import com.cajunsystems.direct.DirectContext;
import com.cajunsystems.direct.DirectResult;
import com.cajunsystems.direct.StatefulDirectHandler;
import com.cajunsystems.handler.Handler;

/**
 * Internal adapter that bridges a {@link StatefulDirectHandler} to the standard
 * {@link Handler} interface.
 *
 * <p>State is held as a mutable field within the adapter. This is safe because
 * the actor framework guarantees that messages are processed sequentially — there
 * is no concurrent access to {@code state}.
 *
 * <p>Unlike {@link com.cajunsystems.handler.StatefulHandler}, this adapter does
 * <em>not</em> use {@link com.cajunsystems.StatefulActor} and therefore does not
 * require messages to implement {@link java.io.Serializable} or configure a
 * persistence provider.
 *
 * @param <State>   The state type
 * @param <Message> The message type
 * @param <Reply>   The reply type
 */
public class StatefulDirectHandlerAdapter<State, Message, Reply>
        implements Handler<Message> {

    private final StatefulDirectHandler<State, Message, Reply> directHandler;
    // Mutable state; safe because actor messages are processed sequentially
    private State state;

    public StatefulDirectHandlerAdapter(
            StatefulDirectHandler<State, Message, Reply> directHandler,
            State initialState) {
        this.directHandler = directHandler;
        this.state = initialState;
    }

    @Override
    public void receive(Message message, ActorContext context) {
        DirectContext directContext = new DirectContextImpl(context);
        DirectWireReply<Reply> wireReply;

        try {
            DirectResult<State, Reply> result = directHandler.handle(message, state, directContext);
            state = result.newState();
            wireReply = new DirectWireReply.Success<>(result.reply());
        } catch (Exception e) {
            wireReply = handleError(message, e, directContext);
        }

        sendReply(wireReply, context);
    }

    @Override
    public void preStart(ActorContext context) {
        state = directHandler.preStart(state, new DirectContextImpl(context));
    }

    @Override
    public void postStop(ActorContext context) {
        directHandler.postStop(state, new DirectContextImpl(context));
    }

    @Override
    public boolean onError(Message message, Throwable exception, ActorContext context) {
        DirectContext directContext = new DirectContextImpl(context);
        DirectWireReply<Reply> wireReply = handleError(message, exception, directContext);
        sendReply(wireReply, context);
        return false;
    }

    private DirectWireReply<Reply> handleError(
            Message message, Throwable exception, DirectContext context) {
        try {
            DirectResult<State, Reply> result =
                    directHandler.onError(message, state, exception, context);
            state = result.newState();
            return new DirectWireReply.Success<>(result.reply());
        } catch (Exception e2) {
            return new DirectWireReply.Failure<>(e2.getMessage(), e2);
        }
    }

    private void sendReply(DirectWireReply<Reply> wireReply, ActorContext context) {
        context.getSender().ifPresent(sender -> context.tell(sender, wireReply));
    }
}
