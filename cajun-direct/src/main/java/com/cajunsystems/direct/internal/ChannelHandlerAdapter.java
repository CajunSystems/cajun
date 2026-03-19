package com.cajunsystems.direct.internal;

import com.cajunsystems.ActorContext;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Internal {@link Handler} adapter that bridges the Cajun actor mailbox to an
 * {@link ActorChannelImpl}.
 *
 * <p>For each incoming message:
 * <ol>
 *   <li>A {@link CompletableFuture} is created to carry the reply.</li>
 *   <li>A {@code whenComplete} callback is registered on the future: it sends the reply (or
 *       failure) back to the ask-pattern sender.</li>
 *   <li>The message and future are delivered to the channel queue.</li>
 *   <li>{@code receive} blocks on {@code future.get()} — this keeps the actor's message
 *       processing loop paused until the user's generator loop produces a reply, preserving
 *       sequential processing semantics. Blocking is safe because the Cajun framework runs
 *       actors on Java 21 virtual threads.</li>
 * </ol>
 *
 * @param <Message> The message type
 * @param <Reply>   The reply type
 */
public final class ChannelHandlerAdapter<Message, Reply> implements Handler<Message> {

    private final ActorChannelImpl<Message, Reply> channel;

    public ChannelHandlerAdapter(ActorChannelImpl<Message, Reply> channel) {
        this.channel = channel;
    }

    @Override
    public void receive(Message message, ActorContext context) {
        CompletableFuture<Reply> replyFuture = new CompletableFuture<>();

        // Capture the sender NOW, while we are still on the actor's processing thread.
        // getSender() reads from a ThreadLocal — it returns null on any other thread.
        Optional<Pid> sender = context.getSender();

        // When the user loop completes the future, send the reply (or failure) back.
        // The whenComplete fires on the loop's virtual thread, so we use the captured sender.
        replyFuture.whenComplete((reply, error) -> {
            DirectWireReply<Reply> wireReply = (error == null)
                    ? new DirectWireReply.Success<>(reply)
                    : new DirectWireReply.Failure<>(error.getMessage(), error);
            sender.ifPresent(s -> context.tell(s, wireReply));
        });

        channel.deliver(message, replyFuture);

        // Block until the user's loop thread processes the message. This preserves the
        // sequential message processing contract (no next message until this one is handled).
        // Blocking is safe because Cajun runs actors on virtual threads.
        try {
            replyFuture.get();
        } catch (Exception ignored) {
            // Errors are already propagated via the whenComplete callback above.
        }
    }

    @Override
    public void postStop(ActorContext context) {
        channel.close();
    }
}
