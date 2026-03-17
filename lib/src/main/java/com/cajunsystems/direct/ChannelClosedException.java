package com.cajunsystems.direct;

/**
 * Exception thrown when attempting to send to or receive from a closed channel.
 */
public class ChannelClosedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Object channel;
    private final boolean sendSide;

    /**
     * Creates a new ChannelClosedException.
     *
     * @param message the detail message
     * @param channel the channel that was closed
     * @param sendSide true if the exception occurred on the send side, false for receive side
     */
    public ChannelClosedException(String message, Object channel, boolean sendSide) {
        super(message);
        this.channel = channel;
        this.sendSide = sendSide;
    }

    /**
     * Creates a new ChannelClosedException with a cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     * @param channel the channel that was closed
     * @param sendSide true if the exception occurred on the send side, false for receive side
     */
    public ChannelClosedException(String message, Throwable cause, Object channel, boolean sendSide) {
        super(message, cause);
        this.channel = channel;
        this.sendSide = sendSide;
    }

    /**
     * Creates an exception for a failed send operation on a closed channel.
     *
     * @param channel the closed channel
     * @param <T> the channel element type
     * @return a new ChannelClosedException for the send side
     */
    public static <T> ChannelClosedException onSend(Channel<T> channel) {
        return new ChannelClosedException("Cannot send to a closed channel", channel, true);
    }

    /**
     * Creates an exception for a failed send operation on a closed channel with a cause.
     *
     * @param channel the closed channel
     * @param cause the underlying cause
     * @param <T> the channel element type
     * @return a new ChannelClosedException for the send side
     */
    public static <T> ChannelClosedException onSend(Channel<T> channel, Throwable cause) {
        return new ChannelClosedException("Cannot send to a closed channel", cause, channel, true);
    }

    /**
     * Creates an exception for a failed receive operation on a closed channel.
     *
     * @param channel the closed channel
     * @param <T> the channel element type
     * @return a new ChannelClosedException for the receive side
     */
    public static <T> ChannelClosedException onReceive(Channel<T> channel) {
        return new ChannelClosedException("Cannot receive from a closed channel", channel, false);
    }

    /**
     * Creates an exception for a failed receive operation on a closed channel with a cause.
     *
     * @param channel the closed channel
     * @param cause the underlying cause
     * @param <T> the channel element type
     * @return a new ChannelClosedException for the receive side
     */
    public static <T> ChannelClosedException onReceive(Channel<T> channel, Throwable cause) {
        return new ChannelClosedException("Cannot receive from a closed channel", cause, channel, false);
    }

    /**
     * Returns the channel that was closed.
     *
     * @return the closed channel, or null if not available
     */
    public Object getChannel() {
        return channel;
    }

    /**
     * Returns whether this exception occurred on the send side.
     *
     * @return true if the exception occurred during a send operation
     */
    public boolean isSendSide() {
        return sendSide;
    }

    /**
     * Returns whether this exception occurred on the receive side.
     *
     * @return true if the exception occurred during a receive operation
     */
    public boolean isReceiveSide() {
        return !sendSide;
    }
}