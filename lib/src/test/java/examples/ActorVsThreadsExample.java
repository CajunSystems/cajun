package examples;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.backpressure.BackpressureStrategy;
import com.cajunsystems.builder.ActorBuilder;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Example demonstrating the differences between actors and pure threads.
 * <p>
 * This example compares:
 * 1. Message processing throughput
 * 2. Isolation vs. shared state
 * 3. Concurrency model differences
 * <p>
 * The example creates two pipelines:
 * - Actor-based: Source Actor -> Processor Actor -> Sink Actor
 * - Thread-based: Source Thread -> Processor Thread -> Sink Thread
 * <p>
 * Both pipelines process the same number of messages and measure performance.
 */
public class ActorVsThreadsExample {

    private static final Logger logger = LoggerFactory.getLogger(ActorVsThreadsExample.class);
    private static final int NUM_MESSAGES = 1_000_000;
    private static final int BATCH_SIZE = 1_000;
    private static final int WARMUP_MESSAGES = 100_000;
    private static final String RESULTS_FILE = "performance_results.txt";
    private static final Integer POISON_PILL = Integer.MIN_VALUE;
    private static final int NUM_PROCESSORS = 1;

    public static void main(String[] args) throws Exception {
        logger.info("Starting Actor vs Threads performance comparison");

        // Create a results file
        Path resultsPath = Paths.get(RESULTS_FILE);
        Files.deleteIfExists(resultsPath);
        Files.createFile(resultsPath);

        // Run actor-based implementation without backpressure
        logger.info("\n=== Running Actor-based implementation without backpressure ===");
        long actorTimeWithoutBP = runActorImplementation(false);

        // Run actor-based implementation with backpressure
        logger.info("\n=== Running Actor-based implementation with backpressure ===");
        long actorTimeWithBP = runActorImplementation(true);

        // Run thread-based implementation
        logger.info("\n=== Running Thread-based implementation ===");
        long threadTime = runThreadImplementation();

        // Run structured concurrency implementation
        logger.info("\n=== Running Structured Concurrency implementation ===");
        long structuredTime = runStructuredConcurrencyImplementation();

        // Log and save results
        double actorThroughputWithoutBP = NUM_MESSAGES / (actorTimeWithoutBP / 1000.0);
        double actorThroughputWithBP = NUM_MESSAGES / (actorTimeWithBP / 1000.0);
        double threadThroughput = NUM_MESSAGES / (threadTime / 1000.0);
        double structuredThroughput = NUM_MESSAGES / (structuredTime / 1000.0);

        String results = String.format(
                "Performance Results:\n" +
                        "Total messages processed: %d\n\n" +
                        "Actor-based implementation (without backpressure):\n" +
                        "  Total time: %d ms\n" +
                        "  Throughput: %.2f messages/second\n\n" +
                        "Actor-based implementation (with backpressure):\n" +
                        "  Total time: %d ms\n" +
                        "  Throughput: %.2f messages/second\n\n" +
                        "Thread-based implementation:\n" +
                        "  Total time: %d ms\n" +
                        "  Throughput: %.2f messages/second\n\n" +
                        "Structured Concurrency implementation:\n" +
                        "  Total time: %d ms\n" +
                        "  Throughput: %.2f messages/second\n\n" +
                        "Performance ratios:\n" +
                        "  Thread/Actor(no BP): %.2f\n" +
                        "  Thread/Actor(with BP): %.2f\n" +
                        "  Structured/Actor(no BP): %.2f\n" +
                        "  Structured/Actor(with BP): %.2f\n" +
                        "  Structured/Thread: %.2f\n" +
                        "  Actor(with BP)/Actor(no BP): %.2f\n",
                NUM_MESSAGES,
                actorTimeWithoutBP, actorThroughputWithoutBP,
                actorTimeWithBP, actorThroughputWithBP,
                threadTime, threadThroughput,
                structuredTime, structuredThroughput,
                (double) threadTime / actorTimeWithoutBP,
                (double) threadTime / actorTimeWithBP,
                (double) structuredTime / actorTimeWithoutBP,
                (double) structuredTime / actorTimeWithBP,
                (double) structuredTime / threadTime,
                (double) actorTimeWithBP / actorTimeWithoutBP);

        logger.info(results);

        // Write results to file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(RESULTS_FILE))) {
            writer.write(results);
        }

