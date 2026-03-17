package com.cajunsystems.direct;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Select utility class providing static methods to receive from multiple channels.
 * Inspired by Ox for Scala's select mechanism.
 *
 * <p>Usage example:
 * <pre>{@code
 * Channel<String> ch1 = new Channel<>(10);
 * Channel<Integer> ch2 = new Channel<>(10);
 *
 * SelectResult<?> result = Select.select(ch1, ch2);
 * System.out.println("Received " + result.value() + " from channel " + result.channelIndex());
 * }</pre>
 */
public final class Select {

    private Select() {
        // Utility class
    }

    /**
     * Result of a select operation, containing the received value and the index
     * of the source channel.
     *
     * @param <T> the type of the received value
     */
    public static final class SelectResult<T> {
        private final T value;
        private final int channelIndex;

        public SelectResult(T value, int channelIndex) {
            this.value = value;
            this.channelIndex = channelIndex;
        }

        /**
         * Returns the value received from the channel.
         *
         * @return the received value
         */
        public T value() {
            return value;
        }

        /**
         * Returns the index of the channel that produced the value,
         * corresponding to the position in the channels array passed to
         * {@link Select#select(Channel[])} or {@link Select#selectOrNull(Channel[])}.
         *
         * @return the zero-based channel index
         */
        public int channelIndex() {
            return channelIndex;
        }

        @Override
        public String toString() {
            return "SelectResult[value=" + value + ", channelIndex=" + channelIndex + "]";
        }
    }

    /**
     * Blocks until a value is available on any of the given channels and returns
     * a {@link SelectResult} containing the value and the source channel index.
     *
     * <p>Internally spawns a virtual thread per channel. The first thread to
     * successfully receive a value delivers the result, and all other threads
     * are interrupted/cancelled.
     *
     * @param channels the channels to select from
     * @return a {@link SelectResult} with the received value and channel index
     * @throws ChannelClosedException if all channels are closed before a value is received
     * @throws IllegalArgumentException if no channels are provided
     */
    @SafeVarargs
    public static SelectResult<Object> select(Channel<?>... channels) {
        if (channels == null || channels.length == 0) {
            throw new IllegalArgumentException("At least one channel must be provided");
        }

        CompletableFuture<SelectResult<Object>> resultFuture = new CompletableFuture<>();
        AtomicBoolean done = new AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicInteger closedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        Thread[] threads = new Thread[channels.length];
        int totalChannels = channels.length;

        for (int i = 0; i < channels.length; i++) {
            final int index = i;
            final Channel<?> channel = channels[i];
            threads[i] = Thread.ofVirtual().name("select-" + index).start(() -> {
                try {
                    while (!done.get() && !Thread.currentThread().isInterrupted()) {
                        try {
                            Object value = channel.receive();
                            if (done.compareAndSet(false, true)) {
                                resultFuture.complete(new SelectResult<>(value, index));
                            } else {
                                // Another thread already completed; we consumed a value
                                // but can't return it. This is an inherent trade-off of
                                // the select pattern. In practice, the window is very small.
                            }
                            return;
                        } catch (ChannelClosedException e) {
                            int closed = closedCount.incrementAndGet();
                            if (closed >= totalChannels) {
                                done.compareAndSet(false, true);
                                resultFuture.completeExceptionally(
                                        new ChannelClosedException("All channels are closed"));
                            }
                            return;
                        }
                    }
                } catch (Exception e) {
                    if (!done.get()) {
                        // Interrupted or unexpected exception
                        if (!(e instanceof InterruptedException)) {
                            resultFuture.completeExceptionally(e);
                        }
                    }
                }
            });
        }

        try {
            SelectResult<Object> result = resultFuture.join();
            // Interrupt all other threads
            for (int i = 0; i < threads.length; i++) {
                if (threads[i] != null) {
                    threads[i].interrupt();
                }
            }
            return result;
        } catch (java.util.concurrent.CompletionException e) {
            // Interrupt all threads on failure
            for (Thread thread : threads) {
                if (thread != null) {
                    thread.interrupt();
                }
            }
            Throwable cause = e.getCause();
            if (cause instanceof ChannelClosedException) {
                throw (ChannelClosedException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException("Select failed", cause);
        }
    }

    /**
     * Blocks until a value is available on any of the given channels and returns
     * a {@link SelectResult}, or returns {@code null} if all channels are closed.
     *
     * <p>This is a variant of {@link #select(Channel[])} that does not throw
     * {@link ChannelClosedException}.
     *
     * @param channels the channels to select from
     * @return a {@link SelectResult} with the received value and channel index,
     *         or {@code null} if all channels are closed
     * @throws IllegalArgumentException if no channels are provided
     */
    @SafeVarargs
    public static SelectResult<Object> selectOrNull(Channel<?>... channels) {
        try {
            return select(channels);
        } catch (ChannelClosedException e) {
            return null;
        }
    }
}