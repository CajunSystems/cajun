package com.cajunsystems.config;

import com.cajunsystems.mailbox.Mailbox;
import com.cajunsystems.config.ThreadPoolFactory.WorkloadType;

/**
 * An interface for providing actor mailboxes.
 * Implementations of this interface can define strategies for selecting
 * and configuring mailboxes based on configuration and workload hints.
 *
 * @param <M> The type of messages the mailbox will hold.
 */
public interface MailboxProvider<M> {

    /**
     * Creates a mailbox based on the provided configuration
     * and workload type hint.
     *
     * @param config The mailbox configuration, potentially including initial/max capacity
     *               and specific types like {@link ResizableMailboxConfig}.
     * @param workloadTypeHint A hint about the expected workload (e.g., IO_BOUND, CPU_BOUND),
     *                         derived from the {@link ThreadPoolFactory}.
     *                         This can be null if no hint is available.
     * @return A {@link Mailbox} instance suitable for an actor's mailbox.
     */
    Mailbox<M> createMailbox(MailboxConfig config, WorkloadType workloadTypeHint);
}