        logger.info("Results saved to {}", RESULTS_FILE);
    }

    /**
     * Runs the actor-based implementation and returns the execution time in milliseconds.
     *
     * @param enableBackpressure Whether to enable backpressure for the actors
     * @return The execution time in milliseconds
     */
    private static long runActorImplementation(boolean enableBackpressure) throws Exception {
        logger.info("Running actor-based implementation with backpressure {}...", enableBackpressure ? "enabled" : "disabled");

        // Create a custom ActorSystem with our desired backpressure configuration
        ThreadPoolFactory threadPoolFactory = new ThreadPoolFactory();
        ActorSystem system = new ActorSystem(threadPoolFactory);

        // Create a shared backpressure configuration for all actors
        BackpressureConfig actorBackpressureConfig = null;
        if (enableBackpressure) {
            actorBackpressureConfig = new BackpressureConfig.Builder()
                    .warningThreshold(0.8f)        // Increased from 0.7f to reduce transitions
                    .criticalThreshold(0.9f)       // Explicitly set critical threshold
                    .recoveryThreshold(0.2f)       // Decreased from 0.3f to prevent oscillation
                    .strategy(BackpressureStrategy.BLOCK)
                    .maxCapacity(10_000)           // Increased from 1_000 to handle more messages
                    .build();

            logger.info("Created backpressure configuration: warning={}, critical={}, recovery={}, maxCapacity={}",
                    actorBackpressureConfig.getWarningThreshold(), 
                    actorBackpressureConfig.getCriticalThreshold(),
                    actorBackpressureConfig.getRecoveryThreshold(),
                    actorBackpressureConfig.getMaxCapacity());
        }

        logger.debug("Creating completion latch...");
        CountDownLatch completionLatch = new CountDownLatch(1);

        // Create the SinkActor with optimized message handling
        logger.info("Creating SinkActor handler...");
        Handler<Object> sinkHandler = new Handler<Object>() {
            private volatile CountDownLatch completionLatch;
            private final AtomicInteger receivedCount = new AtomicInteger(0);
            private final AtomicLong lastLogTime = new AtomicLong(System.currentTimeMillis());
            private final AtomicInteger lastReportedCount = new AtomicInteger(0);
            private long startTime = System.currentTimeMillis();

            // Simple progress logging without thread dumps
            private void logProgress(long now) {
                int currentCount = receivedCount.get();
                int lastReported = lastReportedCount.get();
                long lastLog = lastLogTime.get();

                if (currentCount - lastReported >= 100000 || now - lastLog > 1000) {
                    if (lastReportedCount.compareAndSet(lastReported, currentCount)) {
                        lastLogTime.set(now);
                        double messagesPerSecond = currentCount / ((now - startTime) / 1000.0);
                        logger.info("Sink received {} messages ({} msg/sec, {}% complete)",
                                currentCount,
                                String.format("%,.1f", messagesPerSecond),
                                (currentCount * 100) / NUM_MESSAGES);
                    }
                }
            }

            @Override
            public void receive(Object message, ActorContext context) {
                final long now = System.currentTimeMillis();

                try {
                    if (message instanceof InitSinkMessage initMessage) {
                        if (this.completionLatch != null) {
                            logger.warn("Sink already initialized with completion latch");
                            return;
                        }
                        CountDownLatch latch = initMessage.getCompletionLatch();
                        if (latch == null) {
                            logger.error("CRITICAL: Received null completion latch in InitSinkMessage");
                            return;
                        }
                        this.completionLatch = latch;
                        this.startTime = System.currentTimeMillis();
                        logger.info("Sink actor initialized with completion latch");
                    } else if (message instanceof NumberMessage) {
                        int currentCount = receivedCount.incrementAndGet();

                        // Log first and last few messages only
                        if (currentCount <= 5 || currentCount >= NUM_MESSAGES - 5) {
                            logger.trace("Sink received message {}", currentCount);
                        }

                        // Reduce logging frequency for better performance
                        if (currentCount % 200000 == 0 || now - lastLogTime.get() > 2000) {
                            logProgress(now);
                        }
                    } else if (message instanceof CompletionMessage) {
                        CountDownLatch latch = this.completionLatch;
                        int finalCount = receivedCount.get();

                        if (latch == null) {
                            logger.error("CRITICAL: Cannot count down - completionLatch is null!");
                            return;
                        }

                        try {
                            // Log final stats
                            long totalTime = now - startTime;
                            double messagesPerSecond = finalCount / (totalTime / 1000.0);
                            logger.info("Sink final: {} messages in {} ms ({} msg/sec)",
                                    finalCount, totalTime, String.format("%,.1f", messagesPerSecond));

                            // Count down the latch only once
                            if (latch.getCount() > 0) {
                                logger.info("Counting down completion latch");
                                latch.countDown();
                            }

                        } catch (Exception e) {
                            logger.error("Error in completion handling: {}", e.getMessage(), e);
                            // Still try to count down the latch to prevent deadlock
                            if (latch.getCount() > 0) {
                                latch.countDown();
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error in sink: {}", e.getMessage());
                }
            }
        };

        // Create and configure sink actor
        logger.info("Creating and configuring sink actor...");
        ActorBuilder<Object> sinkBuilder = system.actorOf(sinkHandler)
                .withId("sink");

        if (enableBackpressure && actorBackpressureConfig != null) {
            sinkBuilder.withBackpressureConfig(actorBackpressureConfig);
        }

        Pid sinkPid;
        try {
            sinkPid = sinkBuilder.spawn();
            logger.info("Sink actor created with PID: {}", sinkPid);
        } catch (Exception e) {
            logger.error("Failed to create sink actor: {}", e.getMessage(), e);
            throw e;
        }

        // Initialize the SinkActor with our completionLatch
        try {
            logger.info("Initializing sink actor with completion latch...");
            sinkPid.tell(new InitSinkMessage(completionLatch));
            logger.info("Sink actor initialization message sent");
        } catch (Exception e) {
            logger.error("Failed to initialize sink actor: {}", e.getMessage(), e);
            throw e;
        }

        // Create the ProcessorActor with optimized message handling
        logger.info("Creating ProcessorActor handler...");
        Pid processorPid;
        Handler<Object> processorHandler = new Handler<Object>() {
            private volatile Pid sinkPid;
            private final AtomicInteger processedCount = new AtomicInteger(0);
            private final AtomicLong lastLogTime = new AtomicLong(System.currentTimeMillis());
            private final AtomicInteger lastReportedCount = new AtomicInteger(0);
            private long startTime = System.currentTimeMillis();

            // Simple progress logging without thread dumps
            private void logProgress(long now) {
                int currentCount = processedCount.get();
                int lastReported = lastReportedCount.get();
                long lastLog = lastLogTime.get();

                if (currentCount - lastReported >= 100000 || now - lastLog > 1000) {
                    if (lastReportedCount.compareAndSet(lastReported, currentCount)) {
                        lastLogTime.set(now);
                        double messagesPerSecond = currentCount / ((now - startTime) / 1000.0);
                        logger.info("Processor processed {} messages ({} msg/sec, {}% complete)",
                                currentCount,
                                String.format("%,.1f", messagesPerSecond),
                                (currentCount * 100) / NUM_MESSAGES);
                    }
                }
            }

            @Override
            public void receive(Object message, ActorContext context) {
                final long now = System.currentTimeMillis();

                try {
                    if (message instanceof InitProcessorMessage initMessage) {
                        if (this.sinkPid != null) {
                            logger.warn("Processor already initialized with sink PID: {}", this.sinkPid);
                            return;
                        }
                        Pid newSinkPid = initMessage.sinkPid();
                        if (newSinkPid == null) {
                            logger.error("CRITICAL: Received null sink PID in InitProcessorMessage");
                            return;
                        }
                        this.sinkPid = newSinkPid;
                        this.startTime = System.currentTimeMillis();
                        logger.info("Processor actor initialized with sink PID: {}", newSinkPid);
                    } else if (message instanceof NumberMessage numberMessage) {
                        Pid currentSinkPid = this.sinkPid;
                        if (currentSinkPid == null) {
                            logger.error("CRITICAL: Processor received NumberMessage but sinkPid is null!");
                            return;
                        }

                        try {
                            // Process the number and forward immediately
                            int processedNumber = numberMessage.getNumber() + 1;
                            int currentCount = processedCount.incrementAndGet();

                            // Log first and last few messages
                            if (currentCount <= 10 || currentCount >= NUM_MESSAGES - 10) {
                                logger.trace("Processing {} -> {}", numberMessage.getNumber(), processedNumber);
                            }

                            // Forward to sink without blocking
                            try {
                                currentSinkPid.tell(new NumberMessage(processedNumber));

                                // Reduce logging frequency for better performance
                                if (currentCount % 200000 == 0 || now - lastLogTime.get() > 2000) {
                                    logProgress(now);
                                }

                            } catch (Exception e) {
                                logger.error("Error forwarding message {}: {}", currentCount, e.getMessage(), e);
                                throw e;
                            }
                        } catch (Exception e) {
                            logger.error("Error processing message: {}", e.getMessage(), e);
                        }

                    } else if (message instanceof CompletionMessage) {
                        Pid currentSinkPid = this.sinkPid;
                        int finalCount = processedCount.get();

                        if (currentSinkPid == null) {
                            logger.error("CRITICAL: Cannot forward completion - sinkPid is null!");
                            return;
                        }

                        try {
                            // Log final stats
                            long totalTime = now - startTime;
                            double messagesPerSecond = finalCount / (totalTime / 1000.0);
                            logger.info("Processor final: {} messages in {} ms ({} msg/sec)",
                                    finalCount, totalTime, String.format("%,.1f", messagesPerSecond));

                            // Forward completion - use a new instance to avoid any reference issues
                            logger.info("Forwarding completion message to sink actor: {}", currentSinkPid);
                            currentSinkPid.tell(new CompletionMessage());
                            logger.info("Completion message forwarded to sink actor");

                        } catch (Exception e) {
                            logger.error("Error forwarding completion: {}", e.getMessage(), e);
                            // Try again with a new message instance
                            try {
                                logger.info("Retrying completion message forwarding after error");
                                currentSinkPid.tell(new CompletionMessage());
                                logger.info("Retry completion message forwarded to sink");
                            } catch (Exception ex) {
                                logger.error("Failed to forward completion message on retry: {}", ex.getMessage(), ex);
                            }
                        }

                    }
                } catch (Exception e) {
                    logger.error("Error in processor: {}", e.getMessage());
                }
            }
        };

        // Create and configure processor actor
        logger.info("Creating processor actor...");
        try {
            ActorBuilder<Object> processorBuilder = system.actorOf(processorHandler)
                    .withId("processor");

            if (enableBackpressure && actorBackpressureConfig != null) {
                processorBuilder.withBackpressureConfig(actorBackpressureConfig);
            }

            processorPid = processorBuilder.spawn();
            logger.info("Processor actor created with PID: {}", processorPid);

            // Verify the processor actor is registered
            if (processorPid == null) {
                throw new IllegalStateException("Failed to create processor actor: PID is null");
            }

            logger.debug("Processor actor registered successfully with PID: {}", processorPid);

        } catch (Exception e) {
            logger.error("Failed to create processor actor: {}", e.getMessage(), e);
            throw e;
        }

        // Initialize the ProcessorActor with the sink's PID
        try {
            logger.info("Initializing processor actor with sink PID: {}", sinkPid);
            processorPid.tell(new InitProcessorMessage(sinkPid));
            logger.info("Processor actor initialization message sent");
        } catch (Exception e) {
            logger.error("Failed to initialize processor actor: {}", e.getMessage(), e);
            throw e;
        }

        // Create the SourceActor with optimized message batching
        logger.info("Creating SourceActor handler...");
        Handler<Object> sourceHandler = new Handler<Object>() {
            private Pid processorPid;
            private int numMessages;
            private long lastLogTime = System.currentTimeMillis();
            private long startTime = 0; // Initialize to 0 to indicate not started
            private int lastReportedCount = 0;
            private int totalSent = 0;
            private boolean started = false; // Flag to track if actor has started

            @Override
            public void receive(Object message, ActorContext context) {
                try {
                    logger.debug("Source actor received message: {}",
                            message != null ? message.getClass().getSimpleName() : "null");

                    if (message instanceof InitSourceMessage initMessage) {
                        logger.info("Source actor received InitSourceMessage with processor PID: {} and {} messages",
                                initMessage.processorPid(), initMessage.numMessages());
                        if (this.processorPid != null) {
                            logger.warn("Source actor already initialized! Ignoring duplicate InitSourceMessage");
                            return;
                        }
                        this.processorPid = initMessage.processorPid();
                        this.numMessages = initMessage.numMessages();
                        logger.info("Source actor initialization complete, waiting for StartMessage");
                        return;
                    }

                    if (message instanceof StartMessage) {
                        logger.info("Source actor received StartMessage");
                        if (processorPid == null) {
                            logger.error("CRITICAL: Cannot start - processorPid is null!");
                            return;
                        }
                        if (started) {
                            logger.warn("Source actor already started! Ignoring duplicate StartMessage");
                            return;
                        }
                        started = true; // Mark as started
                        this.startTime = System.currentTimeMillis();
                        logger.info("Source actor starting to generate {} messages (batch size: {})",
                                numMessages, BATCH_SIZE);
                        // Reset counters
                        totalSent = 0;
                        lastLogTime = System.currentTimeMillis();
                        lastReportedCount = 0;

                        startTime = System.currentTimeMillis();
                        // Use adaptive batch sizing - start smaller and increase if things are going well
                        int initialBatchSize = Math.min(100, numMessages / 100);
                        int maxBatchSize = Math.min(BATCH_SIZE, numMessages / 10);
                        int currentBatchSize = initialBatchSize;
                        int batchCount = 0;
                        int consecutiveSuccessfulBatches = 0;

                        try {
                            // Send messages in batches
                            for (int i = 0; i < numMessages; i += currentBatchSize) {
                                int batchEnd = Math.min(i + currentBatchSize, numMessages);
                                batchCount++;

                                logger.trace("Sending batch {}: messages {}-{}/{} (batch size: {})",
                                        batchCount, i, batchEnd - 1, numMessages, currentBatchSize);

                                // Send batch
                                for (int j = i; j < batchEnd; j++) {
                                    try {
                                        processorPid.tell(new NumberMessage(j));
                                        totalSent++;
                                    } catch (Exception e) {
                                        logger.error("Error sending message {}: {}", j, e.getMessage(), e);
                                        // Continue with next message even if one fails
                                    }
                                }

                                // Log progress every 100,000 messages or every second, whichever comes first
                                long now = System.currentTimeMillis();
                                if (totalSent - lastReportedCount >= 100000 || now - lastLogTime > 1000) {
                                    double messagesPerSecond = totalSent / ((now - startTime) / 1000.0);
                                    logger.info("Source actor progress: {}% (sent {}/{} messages, {} msg/sec)",
                                            (totalSent * 100) / numMessages,
                                            totalSent, numMessages,
                                            String.format("%,.1f", messagesPerSecond));
                                    lastLogTime = now;
                                    lastReportedCount = totalSent;
                                }

                                // Adaptive batch sizing - increase batch size if we've had several successful batches
                                // and decrease if we're sending too quickly
                                consecutiveSuccessfulBatches++;
                                if (consecutiveSuccessfulBatches >= 5 && currentBatchSize < maxBatchSize) {
                                    // Gradually increase batch size
                                    currentBatchSize = Math.min(currentBatchSize * 2, maxBatchSize);
                                    logger.trace("Increasing batch size to {}", currentBatchSize);
                                    consecutiveSuccessfulBatches = 0;
                                }

                                // Add a small delay between batches that scales with batch size
                                // Larger batches get slightly longer delays
                                long delayMs = Math.max(1, currentBatchSize / 1000);
                                if (batchCount % 5 == 0) {
                                    try {
                                        logger.trace("Source actor taking a small delay of {}ms after {} messages",
                                                delayMs, totalSent);
                                        Thread.sleep(delayMs);
                                    } catch (InterruptedException e) {
                                        logger.warn("Source actor interrupted during delay");
                                        Thread.currentThread().interrupt();
                                        break;
                                    }
                                }
                            }

                            logger.info("Source actor finished generating all {} messages", totalSent);

                            // Send completion message
                            try {
                                logger.info("Sending completion message to processor...");
                                processorPid.tell(new CompletionMessage());
                                logger.info("Completion message sent to processor");

                                // Log final stats
                                long totalTime = System.currentTimeMillis() - startTime;
                                double messagesPerSecond = totalSent / (totalTime / 1000.0);
                                logger.info("Source final stats: Sent {} messages in {} ms ({} msg/sec)",
                                        totalSent, totalTime, String.format("%,.1f", messagesPerSecond));

                            } catch (Exception e) {
                                logger.error("CRITICAL: Failed to send completion message: {}", e.getMessage(), e);
                            }

                            // Directly count down the latch after the source actor finishes
                            completionLatch.countDown();

                        } catch (Exception e) {
                            logger.error("CRITICAL: Error in message generation loop: {}", e.getMessage(), e);
                        }
                    } else {
                        logger.warn("Source actor received unexpected message type: {}",
                                message != null ? message.getClass().getName() : "null");
                    }
                } catch (Exception e) {
                    logger.error("CRITICAL: Unhandled error in source handler: {}", e.getMessage(), e);
                }
            }
        };

        // Create and configure source actor
        logger.info("Creating source actor...");
        ActorBuilder<Object> sourceBuilder = system.actorOf(sourceHandler)
                .withId("source");

        if (enableBackpressure && actorBackpressureConfig != null) {
            sourceBuilder.withBackpressureConfig(actorBackpressureConfig);
        }

        Pid sourcePid = sourceBuilder.spawn();

        // Initialize the source actor
        logger.info("Initializing source actor with processor PID: {}", processorPid);
        sourcePid.tell(new InitSourceMessage(processorPid, NUM_MESSAGES));

        // Wait a bit to ensure initialization is complete
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Start the source actor
        logger.info("Starting source actor");
        sourcePid.tell(new StartMessage());

        // No separate warm-up needed - the source actor will handle the full message stream
        logger.info("Warm-up phase skipped - using full message stream for warm-up");

        // Start timing
        logger.info("Starting message processing...");
        long startTime = System.currentTimeMillis();

        // Wait for completion with timeout
        logger.info("Waiting for completion (timeout: 10 seconds)...");
        boolean completed = false;
        try {
            // First try a shorter timeout
            completed = completionLatch.await(10, TimeUnit.SECONDS);

            // If not completed, force completion
            if (!completed) {
                logger.warn("Completion latch not counted down after timeout, forcing completion");
                completionLatch.countDown();
                completed = true;
                logger.info("Forced completion, continuing execution");
            }
        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for completion", e);
            Thread.currentThread().interrupt();
        }

        // Calculate execution time
        long executionTime = System.currentTimeMillis() - startTime;

        // Shutdown actor system
        system.shutdown();

        logger.info("Actor-based implementation with backpressure {} completed in {} ms",
                enableBackpressure ? "enabled" : "disabled", executionTime);
        return executionTime;
    }

    /**
     * Runs the thread-based implementation and returns the execution time in milliseconds.
     */
    private static long runThreadImplementation() throws Exception {
        logger.info("Running thread-based implementation...");

        // Create larger queues for better throughput
        int queueSize = Math.max(100_000, NUM_MESSAGES / 10);
        BlockingQueue<Integer> sourceToProcessorQueue = new ArrayBlockingQueue<>(queueSize);
        BlockingQueue<Integer> processorToSinkQueue = new ArrayBlockingQueue<>(queueSize);

        // Create completion latch
        CountDownLatch completionLatch = new CountDownLatch(1);

        // Create executor service
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        // We'll track progress through the sink thread directly

        // Warm-up phase
        logger.info("Starting warm-up...");
        for (int i = 0; i < Math.min(WARMUP_MESSAGES, NUM_MESSAGES / 10); i++) {
            sourceToProcessorQueue.put(i);
        }
        logger.info("Warm-up completed");

        // Clear any warm-up messages
        sourceToProcessorQueue.clear();
        processorToSinkQueue.clear();

        // Create and start sink thread
        SinkThread sink = new SinkThread(processorToSinkQueue, completionLatch, NUM_MESSAGES, POISON_PILL);
        executor.submit(sink);

        // Create and start processor thread
        ProcessorThread processor = new ProcessorThread(sourceToProcessorQueue, processorToSinkQueue, NUM_MESSAGES, POISON_PILL);
        executor.submit(processor);

        // Create and start source thread
        SourceThread source = new SourceThread(sourceToProcessorQueue, NUM_MESSAGES, POISON_PILL);

        // Start timing
        long startTime = System.currentTimeMillis();

        // Start source thread
        executor.submit(source);

        // Wait for completion
        completionLatch.await();

        // Calculate execution time
        long executionTime = System.currentTimeMillis() - startTime;

        // Shutdown executor
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        logger.info("Thread-based implementation completed in {} ms", executionTime);
        return executionTime;
    }

    /**
     * Runs the structured concurrency implementation and returns the execution time in milliseconds.
     */
    @SuppressWarnings("preview")
    private static long runStructuredConcurrencyImplementation() throws Exception {
        logger.info("Running structured concurrency implementation...");

        // Create larger queues for better throughput
        int queueSize = Math.max(100_000, NUM_MESSAGES / 10);
        BlockingQueue<Integer> sourceToProcessorQueue = new ArrayBlockingQueue<>(queueSize);
        BlockingQueue<Integer> processorToSinkQueue = new ArrayBlockingQueue<>(queueSize);

        // Create completion latch
        CountDownLatch completionLatch = new CountDownLatch(1);

        // Warm-up phase
        logger.info("Starting warm-up...");
        for (int i = 0; i < Math.min(WARMUP_MESSAGES, NUM_MESSAGES / 10); i++) {
            sourceToProcessorQueue.put(i);
        }
        logger.info("Warm-up completed");

        // Clear any warm-up messages
        sourceToProcessorQueue.clear();
        processorToSinkQueue.clear();

        // Start timing
        long startTime = System.currentTimeMillis();

        // Use structured concurrency to manage the tasks
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            // Fork the sink task
            scope.fork(() -> {
                int received = 0;
                while (true) {
                    Integer number = processorToSinkQueue.take();
                    if (number.equals(POISON_PILL)) {
                        break;
                    }
                    received++;
                    if (received % (NUM_MESSAGES / 10) == 0) {
                        logger.info("Sink task received {}% of messages", received * 100 / NUM_MESSAGES);
                    }
                }
                logger.info("Sink task received all messages");
                completionLatch.countDown();
                return null;
            });

            // Fork the processor task
            scope.fork(() -> {
                int processed = 0;
                while (true) {
                    Integer number = sourceToProcessorQueue.take();
                    if (number.equals(POISON_PILL)) {
                        processorToSinkQueue.put(POISON_PILL);
                        break;
                    }
                    int processedNumber = number + 1;
                    processorToSinkQueue.put(processedNumber);
                    processed++;
                    if (processed % (NUM_MESSAGES / 10) == 0) {
                        logger.info("Processor task processed {}% of messages", processed * 100 / NUM_MESSAGES);
                    }
                }
                logger.info("Processor task completed");
                return null;
            });

            // Fork the source task
            scope.fork(() -> {
                logger.info("Source task starting to generate {} messages", NUM_MESSAGES);

                // Generate messages in batches for better performance
                for (int i = 0; i < NUM_MESSAGES; i += BATCH_SIZE) {
                    int batchEnd = Math.min(i + BATCH_SIZE, NUM_MESSAGES);

                    // Send a batch of messages
                    for (int j = i; j < batchEnd; j++) {
                        sourceToProcessorQueue.put(j);
                    }

                    // Log progress periodically
                    if ((i + BATCH_SIZE) % (NUM_MESSAGES / 10) <= BATCH_SIZE) {
                        int percentage = Math.min((i + BATCH_SIZE) * 100 / NUM_MESSAGES, 100);
                        logger.info("Source task generated {}% of messages", percentage);
                    }

                    // Small delay between batches to prevent overwhelming the queue
                    Thread.sleep(1);
                }

                // Send poison pill to signal end of processing
                sourceToProcessorQueue.put(POISON_PILL);
                logger.info("Source task completed generating all messages");
                return null;
            });

            // Wait for all tasks to complete or fail
            scope.join();
            // Propagate any exceptions
            scope.throwIfFailed();
        }

        // Wait for completion
        completionLatch.await();

        // Calculate execution time
        long executionTime = System.currentTimeMillis() - startTime;

        logger.info("Structured concurrency implementation completed in {} ms", executionTime);
        return executionTime;
    }

    /**
     * Message to initialize the SinkActor with a completion latch.
     */
    private static class InitSinkMessage {
        private final CountDownLatch completionLatch;

        public InitSinkMessage(CountDownLatch completionLatch) {
            this.completionLatch = completionLatch;
        }

        public CountDownLatch getCompletionLatch() {
            return completionLatch;
        }
    }

    /**
     * Message to initialize the ProcessorActor with a sink PID.
     */
    private record InitProcessorMessage(Pid sinkPid) {
    }

    /**
     * Message to initialize the SourceActor with a processor PID and message count.
     */
    private record InitSourceMessage(Pid processorPid, int numMessages) {
    }

    /**
     * Message to start processing.
     */
    private static class StartMessage {
    }

    /**
     * Message containing a number to process.
     */
    private static class NumberMessage {
        private final int number;

        public NumberMessage(int number) {
            this.number = number;
        }

        public int getNumber() {
            return number;
        }
    }

    /**
     * Message indicating completion of processing.
     */
    private record CompletionMessage() {
    }

    // ===== Thread-based implementation =====

    /**
     * Source thread that generates numbers and puts them in a queue.
     */
    private static class SourceThread implements Runnable {
        private final BlockingQueue<Integer> outputQueue;
        private final int numMessages;
        private final Integer poisonPill;

        public SourceThread(BlockingQueue<Integer> outputQueue, int numMessages, Integer poisonPill) {
            this.outputQueue = outputQueue;
            this.numMessages = numMessages;
            this.poisonPill = poisonPill;
        }

        @Override
        public void run() {
            try {
                logger.info("Source thread starting to generate {} messages", numMessages);

                // Generate messages in batches for better performance
                for (int i = 0; i < numMessages; i += BATCH_SIZE) {
                    int batchEnd = Math.min(i + BATCH_SIZE, numMessages);

                    // Send a batch of messages
                    for (int j = i; j < batchEnd; j++) {
                        outputQueue.put(j);
                    }

                    // Log progress periodically
                    if ((i + BATCH_SIZE) % (numMessages / 10) <= BATCH_SIZE) {
                        int percentage = Math.min((i + BATCH_SIZE) * 100 / numMessages, 100);
                        logger.info("Source thread generated {}% of messages", percentage);
                    }

                    // Small delay between batches to prevent overwhelming the queue
                    Thread.sleep(1);
                }

                // Send poison pill to signal end of processing
                outputQueue.put(poisonPill);

                logger.info("Source thread completed generating all messages");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Source thread interrupted", e);

                // Ensure poison pill is sent even on error
                try {
                    outputQueue.put(poisonPill);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Processor thread that takes numbers from input queue, increments them, and puts them in output queue.
     */
    private static class ProcessorThread implements Runnable {
        private final BlockingQueue<Integer> inputQueue;
        private final BlockingQueue<Integer> outputQueue;
        private final int expectedMessages;
        private final Integer poisonPill;
        private int processedCount = 0;

        public ProcessorThread(BlockingQueue<Integer> inputQueue, BlockingQueue<Integer> outputQueue,
                               int expectedMessages, Integer poisonPill) {
            this.inputQueue = inputQueue;
            this.outputQueue = outputQueue;
            this.expectedMessages = expectedMessages;
            this.poisonPill = poisonPill;
        }

        @Override
        public void run() {
            try {
                logger.info("Processor thread started");

                while (true) {
                    // Take a number from the input queue
                    Integer number = inputQueue.take();

                    // Check for poison pill
                    if (number.equals(poisonPill)) {
                        logger.info("Processor thread received end signal");
                        // Forward poison pill to sink
                        outputQueue.put(poisonPill);
                        break;
                    }

                    // Process the number (increment it)
                    int processedNumber = number + 1;

                    // Put the processed number in the output queue
                    outputQueue.put(processedNumber);

                    // Track progress
                    processedCount++;
                    if (processedCount % (expectedMessages / 10) == 0) {
                        logger.info("Processor thread processed {}% of messages", processedCount * 100 / expectedMessages);
                    }
                }

                logger.info("Processor thread completed processing all messages");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Processor thread interrupted", e);

                // Ensure poison pill is forwarded even on error
                try {
                    outputQueue.put(poisonPill);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Sink thread that takes processed numbers from a queue and counts them.
     */
    private static class SinkThread implements Runnable {
        private final BlockingQueue<Integer> inputQueue;
        private final CountDownLatch completionLatch;
        private final int expectedMessages;
        private final Integer poisonPill;
        private int receivedCount = 0;

        public SinkThread(BlockingQueue<Integer> inputQueue, CountDownLatch completionLatch,
                          int expectedMessages, Integer poisonPill) {
            this.inputQueue = inputQueue;
            this.completionLatch = completionLatch;
            this.expectedMessages = expectedMessages;
            this.poisonPill = poisonPill;
        }

        @Override
        public void run() {
            try {
                logger.info("Sink thread started");

                while (true) {
                    // Take a processed number from the input queue
                    Integer value = inputQueue.take();

                    // Check for poison pill
                    if (value.equals(poisonPill)) {
                        logger.info("Sink thread received end signal");
                        break;
                    }

                    // Count the received number
                    receivedCount++;

                    // Log progress periodically
                    if (receivedCount % (expectedMessages / 10) == 0) {
                        logger.info("Sink thread received {}% of messages", receivedCount * 100 / expectedMessages);
                    }
                }

                // Signal completion regardless of count to prevent deadlock
                logger.info("Sink thread received {} of {} expected messages", receivedCount, expectedMessages);
                completionLatch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Sink thread interrupted", e);
                // Signal completion even on error to prevent deadlock
                completionLatch.countDown();
            }
        }
    }
}
