package com.cajunsystems.mailbox.config;

import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.config.ThreadPoolFactory.WorkloadType;
import com.cajunsystems.mailbox.Mailbox;
import com.cajunsystems.mailbox.LinkedMailbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/**
 * Default mailbox provider that creates high-performance mailboxes based on
 * workload hints and configuration using the Strategy pattern.
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

    private final Map<WorkloadType, MailboxCreationStrategy<M>> strategies;
    private final MailboxCreationStrategy<M> defaultStrategy;

    public DefaultMailboxProvider() {
        this.strategies = new EnumMap<>(WorkloadType.class);
        this.strategies.put(WorkloadType.IO_BOUND, new IoOptimizedStrategy<>());
        this.strategies.put(WorkloadType.CPU_BOUND, new CpuOptimizedStrategy<>());
        this.strategies.put(WorkloadType.MIXED, new MixedWorkloadStrategy<>());
        this.defaultStrategy = new MixedWorkloadStrategy<>();
    }

    @Override
    public Mailbox<M> createMailbox(MailboxConfig config, WorkloadType workloadTypeHint) {
        MailboxConfig effectiveConfig = (config != null) ? config : new MailboxConfig();

        logger.debug("DefaultMailboxProvider creating mailbox - config: {}, workloadHint: {}",
                     effectiveConfig, workloadTypeHint);

        // Handle deprecated ResizableMailboxConfig - log warning and use default strategy
        if (effectiveConfig instanceof ResizableMailboxConfig) {
            logger.warn("ResizableMailboxConfig is deprecated due to performance issues. " +
                       "Using default strategy instead with capacity: {}. " +
                       "Consider using MailboxConfig for better performance.",
                       effectiveConfig.getMaxCapacity());
            return defaultStrategy.createMailbox(effectiveConfig);
        }

        // Select and apply strategy based on workload hint
        MailboxCreationStrategy<M> strategy = (workloadTypeHint != null)
                ? strategies.getOrDefault(workloadTypeHint, defaultStrategy)
                : defaultStrategy;

        return strategy.createMailbox(effectiveConfig);
    }
}
