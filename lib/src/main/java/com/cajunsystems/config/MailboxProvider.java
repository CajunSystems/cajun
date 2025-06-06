package com.cajunsystems.config;

import com.cajunsystems.config.ThreadPoolFactory.WorkloadType;
import java.util.concurrent.BlockingQueue;

/**
 * An interface for providing actor mailboxes (BlockingQueue implementations).
 * Implementations of this interface can define strategies for selecting
 * and configuring mailboxes based on configuration and workload hints.
 *
 * @param <M> The type of messages the mailbox will hold.
 */
public interface MailboxProvider<M> {

    /**
     * Creates a mailbox (BlockingQueue) based on the provided configuration
     * and workload type hint.
     *
     * @param config The mailbox configuration, potentially including initial/max capacity
     *               and specific types like {@link ResizableMailboxConfig}.
     * @param workloadTypeHint A hint about the expected workload (e.g., IO_BOUND, CPU_BOUND),
     *                         derived from the {@link ThreadPoolFactory}.
     *                         This can be null if no hint is available.
     * @return A {@link BlockingQueue} instance suitable for an actor's mailbox.
     */
    BlockingQueue<M> createMailbox(MailboxConfig config, WorkloadType workloadTypeHint);
}
