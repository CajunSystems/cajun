package com.cajunsystems.direct.internal;

import com.cajunsystems.direct.ActorChannel;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;

/**
 * Internal implementation of {@link ActorChannel}.
 *
 * <p>Messages are delivered from the actor framework via {@link #deliver} and consumed by the
 * user's loop via {@link #handle}. A sentinel value ({@link #CLOSED}) is used to unblock
 * {@link #handle} when the channel is closed.
 *
 * @param <Message> The message type
 * @param <Reply>   The reply type
 */
public final class ActorChannelImpl<Message, Reply> implements ActorChannel<Message, Reply> {

    /** Marker record placed in the queue when the channel is closed. */
    private static final Object CLOSED = new Object();

    /** Envelope pairing a message with the future that carries the reply back to the sender. */
    record Envelope<M, R>(M message, CompletableFuture<R> replyFuture) {}

    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private volatile boolean open = true;

    /**
     * Called by {@link ChannelHandlerAdapter} when a message arrives from the actor mailbox.
     *
     * @param message     The incoming message
     * @param replyFuture Future to complete once the user loop produces a reply
     */
    public void deliver(Message message, CompletableFuture<Reply> replyFuture) {
        queue.offer(new Envelope<>(message, replyFuture));
    }

    /**
     * Called by {@link ChannelHandlerAdapter#postStop} to signal that the actor has been
     * stopped and no further messages will be delivered.
     */
    public void close() {
        open = false;
        queue.offer(CLOSED);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean handle(Function<Message, Reply> fn) {
        if (!open && queue.isEmpty()) {
            return false;
        }
        try {
            Object item = queue.take();
            if (item == CLOSED) {
                return false;
            }
            Envelope<Message, Reply> envelope = (Envelope<Message, Reply>) item;
            try {
                Reply reply = fn.apply(envelope.message());
                envelope.replyFuture().complete(reply);
            } catch (Exception e) {
                envelope.replyFuture().completeExceptionally(e);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean isOpen() {
        return open || !queue.isEmpty();
    }
}
