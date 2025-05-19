package examples;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.cajun.Actor;
import systems.cajun.ActorSystem;
import systems.cajun.Pid;
import systems.cajun.backpressure.BackpressureStatus;
import systems.cajun.backpressure.BackpressureSendOptions;
import systems.cajun.backpressure.BackpressureState;
import systems.cajun.config.BackpressureConfig;
import systems.cajun.config.MailboxConfig;
import systems.cajun.config.ResizableMailboxConfig;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example demonstrating the use of Actor with backpressure features for high-performance
 * scenarios with flow control.
 */
public class BackpressureActorExample {
    private static final Logger logger = LoggerFactory.getLogger(BackpressureActorExample.class);

    public static void main(String[] args) throws Exception {
        // Create an actor system optimized for high throughput
        ActorSystem system = new ActorSystem();
        
        // Create a processor actor with backpressure enabled and custom mailbox configuration
        BackpressureConfig backpressureConfig = new BackpressureConfig();
        ResizableMailboxConfig mailboxConfig = new ResizableMailboxConfig()
            .setInitialCapacity(100)
            .setMaxCapacity(10_000);
        Pid processorPid = system.register(ProcessorActor.class, "processor", backpressureConfig, mailboxConfig);
        ProcessorActor processor = (ProcessorActor) system.getActor(processorPid);
        
        // Set up metrics reporting
        final AtomicInteger messagesAccepted = new AtomicInteger(0);
        final AtomicInteger messagesRejected = new AtomicInteger(0);
        
        // Register a backpressure callback to monitor metrics
        system.configureBackpressure(processorPid)
            .withCallback(event -> {
                logger.info("Backpressure metrics - Size: {}/{}, Rate: {} msg/s, Backpressure: {}, Fill ratio: {}", 
                        event.getCurrentSize(), event.getCapacity(), event.getProcessingRate(),
                        event.getState().name(),
                        String.format("%.2f", event.getFillRatio()));
            });
        
        // Completion latch
        CountDownLatch completionLatch = new CountDownLatch(1);
        
        // Start a producer thread
        Thread producerThread = new Thread(() -> {
            try {
                int totalMessages = 100_000;
                int sent = 0;
                
                logger.info("Starting to send {} messages", totalMessages);
                long startTime = System.currentTimeMillis();
                
                while (sent < totalMessages) {
                    try {
                        // Send message directly
                        processor.tell("Message-" + sent);
                        messagesAccepted.incrementAndGet();
                        sent++;
                        
                        // Simulate varying production rates
                        if (sent % 10_000 == 0) {
                            logger.info("Sent {} messages so far", sent);
                        }
                    } catch (Exception e) {
                        // Message rejected due to backpressure
                        messagesRejected.incrementAndGet();
                        
                        // Check backpressure status
                        boolean backpressureActive = system.isBackpressureActive(processorPid);
                        if (backpressureActive) {
                            // Longer backoff when backpressure is active
                            Thread.sleep(10);
                        } else {
                            // Short yield when no backpressure
                            Thread.yield();
                        }
                    }
                }
                
                long duration = System.currentTimeMillis() - startTime;
                logger.info("Finished sending {} messages in {} ms", totalMessages, duration);
                logger.info("Messages accepted: {}, rejected: {}", 
                        messagesAccepted.get(), messagesRejected.get());
                
                // Signal completion
                completionLatch.countDown();
                
            } catch (Exception e) {
                logger.error("Producer error", e);
            }
        });
        
        // Start producer
        producerThread.start();
        
        // Wait for completion
        completionLatch.await(2, TimeUnit.MINUTES);
        
        // Allow time for final processing
        Thread.sleep(5000);
        
        // Shutdown
        system.shutdown();
        
        logger.info("Example completed");
    }
    
    /**
     * A processor actor that demonstrates backpressure handling.
     * This actor processes messages at a controlled rate to show
     * how backpressure affects the system.
     */
    public static class ProcessorActor extends Actor<String> {
        private static final Logger logger = LoggerFactory.getLogger(ProcessorActor.class);
        private final AtomicInteger processedCount = new AtomicInteger(0);
        private long startTime = 0;
        
        /**
         * Constructor with separate backpressure and mailbox configuration
         * 
         * @param system The actor system
         * @param actorId The actor ID
         * @param backpressureConfig The backpressure configuration
         * @param mailboxConfig The mailbox configuration
         */
        public ProcessorActor(ActorSystem system, String actorId, BackpressureConfig backpressureConfig, ResizableMailboxConfig mailboxConfig) {
            super(system, actorId, backpressureConfig, mailboxConfig);
        }
        
        /**
         * Default constructor required by ActorSystem
         * 
         * @param system The actor system
         * @param actorId The actor ID
         */
        public ProcessorActor(ActorSystem system, String actorId) {
            super(system, actorId, 
                  new BackpressureConfig(), 
                  new ResizableMailboxConfig().setInitialCapacity(100).setMaxCapacity(10_000));
        }
        
        /**
         * @deprecated Use the constructor with separate BackpressureConfig and ResizableMailboxConfig instead
         */
        @Deprecated
        public ProcessorActor(ActorSystem system, String actorId, boolean enableBackpressure, int initialCapacity, int maxCapacity) {
            super(system, actorId, 
                enableBackpressure ? system.getBackpressureConfig() : null, 
                new ResizableMailboxConfig().setInitialCapacity(initialCapacity).setMaxCapacity(maxCapacity));
        }
        
        /**
         * @deprecated Use the constructor with separate BackpressureConfig and ResizableMailboxConfig instead
         */
        @Deprecated
        public ProcessorActor(ActorSystem system, String actorId, BackpressureConfig backpressureConfig, int initialCapacity, int maxCapacity) {
            super(system, actorId, backpressureConfig, 
                new ResizableMailboxConfig().setInitialCapacity(initialCapacity).setMaxCapacity(maxCapacity));
        }
        
        @Override
        protected void preStart() {
            startTime = System.currentTimeMillis();
            logger.info("ProcessorActor starting");
        }
        
        @Override
        protected void receive(String message) {
            try {
                // Simulate varying processing times
                int processed = processedCount.incrementAndGet();
                
                if (processed % 1000 == 0) {
                    // Every 1000th message, log progress
                    long elapsed = System.currentTimeMillis() - startTime;
                    double rate = processed * 1000.0 / elapsed;
                    logger.info("Processed {} messages at {} msg/sec", processed, String.format("%.2f", rate));
                }
                
                // Simulate processing work with variable latency
                if (processed % 100 == 0) {
                    // Occasionally take longer to process
                    Thread.sleep(5);
                } else {
                    // Normal processing time
                    Thread.sleep(1);
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        @Override
        protected void postStop() {
            logger.info("ProcessorActor stopping after processing {} messages", 
                    processedCount.get());
        }
    }
}
