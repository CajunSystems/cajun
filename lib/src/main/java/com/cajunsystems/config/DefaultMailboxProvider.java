package com.cajunsystems.config;

import com.cajunsystems.config.ThreadPoolFactory.WorkloadType;
import com.cajunsystems.mailbox.Mailbox;

/**
 * Backward compatibility wrapper for DefaultMailboxProvider.
 *
 * @deprecated Use {@link com.cajunsystems.mailbox.config.DefaultMailboxProvider} instead.
 *             This class will be removed in v0.3.0.
 */
@Deprecated(since = "0.2.0", forRemoval = true)
public class DefaultMailboxProvider implements MailboxProvider<Object> {

    private final com.cajunsystems.mailbox.config.DefaultMailboxProvider<Object> delegate =
            new com.cajunsystems.mailbox.config.DefaultMailboxProvider<>();

    @Override
    public Mailbox<Object> createMailbox(com.cajunsystems.config.MailboxConfig config,
                                         WorkloadType workloadTypeHint) {
        com.cajunsystems.mailbox.config.MailboxConfig moduleConfig = new com.cajunsystems.mailbox.config.MailboxConfig()
                .setInitialCapacity(config.getInitialCapacity())
                .setMaxCapacity(config.getMaxCapacity())
                .setResizeThreshold(config.getResizeThreshold())
                .setResizeFactor(config.getResizeFactor());

        return delegate.createMailbox(moduleConfig, workloadTypeHint);
    }
}
