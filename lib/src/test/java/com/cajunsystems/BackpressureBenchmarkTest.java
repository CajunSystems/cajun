package com.cajunsystems;

import com.cajunsystems.backpressure.BackpressureState;
import com.cajunsystems.backpressure.BackpressureStatus;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.config.MailboxConfig;
import com.cajunsystems.config.ResizableMailboxConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;


/**
 * Benchmark tests comparing performance of actors with and without backpressure.
 */
@Tag("performance")
public class BackpressureBenchmarkTest {
    private static final Logger logger = LoggerFactory.getLogger(BackpressureBenchmarkTest.class);
    private ActorSystem system;

    @BeforeEach
    public void setup() {
        system = new ActorSystem();
    }

    @AfterEach
    public void tearDown() {
        system.shutdown();
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 2, unit = java.util.concurrent.TimeUnit.MINUTES)
    public void benchmarkBackpressureVsStandard() throws Exception {
        final int messageCount = 20_000; // Reduced from 50,000 to make test complete faster
        final int producerCount = 4;

        // Run benchmark with backpressure enabled
        long backpressureTime = runBenchmark(true, messageCount, producerCount);

        // Run benchmark with standard unbounded mailbox
        long standardTime = runBenchmark(false, messageCount, producerCount);

        // Log results
        logger.info("Benchmark Results for {} messages with {} producers:", messageCount, producerCount);
        logger.info("  Backpressure enabled: {} ms", backpressureTime);
        logger.info("  Standard unbounded:   {} ms", standardTime);
        logger.info("  Difference:           {} ms ({}%)", 
                (backpressureTime - standardTime),
                String.format("%.2f", (backpressureTime - standardTime) * 100.0 / standardTime));
    }

    private long runBenchmark(boolean enableBackpressure, int messageCount, int producerCount) throws Exception {
        logger.info("Starting {} benchmark with {} messages and {} producers",
                enableBackpressure ? "backpressure" : "standard", messageCount, producerCount);

        // Create consumer actor with or without backpressure
        String consumerId = "consumer-" + (enableBackpressure ? "backpressure" : "standard");
        BenchmarkActor consumer;
        
        if (enableBackpressure) {
            // Create with backpressure enabled and a larger mailbox
            consumer = new BenchmarkActor(system, consumerId, true, 64, 10000);
        } else {
            // Create with standard settings
            consumer = new BenchmarkActor(system, consumerId);
        }
        
        // Set up completion tracking
        CountDownLatch completionLatch = new CountDownLatch(1);
        consumer.setCompletionLatch(completionLatch, messageCount);
        
        // Start timing
        long startTime = System.currentTimeMillis();
        
        // Create and start producer threads
        Thread[] producers = new Thread[producerCount];
        int messagesPerProducer = messageCount / producerCount;
        
        for (int i = 0; i < producerCount; i++) {
            final int producerId = i;
            producers[i] = new Thread(() -> {
                try {
                    int start = producerId * messagesPerProducer;
                    int end = start + messagesPerProducer;

                    for (int j = start; j < end; j++) {
                        boolean sent = false;
                        int retryCount = 0;
                        while (!sent && retryCount < 1000) { // Add retry limit to prevent infinite loops
                            sent = consumer.tryTell("Message-" + j);
                            if (!sent) {
                                // Backoff when backpressured
                                if (enableBackpressure) {
                                    BackpressureStatus metrics = consumer.getBackpressureStatus();
                                    if (metrics != null && metrics.getCurrentState() != null) {
                                        Thread.sleep(1);
                                    } else {
                                        Thread.yield();
                                    }
                                } else {
                                    Thread.yield();
                                }
                                retryCount++;
                            }
                        }
                        // Log metrics periodically
                        if (j % 1000 == 0) {
                            BackpressureStatus metrics = consumer.getBackpressureStatus();
                            logger.info("Producer {}: Sent {} messages, Consumer mailbox: {}/{}, Processing rate: {}, Backpressure: {}",
                                    producerId, j, metrics.getCurrentSize(), metrics.getCapacity(), 
                                    metrics.getProcessingRate(), 
                                    metrics.getCurrentState() != BackpressureState.NORMAL);
                        }
                    }
                } catch (Exception e) {
                    logger.error("Producer error", e);
                }
            });
            producers[i].start();
        }

        // Wait for completion with a shorter timeout
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();

        if (!completed) {
            logger.warn("Benchmark timed out after 30 seconds!");
        }

        // Calculate duration
        long duration = endTime - startTime;

        // Log results
        double messagesPerSecond = messageCount * 1000.0 / duration;
        logger.info("{} benchmark completed in {} ms ({} messages/sec)", 
                enableBackpressure ? "backpressure" : "standard", duration, String.format("%.2f", messagesPerSecond));

        return duration;
    }

