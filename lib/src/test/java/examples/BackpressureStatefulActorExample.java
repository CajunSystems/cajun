///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.cajunsystems:cajun:0.3.1
//JAVA 21+
//PREVIEW

package examples;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.StatefulActor;
import com.cajunsystems.backpressure.BackpressureSendOptions;
import com.cajunsystems.backpressure.BackpressureStrategy;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.handler.StatefulHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example demonstrating the use of StatefulActor with backpressure features for high-performance
 * scenarios with flow control and state management.
 */
public class BackpressureStatefulActorExample {
    private static final Logger logger = LoggerFactory.getLogger(BackpressureStatefulActorExample.class);

    public static void main(String[] args) throws Exception {
        // Create an actor system
        ActorSystem system = new ActorSystem(new ThreadPoolFactory(), new BackpressureConfig());
        
        // Create a stateful processor actor with backpressure enabled using the new interface-based approach
        // Initial capacity of 100 and max of 10,000
        BackpressureConfig backpressureConfig = new BackpressureConfig()
            .setWarningThreshold(0.7f)
            .setRecoveryThreshold(0.3f)
            .setMaxCapacity(10000);
            
        // Create the processor state
        CounterState initialState = new CounterState(0);
        
        // Create the stateful actor using the new interface-based approach
        Pid processorPid = system.statefulActorOf(
            new StatefulHandler<CounterState, CounterMessage>() {
                private final AtomicInteger processedCount = new AtomicInteger(0);
                private long startTime = System.currentTimeMillis();
                
                @Override
                public CounterState receive(CounterMessage message, CounterState state, ActorContext context) {
                    try {
                        // Simulate varying processing times
                        int processed = processedCount.incrementAndGet();
                        
                        if (processed % 1000 == 0) {
                            // Every 1000th message, log progress
                            long elapsed = System.currentTimeMillis() - startTime;
                            double rate = processed * 1000.0 / elapsed;
                            logger.info("Processed {} messages at {} msg/sec", processed, String.format("%.2f", rate));
                        }
                        
                        // Simulate processing time (variable based on message count)
                        Thread.sleep(5 + (processed % 5));
                        
                        // Process the message based on operation
                        if ("increment".equals(message.getOperation())) {
                            return state.increment();
                        } else if ("decrement".equals(message.getOperation())) {
                            return state.decrement();
                        } else {
                            logger.warn("Unknown operation: {}", message.getOperation());
                            return state;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return state;
                    }
                }
            },
            initialState
        )
        .withBackpressureConfig(backpressureConfig)
        .withId("processor")
        .spawn();
        
        // Set up metrics reporting
        final AtomicInteger messagesAccepted = new AtomicInteger(0);
        final AtomicInteger messagesRejected = new AtomicInteger(0);
        
        // Register a backpressure callback to monitor metrics
        system.setBackpressureCallback(processorPid, metrics -> {
            logger.info("Backpressure metrics - Size: {}/{}, Rate: {} msg/s, Backpressure: {}, Fill ratio: {}", 
                    metrics.getCurrentSize(), metrics.getCapacity(), metrics.getProcessingRate(),
                    metrics.isBackpressureActive() ? "ACTIVE" : "inactive",
                    String.format("%.2f", metrics.getFillRatio()));
        });
        
        // Configure backpressure for the actor
        system.configureBackpressure(processorPid)
            .withStrategy(BackpressureStrategy.BLOCK)
            .apply();
            
        // Note: In a real application, you would configure retry strategies
        // using the appropriate API
        
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
                    // Try to send with backpressure awareness
                    if (system.tellWithOptions(processorPid, 
                            new CounterMessage("increment"), 
                            new BackpressureSendOptions())) {
                        messagesAccepted.incrementAndGet();
                        sent++;
                        
                        // Simulate varying production rates
                        if (sent % 10_000 == 0) {
                            logger.info("Sent {} messages so far", sent);
                        }
                    } else {
                        // Message rejected due to backpressure
                        messagesRejected.incrementAndGet();
                        
                        // Adaptive backoff based on backpressure metrics
                        if (system.isBackpressureActive(processorPid)) {
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
        
        // Get final state - in a real application, you would use ask pattern to retrieve state
        // For this example, we'll just log a completion message
        logger.info("Processing completed");
        
        // Shutdown
        system.shutdown();
        
        logger.info("Example completed");
    }
    
    /**
     * A stateful counter message.
     */
    public static class CounterMessage implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String operation;
        
        public CounterMessage(String operation) {
            this.operation = operation;
        }
        
        public String getOperation() {
            return operation;
        }
    }
    
    /**
     * A stateful counter state.
     */
    public static class CounterState implements Serializable {
        private static final long serialVersionUID = 1L;
        private final int value;
        
        public CounterState(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
        
        public CounterState increment() {
            return new CounterState(value + 1);
        }
        
        public CounterState decrement() {
            return new CounterState(value - 1);
        }
    }
    
    /**
     * A stateful processor actor that demonstrates backpressure handling.
     * This actor processes messages at a controlled rate to show
     * how backpressure affects the system.
     */
    public static class ProcessorActor extends StatefulActor<CounterState, CounterMessage> {
        private static final Logger logger = LoggerFactory.getLogger(ProcessorActor.class);
        private final AtomicInteger processedCount = new AtomicInteger(0);
        private long startTime = 0;
        
        public ProcessorActor(ActorSystem system, String actorId) {
            // Create with backpressure enabled, initial capacity 100, max capacity 10,000
            super(system, actorId, new CounterState(0), 
                  new BackpressureConfig()
                      .setWarningThreshold(0.6f)
                      .setCriticalThreshold(0.8f)
                      .setRecoveryThreshold(0.4f)
                      .setMaxCapacity(10_000));
        }
        
        @Override
        protected void preStart() {
            super.preStart();
            startTime = System.currentTimeMillis();
            logger.info("ProcessorActor starting");
        }
        
        @Override
        protected CounterState processMessage(CounterState state, CounterMessage message) {
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
                
                // Process the message
                if ("increment".equals(message.getOperation())) {
                    return state.increment();
                } else if ("decrement".equals(message.getOperation())) {
                    return state.decrement();
                }
                
                return state;
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Processing interrupted", e);
            }
        }
        
        @Override
        protected void postStop() {
            super.postStop();
            logger.info("ProcessorActor stopping after processing {} messages", 
                    processedCount.get());
        }
    }
}
