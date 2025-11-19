package com.cajunsystems.mailbox.config;

import com.cajunsystems.mailbox.Mailbox;

/**
 * Strategy interface for creating mailboxes based on configuration and workload characteristics.
 * This allows different mailbox creation strategies to be plugged in without modifying
 * the core mailbox provider logic.
 *
 * @param <M> The message type
 */
@FunctionalInterface
public interface MailboxCreationStrategy<M> {

    /**
     * Creates a mailbox according to this strategy.
     *
     * @param config The mailbox configuration
     * @return A new mailbox instance
     */
    Mailbox<M> createMailbox(MailboxConfig config);
}
