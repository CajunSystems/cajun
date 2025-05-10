package examples;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.cajun.Actor;
import systems.cajun.ActorSystem;
import systems.cajun.Pid;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final int BATCH_SIZE = 1000;
    private static final String RESULTS_FILE = "performance_results.txt";

    public static void main(String[] args) throws Exception {
        logger.info("Starting Actor vs Threads performance comparison");

        // Create a results file
        Path resultsPath = Paths.get(RESULTS_FILE);
        Files.deleteIfExists(resultsPath);
        Files.createFile(resultsPath);

        // Run actor-based implementation
        long actorTime = runActorImplementation();

        // Run thread-based implementation
        long threadTime = runThreadImplementation();

        // Run structured concurrency implementation
        long structuredTime = runStructuredConcurrencyImplementation();

        // Log and save results
        double actorThroughput = NUM_MESSAGES / (actorTime / 1000.0);
        double threadThroughput = NUM_MESSAGES / (threadTime / 1000.0);
        double structuredThroughput = NUM_MESSAGES / (structuredTime / 1000.0);

        String results = String.format(
                "Performance Results:\n" +
                        "Total messages processed: %d\n\n" +
                        "Actor-based implementation:\n" +
                        "  Total time: %d ms\n" +
                        "  Throughput: %.2f messages/second\n\n" +
                        "Thread-based implementation:\n" +
                        "  Total time: %d ms\n" +
                        "  Throughput: %.2f messages/second\n\n" +
                        "Structured Concurrency implementation:\n" +
                        "  Total time: %d ms\n" +
                        "  Throughput: %.2f messages/second\n\n" +
                        "Performance ratio (Thread/Actor): %.2f\n" +
                        "Performance ratio (Structured/Actor): %.2f\n" +
                        "Performance ratio (Structured/Thread): %.2f\n",
                NUM_MESSAGES,
                actorTime, actorThroughput,
                threadTime, threadThroughput,
                structuredTime, structuredThroughput,
                (double) threadTime / actorTime,
                (double) structuredTime / actorTime,
                (double) structuredTime / threadTime);

        logger.info(results);

        // Write results to file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(RESULTS_FILE))) {
            writer.write(results);
        }

        logger.info("Results saved to {}", RESULTS_FILE);
    }

    /**
     * Runs the actor-based implementation and returns the execution time in milliseconds.
     */
    private static long runActorImplementation() throws Exception {
        logger.info("Running actor-based implementation...");

        // Create actor system
        ActorSystem system = new ActorSystem();

        // Create completion latch
        CountDownLatch completionLatch = new CountDownLatch(1);

        // Create atomic counter for processed messages
        AtomicInteger processedCount = new AtomicInteger(0);

        // Create and register actors with the system
        // First create the sink actor
        SinkActor sink = new SinkActor(system, "sink", completionLatch);
        registerActor(system, "sink", sink);

        // Create processor actor
        ProcessorActor processor = new ProcessorActor(system, "processor", sink.self());
        registerActor(system, "processor", processor);

        // Create source actor
        SourceActor source = new SourceActor(system, "source", processor.self(), NUM_MESSAGES);
        registerActor(system, "source", source);

        // Start timing
        long startTime = System.currentTimeMillis();

        // Start message generation
        source.tell(new StartMessage());

        // Wait for completion
        completionLatch.await();

        // Calculate execution time
        long executionTime = System.currentTimeMillis() - startTime;

        // Shutdown actor system
        system.shutdown();

        logger.info("Actor-based implementation completed in {} ms", executionTime);
        return executionTime;
    }

    /**
     * Helper method to register an actor with the system and start it.
     */
    private static <T extends Actor<?>> void registerActor(ActorSystem system, String actorId, T actor) {
        try {
            // Use reflection to access the private actors map in ActorSystem
            java.lang.reflect.Field actorsField = ActorSystem.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<String, Actor<?>> actors =
                    (ConcurrentHashMap<String, Actor<?>>) actorsField.get(system);

            // Register the actor
            actors.put(actorId, actor);

            // Start the actor
            actor.start();

            logger.debug("Registered and started actor: {}", actorId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to register actor: " + actorId, e);
        }
    }

    /**
     * Runs the thread-based implementation and returns the execution time in milliseconds.
     */
    private static long runThreadImplementation() throws Exception {
        logger.info("Running thread-based implementation...");

        // Create queues for communication between threads
        BlockingQueue<Integer> sourceToProcessorQueue = new LinkedBlockingQueue<>();
        BlockingQueue<Integer> processorToSinkQueue = new LinkedBlockingQueue<>();

        // Create completion latch
        CountDownLatch completionLatch = new CountDownLatch(1);

        // Create executor service
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        // Create atomic counter for tracking progress
        AtomicInteger processedCount = new AtomicInteger(0);

        // Create and start sink thread
        SinkThread sink = new SinkThread(processorToSinkQueue, completionLatch, NUM_MESSAGES);
        executor.submit(sink);

        // Create and start processor thread
        ProcessorThread processor = new ProcessorThread(sourceToProcessorQueue, processorToSinkQueue);
        executor.submit(processor);

        // Create and start source thread
        SourceThread source = new SourceThread(sourceToProcessorQueue, NUM_MESSAGES);

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
     * Runs the implementation using structured concurrency and returns the execution time in milliseconds.
     */
    private static long runStructuredConcurrencyImplementation() throws Exception {
        logger.info("Running structured concurrency implementation...");

        // Create completion latch
        CountDownLatch completionLatch = new CountDownLatch(1);

        // Create queues for communication between stages
        BlockingQueue<Integer> sourceToProcessorQueue = new LinkedBlockingQueue<>();
        BlockingQueue<Integer> processorToSinkQueue = new LinkedBlockingQueue<>();

        // Create atomic counter for tracking progress
        AtomicInteger processedCount = new AtomicInteger(0);

        // Start timing
        long startTime = System.currentTimeMillis();

        // Use structured concurrency to manage the pipeline
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            // Fork the sink task
            scope.fork(() -> {
                int received = 0;
                while (received < NUM_MESSAGES) {
                    try {
                        // Take a processed number from the input queue
                        Integer number = processorToSinkQueue.take();

                        // Count the received number
                        received++;

                        // Log progress periodically
                        if (received % (NUM_MESSAGES / 10) == 0) {
                            logger.info("Structured sink received {}% of messages", received * 100 / NUM_MESSAGES);
                        }

                        // Check if all messages have been received
                        if (received >= NUM_MESSAGES) {
                            logger.info("Structured sink received all {} messages", NUM_MESSAGES);
                            completionLatch.countDown();
                            break;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.error("Structured sink interrupted", e);
                        break;
                    }
                }
                return null;
            });

            // Fork the processor task
            scope.fork(() -> {
                int processed = 0;
                while (processed < NUM_MESSAGES) {
                    try {
                        // Take a number from the input queue
                        Integer number = sourceToProcessorQueue.take();

                        // Process the number (increment it)
                        int processedNumber = number + 1;

                        // Put the processed number in the output queue
                        processorToSinkQueue.put(processedNumber);

                        // Track progress
                        processed++;
                        if (processed % (NUM_MESSAGES / 10) == 0) {
                            logger.info("Structured processor processed {}% of messages", processed * 100 / NUM_MESSAGES);
                        }

                        // Check if all messages have been processed
                        if (processed >= NUM_MESSAGES) {
                            logger.info("Structured processor completed processing all messages");
                            break;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.error("Structured processor interrupted", e);
                        break;
                    }
                }
                return null;
            });

            // Fork the source task
            scope.fork(() -> {
                logger.info("Structured source starting to generate {} messages", NUM_MESSAGES);

                try {
                    // Generate messages
                    for (int i = 0; i < NUM_MESSAGES; i++) {
                        sourceToProcessorQueue.put(i);

                        // Log progress periodically
                        if ((i + 1) % (NUM_MESSAGES / 10) == 0) {
                            logger.info("Structured source generated {}% of messages", (i + 1) * 100 / NUM_MESSAGES);
                        }
                    }

                    logger.info("Structured source completed generating all messages");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Structured source interrupted", e);
                }
                return null;
            });

            // Wait for completion latch
            completionLatch.await();

            // Join all tasks
            scope.join();

            // Check for failures
            scope.throwIfFailed();
        }

        // Calculate execution time
        long executionTime = System.currentTimeMillis() - startTime;

        logger.info("Structured concurrency implementation completed in {} ms", executionTime);
        return executionTime;
    }

    // ===== Actor-based implementation =====

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
    private static class CompletionMessage {
    }

    /**
     * Source actor that generates numbers and sends them to the processor.
     */
    public static class SourceActor extends Actor<Object> {
        private final Pid processorPid;
        private final int numMessages;

        public SourceActor(ActorSystem system, String actorId, Pid processorPid, int numMessages) {
            super(system, actorId);
            this.processorPid = processorPid;
            this.numMessages = numMessages;
        }

        @Override
        protected void receive(Object message) {
            if (message instanceof StartMessage) {
                logger.info("Source actor starting to generate {} messages", numMessages);

                // Generate messages in batches for better performance
                for (int i = 0; i < numMessages; i++) {
                    processorPid.tell(new NumberMessage(i));

                    // Log progress periodically
                    if ((i + 1) % (numMessages / 10) == 0) {
                        logger.info("Source actor generated {}% of messages", (i + 1) * 100 / numMessages);
                    }
                }

                logger.info("Source actor completed generating all messages");
            }
        }
    }

    /**
     * Processor actor that increments numbers and forwards them to the sink.
     */
    public static class ProcessorActor extends Actor<NumberMessage> {
        private final Pid sinkPid;
        private int processedCount = 0;

        public ProcessorActor(ActorSystem system, String actorId, Pid sinkPid) {
            super(system, actorId);
            this.sinkPid = sinkPid;
        }

        @Override
        protected void receive(NumberMessage message) {
            // Process the message (increment the number)
            int processedNumber = message.getNumber() + 1;

            // Forward to sink
            sinkPid.tell(new NumberMessage(processedNumber));

            // Track progress
            processedCount++;
            if (processedCount % (NUM_MESSAGES / 10) == 0) {
                logger.info("Processor actor processed {}% of messages", processedCount * 100 / NUM_MESSAGES);
            }
        }
    }

    /**
     * Sink actor that receives processed numbers and counts them.
     */
    public static class SinkActor extends Actor<NumberMessage> {
        private final CountDownLatch completionLatch;
        private int receivedCount = 0;

        public SinkActor(ActorSystem system, String actorId, CountDownLatch completionLatch) {
            super(system, actorId);
            this.completionLatch = completionLatch;
        }

        @Override
        protected void receive(NumberMessage message) {
            // Process the received number (just count it)
            receivedCount++;

            // Check if all messages have been received
            if (receivedCount == NUM_MESSAGES) {
                logger.info("Sink actor received all {} messages", NUM_MESSAGES);
                completionLatch.countDown();
            } else if (receivedCount % (NUM_MESSAGES / 10) == 0) {
                logger.info("Sink actor received {}% of messages", receivedCount * 100 / NUM_MESSAGES);
            }
        }
    }

    // ===== Thread-based implementation =====

    /**
     * Source thread that generates numbers and puts them in a queue.
     */
    private static class SourceThread implements Runnable {
        private final BlockingQueue<Integer> outputQueue;
        private final int numMessages;

        public SourceThread(BlockingQueue<Integer> outputQueue, int numMessages) {
            this.outputQueue = outputQueue;
            this.numMessages = numMessages;
        }

        @Override
        public void run() {
            try {
                logger.info("Source thread starting to generate {} messages", numMessages);

                for (int i = 0; i < numMessages; i++) {
                    outputQueue.put(i);

                    // Log progress periodically
                    if ((i + 1) % (numMessages / 10) == 0) {
                        logger.info("Source thread generated {}% of messages", (i + 1) * 100 / numMessages);
                    }
                }

                logger.info("Source thread completed generating all messages");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Source thread interrupted", e);
            }
        }
    }

    /**
     * Processor thread that takes numbers from input queue, increments them, and puts them in output queue.
     */
    private static class ProcessorThread implements Runnable {
        private final BlockingQueue<Integer> inputQueue;
        private final BlockingQueue<Integer> outputQueue;
        private int processedCount = 0;

        public ProcessorThread(BlockingQueue<Integer> inputQueue, BlockingQueue<Integer> outputQueue) {
            this.inputQueue = inputQueue;
            this.outputQueue = outputQueue;
        }

        @Override
        public void run() {
            try {
                logger.info("Processor thread started");

                while (true) {
                    // Take a number from the input queue
                    Integer number = inputQueue.take();

                    // Process the number (increment it)
                    int processedNumber = number + 1;

                    // Put the processed number in the output queue
                    outputQueue.put(processedNumber);

                    // Track progress
                    processedCount++;
                    if (processedCount % (NUM_MESSAGES / 10) == 0) {
                        logger.info("Processor thread processed {}% of messages", processedCount * 100 / NUM_MESSAGES);
                    }

                    // Check if all messages have been processed
                    if (processedCount >= NUM_MESSAGES) {
                        logger.info("Processor thread completed processing all messages");
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Processor thread interrupted", e);
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
        private int receivedCount = 0;

        public SinkThread(BlockingQueue<Integer> inputQueue, CountDownLatch completionLatch, int expectedMessages) {
            this.inputQueue = inputQueue;
            this.completionLatch = completionLatch;
            this.expectedMessages = expectedMessages;
        }

        @Override
        public void run() {
            try {
                logger.info("Sink thread started");

                while (true) {
                    // Take a processed number from the input queue
                    inputQueue.take();

                    // Count the received number
                    receivedCount++;

                    // Check if all messages have been received
                    if (receivedCount >= expectedMessages) {
                        logger.info("Sink thread received all {} messages", expectedMessages);
                        completionLatch.countDown();
                        break;
                    } else if (receivedCount % (expectedMessages / 10) == 0) {
                        logger.info("Sink thread received {}% of messages", receivedCount * 100 / expectedMessages);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Sink thread interrupted", e);
            }
        }
    }
}
