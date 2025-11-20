package com.cajunsystems.mailbox.config;

import com.cajunsystems.mailbox.LinkedMailbox;
import com.cajunsystems.mailbox.Mailbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mailbox creation strategy for mixed workloads (both CPU and I/O).
 * Uses LinkedMailbox as a good general-purpose option with balanced performance.
 *
 * @param <M> The message type
 */
public class MixedWorkloadStrategy<M> implements MailboxCreationStrategy<M> {
    private static final Logger logger = LoggerFactory.getLogger(MixedWorkloadStrategy.class);

    @Override
    public Mailbox<M> createMailbox(MailboxConfig config) {
        int capacity = config.getMaxCapacity();
        logger.debug("Creating LinkedMailbox for MIXED workload with capacity: {}", capacity);
        return new LinkedMailbox<>(capacity);
    }
}
