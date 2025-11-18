package com.cajunsystems.config;

/**
 * Backward compatibility wrapper for DefaultMailboxProvider.
 *
 * @deprecated Use {@link com.cajunsystems.mailbox.config.DefaultMailboxProvider} instead.
 *             This class will be removed in v0.3.0.
 * @param <M> The type of messages
 */
@Deprecated(since = "0.2.0", forRemoval = true)
public class DefaultMailboxProvider<M> extends com.cajunsystems.mailbox.config.DefaultMailboxProvider<M> {
}
