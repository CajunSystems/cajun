package examples;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.cajun.Actor;
import systems.cajun.ActorSystem;
import systems.cajun.Pid;
import systems.cajun.config.BackpressureConfig;
import systems.cajun.backpressure.BackpressureStrategy;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.StructuredTaskScope;

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
    private static final Integer POISON_PILL = Integer.MIN_VALUE;

    public static void main(String[] args) throws Exception {
        logger.info("Starting Actor vs Threads performance comparison");

        // Create a results file
        Path resultsPath = Paths.get(RESULTS_FILE);
        Files.deleteIfExists(resultsPath);
        Files.createFile(resultsPath);

        // Run actor-based implementation without backpressure
        long actorTimeWithoutBP = runActorImplementation(false);

        // Run actor-based implementation with backpressure
        long actorTimeWithBP = runActorImplementation(true);

        // Run thread-based implementation
        long threadTime = runThreadImplementation();

        // Run structured concurrency implementation
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
        logger.info("Running actor-based implementation with backpressure {}...", 
                    enableBackpressure ? "enabled" : "disabled");

        // Create actor system
        ActorSystem system = new ActorSystem();

        // Create completion latch
        CountDownLatch completionLatch = new CountDownLatch(1);

        // Create backpressure configuration if needed
        BackpressureConfig backpressureConfig = null;
        if (enableBackpressure) {
            // Configure with a small mailbox to trigger backpressure
            backpressureConfig = new BackpressureConfig.Builder()
                .warningThreshold(0.7f)
                .recoveryThreshold(0.3f)
                .strategy(BackpressureStrategy.BLOCK)
                .maxCapacity(1000) // Set a relatively small mailbox capacity to demonstrate backpressure
                .build();
        }

        // Register the SinkActor class with the system
        Pid sinkPid;
        if (enableBackpressure) {
            sinkPid = system.register(SinkActor.class, "sink", backpressureConfig);
        } else {
            sinkPid = system.register(SinkActor.class, "sink");
        }
        
        // Initialize the SinkActor with our completionLatch
        sinkPid.tell(new InitSinkMessage(completionLatch));

        // Register the ProcessorActor class with the system
        Pid processorPid;
        if (enableBackpressure) {
            processorPid = system.register(ProcessorActor.class, "processor", backpressureConfig);
        } else {
            processorPid = system.register(ProcessorActor.class, "processor");
        }
        
        // Initialize the ProcessorActor with the sink's PID
        processorPid.tell(new InitProcessorMessage(sinkPid));

        // Register the SourceActor class with the system
        Pid sourcePid;
        if (enableBackpressure) {
            sourcePid = system.register(SourceActor.class, "source", backpressureConfig);
        } else {
            sourcePid = system.register(SourceActor.class, "source");
        }
        
        // Initialize the SourceActor with the processor's PID and message count
        sourcePid.tell(new InitSourceMessage(processorPid, NUM_MESSAGES));

        // Start timing
        long startTime = System.currentTimeMillis();

        // Start message generation
        sourcePid.tell(new StartMessage());

        // Wait for completion
        completionLatch.await();

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

        // Create queues for communication between threads
        BlockingQueue<Integer> sourceToProcessorQueue = new ArrayBlockingQueue<>(10000);
        BlockingQueue<Integer> processorToSinkQueue = new ArrayBlockingQueue<>(10000);

        // Create completion latch
        CountDownLatch completionLatch = new CountDownLatch(1);

        // Create executor service
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        // Create atomic counter for tracking progress
        AtomicInteger processedCount = new AtomicInteger(0);

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
    private static long runStructuredConcurrencyImplementation() throws Exception {
        logger.info("Running structured concurrency implementation...");

        // Create queues for communication between tasks
        BlockingQueue<Integer> sourceToProcessorQueue = new ArrayBlockingQueue<>(10000);
        BlockingQueue<Integer> processorToSinkQueue = new ArrayBlockingQueue<>(10000);

        // Create completion latch
        CountDownLatch completionLatch = new CountDownLatch(1);

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
    private static class InitProcessorMessage {
        private final Pid sinkPid;

        public InitProcessorMessage(Pid sinkPid) {
            this.sinkPid = sinkPid;
        }

        public Pid getSinkPid() {
            return sinkPid;
        }
    }

    /**
     * Message to initialize the SourceActor with a processor PID and message count.
     */
    private static class InitSourceMessage {
        private final Pid processorPid;
        private final int numMessages;

        public InitSourceMessage(Pid processorPid, int numMessages) {
            this.processorPid = processorPid;
            this.numMessages = numMessages;
        }

        public Pid getProcessorPid() {
            return processorPid;
        }

        public int getNumMessages() {
            return numMessages;
        }
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
    private static class CompletionMessage {
    }

    /**
     * Source actor that generates numbers and sends them to the processor.
     */
    public static class SourceActor extends Actor<Object> {
        private Pid processorPid;
        private int numMessages;

        // Add a constructor that takes only system and actorId
        public SourceActor(ActorSystem system, String actorId) {
            super(system, actorId);
            this.processorPid = null;
            this.numMessages = 0;
        }

        @Override
        protected void receive(Object message) {
            if (message instanceof InitSourceMessage initMessage) {
                this.processorPid = initMessage.getProcessorPid();
                this.numMessages = initMessage.getNumMessages();
            } else if (message instanceof StartMessage) {
                // Only proceed if processorPid is available
                if (processorPid == null) {
                    logger.warn("Source actor cannot generate messages: processor PID is null");
                    return;
                }
                
                logger.info("Source actor starting to generate {} messages", numMessages);

                // Generate messages in batches for better performance and to prevent mailbox overflow
                for (int i = 0; i < numMessages; i += BATCH_SIZE) {
                    int batchEnd = Math.min(i + BATCH_SIZE, numMessages);
                    
                    // Send a batch of messages
                    for (int j = i; j < batchEnd; j++) {
                        processorPid.tell(new NumberMessage(j));
                    }
                    
                    // Log progress periodically
                    if ((i + BATCH_SIZE) % (numMessages / 10) <= BATCH_SIZE) {
                        int percentage = Math.min((i + BATCH_SIZE) * 100 / numMessages, 100);
                        logger.info("Source actor generated {}% of messages", percentage);
                    }
                    
                    // Small delay between batches to prevent overwhelming the processor
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                logger.info("Source actor completed generating all messages");
                
                // Send completion message to processor to signal end of data
                processorPid.tell(new CompletionMessage());
            }
        }
    }

    /**
     * Processor actor that increments numbers and forwards them to the sink.
     */
    public static class ProcessorActor extends Actor<Object> {
        private Pid sinkPid;
        private int processedCount = 0;

        // Add a constructor that takes only system and actorId
        public ProcessorActor(ActorSystem system, String actorId) {
            super(system, actorId);
            this.sinkPid = null; // Default value when created by the system
        }

        @Override
        protected void receive(Object message) {
            if (message instanceof InitProcessorMessage initMessage) {
                this.sinkPid = initMessage.getSinkPid();
            } else if (message instanceof NumberMessage numberMessage) {
                // Process the message (increment the number)
                int processedNumber = numberMessage.getNumber() + 1;

                // Forward to sink if sinkPid is available
                if (sinkPid != null) {
                    sinkPid.tell(new NumberMessage(processedNumber));
                }
                
                // Track progress
                processedCount++;
                if (processedCount % (NUM_MESSAGES / 10) == 0) {
                    logger.info("Processor actor processed {}% of messages", processedCount * 100 / NUM_MESSAGES);
                }
            } else if (message instanceof CompletionMessage) {
                // Forward completion message to sink if sinkPid is available
                logger.info("Processor actor completed processing all messages");
                if (sinkPid != null) {
                    sinkPid.tell(new CompletionMessage());
                }
            }
        }
    }

    /**
     * Sink actor that receives processed numbers and counts them.
     */
    public static class SinkActor extends Actor<Object> {
        private CountDownLatch completionLatch;
        private int receivedCount = 0;

        // Add a constructor that takes only system and actorId
        public SinkActor(ActorSystem system, String actorId) {
            super(system, actorId);
            this.completionLatch = null; // Default value when created by the system
        }

        @Override
        protected void receive(Object message) {
            if (message instanceof InitSinkMessage initMessage) {
                this.completionLatch = initMessage.getCompletionLatch();
            } else if (message instanceof NumberMessage) {
                // Process the received number (just count it)
                receivedCount++;

                // Log progress periodically
                if (receivedCount % (NUM_MESSAGES / 10) == 0) {
                    logger.info("Sink actor received {}% of messages", receivedCount * 100 / NUM_MESSAGES);
                }
            } else if (message instanceof CompletionMessage) {
                // Signal completion
                logger.info("Sink actor received completion message. Processed {} messages.", receivedCount);
                if (completionLatch != null) {
                    completionLatch.countDown();
                }
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
