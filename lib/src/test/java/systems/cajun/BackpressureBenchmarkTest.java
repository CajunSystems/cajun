package systems.cajun;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Benchmark tests comparing performance of actors with and without backpressure.
 */
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
    public void benchmarkBackpressureVsStandard() throws Exception {
        final int messageCount = 1_000_000;
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
        String actorType = enableBackpressure ? "backpressure" : "standard";
        logger.info("Starting {} benchmark with {} messages and {} producers", 
                actorType, messageCount, producerCount);
        
        // Create consumer actor
        Pid consumerPid;
        if (enableBackpressure) {
            consumerPid = system.register(BenchmarkActor.class, "consumer-" + actorType, true, 10_000, 100_000);
        } else {
            consumerPid = system.register(BenchmarkActor.class, "consumer-" + actorType, false);
        }
        BenchmarkActor consumer = (BenchmarkActor) system.getActor(consumerPid);
        
        // Set up completion latch
        CountDownLatch completionLatch = new CountDownLatch(1);
        consumer.setCompletionLatch(completionLatch, messageCount);
        
        // Track metrics if backpressure is enabled
        if (enableBackpressure) {
            consumer.withBackpressureCallback(metrics -> {
                if (metrics.getCurrentSize() % 10_000 == 0 && metrics.getCurrentSize() > 0) {
                    logger.info("Mailbox size: {}/{}, Processing rate: {} msg/s, Backpressure: {}", 
                            metrics.getCurrentSize(), metrics.getCapacity(), 
                            metrics.getProcessingRate(), metrics.isBackpressureActive());
                }
            });
        }
        
        // Start timing
        long startTime = System.currentTimeMillis();
        
        // Create and start producer threads
        Thread[] producers = new Thread[producerCount];
        final int messagesPerProducer = messageCount / producerCount;
        
        for (int i = 0; i < producerCount; i++) {
            final int producerId = i;
            producers[i] = new Thread(() -> {
                try {
                    int start = producerId * messagesPerProducer;
                    int end = start + messagesPerProducer;
                    
                    for (int j = start; j < end; j++) {
                        boolean sent = false;
                        while (!sent) {
                            sent = consumer.tryTell("Message-" + j);
                            if (!sent) {
                                // Backoff when backpressured
                                if (enableBackpressure) {
                                    Actor.BackpressureMetrics metrics = consumer.getBackpressureMetrics();
                                    if (metrics != null && metrics.isBackpressureActive()) {
                                        Thread.sleep(1);
                                    } else {
                                        Thread.yield();
                                    }
                                } else {
                                    Thread.yield();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error("Producer error", e);
                }
            });
            producers[i].start();
        }
        
        // Wait for completion
        boolean completed = completionLatch.await(5, TimeUnit.MINUTES);
        long endTime = System.currentTimeMillis();
        
        if (!completed) {
            logger.warn("Benchmark timed out!");
        }
        
        // Calculate duration
        long duration = endTime - startTime;
        
        // Log results
        double messagesPerSecond = messageCount * 1000.0 / duration;
        logger.info("{} benchmark completed in {} ms ({} messages/sec)", 
                actorType, duration, String.format("%.2f", messagesPerSecond));
        
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
            super(system, actorId, enableBackpressure);
        }
        
        public BenchmarkActor(ActorSystem system, String actorId, boolean enableBackpressure, 
                            int initialCapacity, int maxCapacity) {
            super(system, actorId, enableBackpressure, initialCapacity, maxCapacity);
        }
        
        public void setCompletionLatch(CountDownLatch latch, int targetCount) {
            this.completionLatch = latch;
            this.targetMessageCount = targetCount;
        }
        
        @Override
        protected void receive(String message) {
            int count = processedCount.incrementAndGet();
            
            // Log progress
            if (count % 100_000 == 0) {
                logger.info("Processed {} messages", count);
            }
            
            // Signal completion when target is reached
            if (count >= targetMessageCount && completionLatch != null) {
                completionLatch.countDown();
            }
            
            // Simulate some processing work
            for (int i = 0; i < 100; i++) {
                Math.sqrt(i * count);
            }
        }
    }
}
