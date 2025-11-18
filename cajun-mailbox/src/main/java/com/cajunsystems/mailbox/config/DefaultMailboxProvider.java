package com.cajunsystems.mailbox.config;

import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.config.ThreadPoolFactory.WorkloadType;
import com.cajunsystems.mailbox.Mailbox;
import com.cajunsystems.mailbox.LinkedMailbox;
import com.cajunsystems.mailbox.MpscMailbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default mailbox provider that creates high-performance mailboxes based on
 * workload hints and configuration.
 *
 * Performance characteristics:
 * - IO_BOUND: LinkedMailbox (good lock-free performance, larger capacity)
 * - CPU_BOUND: MpscMailbox (best performance for high-throughput CPU workloads)
 * - MIXED/Default: LinkedMailbox (good general-purpose performance)
 *
 * Note: ResizableBlockingQueue has been removed due to performance issues.
 */
public class DefaultMailboxProvider<M> implements MailboxProvider<M> {
    private static final Logger logger = LoggerFactory.getLogger(DefaultMailboxProvider.class);

    // Default capacities based on workload type
    private static final int IO_BOUND_DEFAULT_CAPACITY = 10000;
    private static final int CPU_BOUND_DEFAULT_CAPACITY = 1000;
    private static final int MPSC_INITIAL_CHUNK_SIZE = 128;

    public DefaultMailboxProvider() {
    }

    @Override
    public Mailbox<M> createMailbox(MailboxConfig config, WorkloadType workloadTypeHint) {
        MailboxConfig effectiveConfig = (config != null) ? config : new MailboxConfig();
        int initialCapacity = effectiveConfig.getInitialCapacity();
        int maxCapacity = effectiveConfig.getMaxCapacity();

        logger.debug("DefaultMailboxProvider creating mailbox - initialCapacity: {}, maxCapacity: {}, workloadHint: {}",
                     initialCapacity, maxCapacity, workloadTypeHint);

        // Handle deprecated ResizableMailboxConfig - log warning and use LinkedMailbox
        if (effectiveConfig instanceof ResizableMailboxConfig) {
            logger.warn("ResizableMailboxConfig is deprecated due to performance issues. " +
                       "Using LinkedMailbox instead with capacity: {}. " +
                       "Consider using MailboxConfig or MpscMailboxConfig for better performance.",
                       maxCapacity);
            return new LinkedMailbox<>(maxCapacity);
        }

        // Create mailbox based on workload hint
        if (workloadTypeHint != null) {
            switch (workloadTypeHint) {
                case IO_BOUND:
                    int ioCapacity = Math.max(maxCapacity, IO_BOUND_DEFAULT_CAPACITY);
                    logger.info("Creating LinkedMailbox for IO_BOUND workload with capacity: {}", ioCapacity);
                    return new LinkedMailbox<>(ioCapacity);

                case CPU_BOUND:
                    // For CPU-bound workloads, use MPSC for best performance
                    int cpuCapacity = Math.min(maxCapacity, CPU_BOUND_DEFAULT_CAPACITY);
                    logger.info("Creating MpscMailbox for CPU_BOUND workload with initial capacity: {}",
                               MPSC_INITIAL_CHUNK_SIZE);
                    return new MpscMailbox<>(MPSC_INITIAL_CHUNK_SIZE);

                case MIXED:
                default:
                    logger.info("Creating LinkedMailbox for MIXED/default workload with capacity: {}", maxCapacity);
                    return new LinkedMailbox<>(maxCapacity);
            }
        }

        // Default: LinkedMailbox with specified capacity
        logger.info("Creating default LinkedMailbox with capacity: {}", maxCapacity);
        return new LinkedMailbox<>(maxCapacity);
    }
}
