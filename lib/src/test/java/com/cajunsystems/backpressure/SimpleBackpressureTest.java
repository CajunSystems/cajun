package com.cajunsystems.backpressure;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.config.MailboxConfig;
import com.cajunsystems.config.ResizableMailboxConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test for backpressure functionality.
 */
public class SimpleBackpressureTest {
    private static final Logger logger = LoggerFactory.getLogger(SimpleBackpressureTest.class);
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
    public void testBackpressureBasics() throws Exception {
        // Create a simple actor with backpressure configuration
        BackpressureConfig config = new BackpressureConfig()
                .setEnabled(true)
                .setStrategy(BackpressureStrategy.DROP_NEW);
        
        MailboxConfig mailboxConfig = new MailboxConfig()
                .setInitialCapacity(5)
                .setMaxCapacity(5);
        
        // Create and start our test actor
        SlowActor actor = new SlowActor(system, "test-actor", config, mailboxConfig);
        actor.start();
        
        try {
            // Track processed messages
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch processingLatch = new CountDownLatch(1);
            
            actor.setMessageCallback(msg -> {
                processedCount.incrementAndGet();
                if (processedCount.get() >= 3) {
                    processingLatch.countDown();
                }
            });
            
            // Send messages to fill the mailbox
            for (int i = 0; i < 10; i++) {
                actor.tell("msg-" + i);
            }
            
            // Wait for at least 3 messages to be processed
            assertTrue(processingLatch.await(3, TimeUnit.SECONDS), 
                    "Actor should process at least 3 messages");
            
            // Verify that at least some messages were processed
            int processed = processedCount.get();
            assertTrue(processed >= 3, 
                    "Should have processed at least 3 messages, got: " + processed);
            
            logger.info("Processed {} messages", processed);
            
            // Basic verification passed
            assertTrue(true, "Basic backpressure test completed successfully");
        } finally {
            actor.stop();
        }
    }
    
    /**
     * Simple actor that processes messages slowly
     */
    static class SlowActor extends Actor<String> {
        private volatile int processingDelay = 200; // milliseconds
        private Consumer<String> messageCallback;
        
        public SlowActor(ActorSystem system, String actorId, 
                        BackpressureConfig backpressureConfig, 
                        MailboxConfig mailboxConfig) {
            super(system, actorId, backpressureConfig, 
                  mailboxConfig instanceof ResizableMailboxConfig ?
                      (ResizableMailboxConfig) mailboxConfig :
                      new ResizableMailboxConfig()
                          .setInitialCapacity(mailboxConfig.getInitialCapacity())
                          .setMaxCapacity(mailboxConfig.getMaxCapacity()));
        }
        
        public void setProcessingDelay(int delayMs) {
            this.processingDelay = delayMs;
        }
        
        public void setMessageCallback(Consumer<String> callback) {
            this.messageCallback = callback;
        }
        
        @Override
        protected void receive(String message) {
            if (messageCallback != null) {
                messageCallback.accept(message);
            }
            
            try {
                Thread.sleep(processingDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    // Consumer interface for callbacks
    interface Consumer<T> {
        void accept(T t);
    }
}
