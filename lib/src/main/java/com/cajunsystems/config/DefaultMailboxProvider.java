package com.cajunsystems.config;

import com.cajunsystems.MpscArrayBlockingQueue;
import com.cajunsystems.ResizableBlockingQueue;
import com.cajunsystems.UnboundedConcurrentBlockingQueue;
import com.cajunsystems.config.ThreadPoolFactory.WorkloadType;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultMailboxProvider<M> implements MailboxProvider<M> {
    private static final Logger logger = LoggerFactory.getLogger(DefaultMailboxProvider.class);

    public DefaultMailboxProvider() {
    }

    @Override
    public BlockingQueue<M> createMailbox(MailboxConfig config, WorkloadType workloadTypeHint) {
        MailboxConfig effectiveConfig = (config != null) ? config : new MailboxConfig();
        int initialCapacity = effectiveConfig.getInitialCapacity();
        int maxCapacity = effectiveConfig.getMaxCapacity();

        logger.debug("DefaultMailboxProvider received MailboxConfig type: {}, initialCapacity: {}, maxCapacity: {}",
                     effectiveConfig.getClass().getName(), initialCapacity, maxCapacity);
        logger.debug("WorkloadType hint received: {}", workloadTypeHint);

        // Priority 1: Check for high-throughput configuration (MpscArrayQueue)
        if (effectiveConfig instanceof HighThroughputMailboxConfig) {
            HighThroughputMailboxConfig htmc = (HighThroughputMailboxConfig) effectiveConfig;
            logger.info("High-throughput mailbox requested. Creating MpscArrayBlockingQueue with capacity: {}",
                        htmc.getCapacity());
            return new MpscArrayBlockingQueue<>(htmc.getCapacity());
        }

        // Priority 2: Check for unbounded configuration (ConcurrentLinkedQueue)
        if (effectiveConfig instanceof UnboundedMailboxConfig) {
            logger.info("Unbounded mailbox requested. Creating UnboundedConcurrentBlockingQueue");
            return new UnboundedConcurrentBlockingQueue<>();
        }

        // Priority 3: Check for resizable configuration
        if (effectiveConfig instanceof ResizableMailboxConfig) {
            ResizableMailboxConfig rmc = (ResizableMailboxConfig) effectiveConfig;
            logger.info("Prioritizing ResizableMailboxConfig. Creating ResizableBlockingQueue with initialCapacity: {}, maxCapacity: {}, resizeThreshold: {}, resizeFactor: {}",
                        initialCapacity,
                        maxCapacity,
                        rmc.getResizeThreshold(),
                        rmc.getResizeFactor());
            ResizableBlockingQueue<M> queue = new ResizableBlockingQueue<>(
                    initialCapacity,
                    maxCapacity
            );
            return queue;
        }

        // Priority 4: Use workload type hint
        if (workloadTypeHint != null) {
            switch (workloadTypeHint) {
                case IO_BOUND:
                    logger.info("Workload hint IO_BOUND. Creating LinkedBlockingQueue with capacity: {}", Math.max(maxCapacity, 10000));
                    return new LinkedBlockingQueue<>(Math.max(maxCapacity, 10000));
                case CPU_BOUND:
                    int boundedCapacity = Math.min(maxCapacity, 1000);
                    logger.info("Workload hint CPU_BOUND. Creating ArrayBlockingQueue with capacity: {}", boundedCapacity);
                    return new ArrayBlockingQueue<>(boundedCapacity);
                case MIXED:
                default:
                    logger.info("Workload hint MIXED/UNKNOWN/Default. Creating LinkedBlockingQueue with capacity: {}", maxCapacity);
                    return new LinkedBlockingQueue<>(maxCapacity);
            }
        }

        logger.info("Defaulting (no ResizableConfig, null hint). Creating LinkedBlockingQueue with capacity: {}", maxCapacity);
        return new LinkedBlockingQueue<>(maxCapacity);
    }
}
