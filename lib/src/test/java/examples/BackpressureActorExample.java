///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.cajunsystems:cajun:0.1.4
//JAVA 21+
//PREVIEW

package examples;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.config.MailboxConfig;
import com.cajunsystems.config.ResizableMailboxConfig;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        // Create an actor system optimized for high throughput, ensuring backpressure capabilities
        ActorSystem system = new ActorSystem(new ThreadPoolFactory(), new BackpressureConfig());
        
        // Create a processor actor with backpressure enabled and custom mailbox configuration
        BackpressureConfig backpressureConfig = new BackpressureConfig();
        ResizableMailboxConfig mailboxConfig = new ResizableMailboxConfig()
            .setInitialCapacity(100)
            .setMaxCapacity(10_000);
            
        // Create the processor actor using the new interface-based approach
        Pid processorPid = system.actorOf(new Handler<String>() {
            private final AtomicInteger processedCount = new AtomicInteger(0);
            private long startTime = 0;
            
            @Override
            public void preStart(ActorContext context) {
                startTime = System.currentTimeMillis();
                logger.info("ProcessorActor starting");
            }
            
            @Override
            public void receive(String message, ActorContext context) {
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
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            @Override
            public void postStop(ActorContext context) {
                logger.info("ProcessorActor stopping after processing {} messages", processedCount.get());
            }
        })
        .withId("processor")
        .withBackpressureConfig(backpressureConfig)
        .withMailboxConfig(mailboxConfig)
        .spawn();
        
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
                        // Send message to the processor actor
                        system.tell(processorPid, "Message-" + sent);
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
}