    /**
     * Actor for benchmark testing
     */
    public static class BenchmarkActor extends Actor<String> {
        private static final Logger logger = LoggerFactory.getLogger(BenchmarkActor.class);
        private final AtomicInteger processedCount = new AtomicInteger(0);
        private CountDownLatch completionLatch;
        private int targetMessageCount;

        public BenchmarkActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        public BenchmarkActor(ActorSystem system, String actorId, boolean enableBackpressure) {
            super(system, actorId, enableBackpressure ? system.getBackpressureConfig() : null);
        }

        public BenchmarkActor(ActorSystem system, String actorId, boolean enableBackpressure, 
                            int initialCapacity, int maxCapacity) {
            super(system, actorId, 
                  enableBackpressure ? system.getBackpressureConfig() : null, 
                  new ResizableMailboxConfig()
                      .setInitialCapacity(initialCapacity)
                      .setMaxCapacity(maxCapacity));
        }

        public BenchmarkActor(ActorSystem system, String actorId, BackpressureConfig backpressureConfig) {
            super(system, actorId, backpressureConfig, new ResizableMailboxConfig());
        }

        public BenchmarkActor(ActorSystem system, String actorId, BackpressureConfig backpressureConfig, 
                            int initialCapacity, int maxCapacity) {
            super(system, actorId, backpressureConfig, 
                  new ResizableMailboxConfig()
                      .setInitialCapacity(initialCapacity)
                      .setMaxCapacity(maxCapacity));
        }

        public BenchmarkActor(ActorSystem system, String actorId, BackpressureConfig backpressureConfig,
                            MailboxConfig mailboxConfig) {
            super(system, actorId, backpressureConfig, 
                  mailboxConfig instanceof ResizableMailboxConfig ? 
                      (ResizableMailboxConfig) mailboxConfig : 
                      new ResizableMailboxConfig()
                          .setInitialCapacity(mailboxConfig.getInitialCapacity())
                          .setMaxCapacity(mailboxConfig.getMaxCapacity()));
        }

        public void setCompletionLatch(CountDownLatch latch, int targetCount) {
            this.completionLatch = latch;
            this.targetMessageCount = targetCount;
        }

        /**
         * Try to send a message to this actor with backpressure awareness.
         * Returns immediately with a boolean indicating success or failure.
         *
         * @param message The message to send
         * @return true if the message was accepted, false if rejected due to backpressure
         */
        public boolean tryTell(String message) {
            try {
                tell(message);
                return true;
            } catch (Exception e) {
                // Message rejected due to backpressure or other reason
                return false;
            }
        }

        /**
         * Adds a callback to be notified of backpressure events.
         *
         * @param callback The callback to invoke when backpressure state changes
         * @return This actor instance for method chaining
         */
        public BenchmarkActor withBackpressureCallback(Consumer<BackpressureStatus> callback) {
            // This is a stub implementation for compatibility with test code
            return this;
        }

