package com.cajunsystems.mailbox.config;

import com.cajunsystems.mailbox.LinkedMailbox;
import com.cajunsystems.mailbox.Mailbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mailbox creation strategy optimized for I/O-bound workloads.
 * Uses LinkedMailbox with larger capacity for better throughput.
 *
 * @param <M> The message type
 */
public class IoOptimizedStrategy<M> implements MailboxCreationStrategy<M> {
    private static final Logger logger = LoggerFactory.getLogger(IoOptimizedStrategy.class);
    private static final int DEFAULT_CAPACITY = 10000;

    @Override
    public Mailbox<M> createMailbox(MailboxConfig config) {
        int capacity = Math.max(config.getMaxCapacity(), DEFAULT_CAPACITY);
        logger.debug("Creating LinkedMailbox for IO_BOUND workload with capacity: {}", capacity);
        return new LinkedMailbox<>(capacity);
    }
}
