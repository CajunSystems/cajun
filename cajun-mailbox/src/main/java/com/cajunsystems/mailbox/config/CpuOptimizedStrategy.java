package com.cajunsystems.mailbox.config;

import com.cajunsystems.mailbox.Mailbox;
import com.cajunsystems.mailbox.MpscMailbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mailbox creation strategy optimized for CPU-bound workloads.
 * Uses MpscMailbox for best lock-free performance with high-throughput CPU workloads.
 *
 * @param <M> The message type
 */
public class CpuOptimizedStrategy<M> implements MailboxCreationStrategy<M> {
    private static final Logger logger = LoggerFactory.getLogger(CpuOptimizedStrategy.class);
    private static final int INITIAL_CHUNK_SIZE = 128;

    @Override
    public Mailbox<M> createMailbox(MailboxConfig config) {
        logger.debug("Creating MpscMailbox for CPU_BOUND workload with initial chunk size: {}",
                    INITIAL_CHUNK_SIZE);
        return new MpscMailbox<>(INITIAL_CHUNK_SIZE);
    }
}