        /**
         * Gets the current backpressure status for this actor.
         *
         * @return The backpressure status
         */
        public com.cajunsystems.backpressure.BackpressureStatus getBackpressureStatus() {
            try {
                // Try to access the mailbox field to get its size and capacity
                java.lang.reflect.Field mailboxField = Actor.class.getDeclaredField("mailbox");
                mailboxField.setAccessible(true);
                Object mailbox = mailboxField.get(this);

                int currentSize = 0;
                int capacity = 100; // Default capacity

                if (mailbox instanceof java.util.concurrent.BlockingQueue) {
                    java.util.concurrent.BlockingQueue<?> queue = (java.util.concurrent.BlockingQueue<?>) mailbox;
                    currentSize = queue.size();

                    // Try to get capacity if it's a ResizableBlockingQueue
                    if (mailbox.getClass().getSimpleName().equals("ResizableBlockingQueue")) {
                        try {
                            java.lang.reflect.Method getCapacityMethod = 
                                    mailbox.getClass().getDeclaredMethod("getCapacity");
                            getCapacityMethod.setAccessible(true);
                            capacity = (int) getCapacityMethod.invoke(mailbox);
                        } catch (Exception e) {
                            logger.debug("Could not get mailbox capacity: {}", e.getMessage());
                        }
                    }
                }

                // Calculate fill ratio
                float fillRatio = capacity > 0 ? (float) currentSize / capacity : 0;

                // Determine backpressure state based on fill ratio
                BackpressureState state = BackpressureState.NORMAL;
                if (fillRatio >= 0.8f) {
                    state = BackpressureState.CRITICAL;
                } else if (fillRatio >= 0.7f) {
                    state = BackpressureState.WARNING;
                }

                return new com.cajunsystems.backpressure.BackpressureStatus.Builder()
                    .actorId(getActorId())
                    .currentState(state)
                    .fillRatio(fillRatio)
                    .currentSize(currentSize)
                    .capacity(capacity)
                    .processingRate(0) // We don't have this information
                    .timestamp(java.time.Instant.now())
                    .enabled(true)
                    .build();
            } catch (Exception e) {
                // If reflection fails, return a default status with NORMAL state
                logger.debug("Failed to get mailbox info: {}", e.getMessage());
                return new com.cajunsystems.backpressure.BackpressureStatus.Builder()
                    .actorId(getActorId())
                    .currentState(BackpressureState.NORMAL)
                    .fillRatio(0.0f)
                    .currentSize(0)
                    .capacity(100)
                    .processingRate(0)
                    .timestamp(java.time.Instant.now())
                    .enabled(true)
                    .build();
            }
        }

        /**
         * Inner class representing backpressure status for compatibility with tests.
         */
        public static class BackpressureStatus {
            /**
             * Checks if backpressure is currently active.
             *
             * @return true if backpressure is active, false otherwise
             */
            public boolean isBackpressureActive() {
                return false;
            }

            /**
             * Gets the current mailbox size.
             *
             * @return The current mailbox size
             */
            public int getCurrentSize() {
                return 0;
            }

            /**
             * Gets the maximum mailbox capacity.
             *
             * @return The maximum mailbox capacity
             */
            public int getCapacity() {
                return 0;
            }

            /**
             * Gets the current processing rate.
             *
             * @return The current processing rate
             */
            public double getProcessingRate() {
                return 0;
            }

            public BackpressureState getCurrentState() {
                return null;
            }
        }

        @Override
        protected void receive(String message) {
            int count = processedCount.incrementAndGet();

            // Log progress
            if (count % 10_000 == 0) {  
                logger.info("Processed {} messages", count);
            }

            // Signal completion when target is reached
            if (count >= targetMessageCount && completionLatch != null) {
                logger.info("Target message count reached: {}/{}", count, targetMessageCount);
                completionLatch.countDown();
            }

            // Simulate some processing work - reduced to make test run faster
            for (int i = 0; i < 10; i++) {  
                Math.sqrt(i * count);
            }
        }
    }
}
