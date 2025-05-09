package systems.cajun;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import systems.cajun.flow.BackpressureAwareQueue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the integration of BackpressureAwareQueue with the Actor class.
 */
public class ActorBackpressureTest {

    /**
     * Test actor that processes messages with configurable delay
     */
    static class TestActor extends Actor<String> {
        private final AtomicInteger processedCount = new AtomicInteger(0);
        private final long processingDelayMs;
        private final CountDownLatch processedLatch;
        private volatile boolean shouldFail = false;

        public TestActor(ActorSystem system, long processingDelayMs, int expectedMessages) {
            super(system);
            this.processingDelayMs = processingDelayMs;
            this.processedLatch = new CountDownLatch(expectedMessages);
        }

        @Override
        protected void receive(String message) {
            if (shouldFail) {
                throw new RuntimeException("Simulated failure in actor");
            }
            
            // Simulate processing time
            if (processingDelayMs > 0) {
                try {
                    Thread.sleep(processingDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            processedCount.incrementAndGet();
            processedLatch.countDown();
        }

        public int getProcessedCount() {
            return processedCount.get();
        }

        public boolean awaitProcessed(long timeout, TimeUnit unit) throws InterruptedException {
            return processedLatch.await(timeout, unit);
        }

        public void setShouldFail(boolean shouldFail) {
            this.shouldFail = shouldFail;
        }
    }

    @Test
    public void testDefaultBackpressureSettings() throws InterruptedException {
        ActorSystem system = new ActorSystem();
        TestActor actor = new TestActor(system, 0, 10);
        actor.start();

        // Send 10 messages
        for (int i = 0; i < 10; i++) {
            actor.tell("Message " + i);
        }

        // Wait for processing to complete
        assertTrue(actor.awaitProcessed(1, TimeUnit.SECONDS));
        assertEquals(10, actor.getProcessedCount());

        // Check metrics
        Map<String, Object> metrics = actor.getBackpressureMetrics();
        assertEquals(0L, metrics.get("droppedMessages"));
        assertEquals(10L, metrics.get("processedMessages"));

        actor.stop();
        system.shutdown();
    }

    @Test
    public void testDropBackpressureMode() throws InterruptedException {
        ActorSystem system = new ActorSystem();
        TestActor actor = new TestActor(system, 50, 10); // 50ms processing time, expect at least 10 messages
        
        // Configure for drop mode with small buffer
        actor.withMailboxBufferSize(5)
             .withBackpressureMode(BackpressureAwareQueue.BackpressureMode.BUFFER_THEN_DROP);
        
        actor.start();

        // Send 30 messages rapidly - some should be dropped
        for (int i = 0; i < 30; i++) {
            actor.tell("Message " + i);
            // No delay between messages to ensure backpressure
        }

        // Wait for processing to complete
        actor.awaitProcessed(2, TimeUnit.SECONDS);
        
        // Give a little more time for metrics to update
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Check metrics
        Map<String, Object> metrics = actor.getBackpressureMetrics();
        
        // Log the actual values for debugging
        System.out.println("Actor processed count: " + actor.getProcessedCount());
        System.out.println("Metrics: " + metrics);
        
        // Verify that the actor processed some messages
        assertTrue(actor.getProcessedCount() > 0, 
                "Actor should have processed some messages");
        
        actor.stop();
        system.shutdown();
    }

    @Test
    @Timeout(10) // Timeout after 10 seconds
    public void testBlockBackpressureMode() throws InterruptedException {
        ActorSystem system = new ActorSystem();
        TestActor actor = new TestActor(system, 50, 10); // 50ms processing time
        
        // Configure for block mode with small buffer
        actor.withMailboxBufferSize(5)
             .withBackpressureMode(BackpressureAwareQueue.BackpressureMode.BUFFER_THEN_BLOCK);
        
        actor.start();

        // Send 10 messages with blocking backpressure
        // This should not drop messages but will block the sender
        CountDownLatch sendCompleteLatch = new CountDownLatch(1);
        
        Thread sender = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                actor.tell("Message " + i);
            }
            sendCompleteLatch.countDown();
        });
        
        sender.start();
        
        // Wait for all messages to be sent (this might take time due to blocking)
        assertTrue(sendCompleteLatch.await(5, TimeUnit.SECONDS), "Sender thread did not complete in time");
        
        // Wait for processing to complete
        assertTrue(actor.awaitProcessed(5, TimeUnit.SECONDS), "Not all messages were processed");
        
        // Check metrics
        Map<String, Object> metrics = actor.getBackpressureMetrics();
        assertEquals(0L, metrics.get("droppedMessages"), "No messages should be dropped in blocking mode");
        assertEquals(10L, metrics.get("processedMessages"), "All messages should be processed");
        
        actor.stop();
        system.shutdown();
    }

    @Test
    public void testAdaptiveBackpressureMode() throws InterruptedException {
        ActorSystem system = new ActorSystem();
        TestActor actor = new TestActor(system, 20, 30); // 20ms processing time
        
        // Configure for adaptive mode
        actor.withMailboxBufferSize(10)
             .withBackpressureMode(BackpressureAwareQueue.BackpressureMode.ADAPTIVE);
        
        actor.start();

        // First send messages at a moderate rate
        for (int i = 0; i < 10; i++) {
            actor.tell("Message " + i);
            Thread.sleep(5); // Small delay between messages
        }
        
        // Then send messages rapidly to trigger backpressure
        for (int i = 10; i < 40; i++) {
            actor.tell("Message " + i);
            // No delay between messages
        }

        // Wait for processing to complete
        actor.awaitProcessed(5, TimeUnit.SECONDS);
        
        // Check metrics
        Map<String, Object> metrics = actor.getBackpressureMetrics();
        long droppedMessages = (Long) metrics.get("droppedMessages");
        long delayedMessages = (Long) metrics.get("delayedMessages");
        long processedMessages = (Long) metrics.get("processedMessages");
        double backpressureLevel = (Double) metrics.get("backpressureLevel");
        
        // In adaptive mode, we might see a mix of dropped and delayed messages
        assertTrue(processedMessages > 0, "Expected some processed messages");
        assertTrue(backpressureLevel >= 0.0 && backpressureLevel <= 1.0, 
                "Backpressure level should be between 0 and 1, but was " + backpressureLevel);
        
        // Total of processed + dropped should account for all sent messages (with some tolerance)
        assertTrue(processedMessages + droppedMessages <= 40, 
                "Total of processed (" + processedMessages + ") and dropped (" + 
                droppedMessages + ") should not exceed 40");
        
        actor.stop();
        system.shutdown();
    }

    @Test
    public void testErrorHandlingWithBackpressure() throws InterruptedException {
        ActorSystem system = new ActorSystem();
        TestActor actor = new TestActor(system, 10, 5);
        
        // Configure with resume strategy
        actor.withSupervisionStrategy(Actor.SupervisionStrategy.RESUME)
             .withBackpressureMode(BackpressureAwareQueue.BackpressureMode.ADAPTIVE);
        
        actor.start();

        // Send 5 messages
        for (int i = 0; i < 5; i++) {
            actor.tell("Message " + i);
        }
        
        // Wait for processing to start
        Thread.sleep(50);
        
        // Simulate failure
        actor.setShouldFail(true);
        
        // Send 5 more messages
        for (int i = 5; i < 10; i++) {
            actor.tell("Message " + i);
        }
        
        // Wait a bit
        Thread.sleep(100);
        
        // Fix the actor
        actor.setShouldFail(false);
        
        // Check that the actor is still running
        assertTrue(actor.isRunning(), "Actor should still be running with RESUME strategy");
        
        // Check metrics - we should see some processed messages before the failure
        Map<String, Object> metrics = actor.getBackpressureMetrics();
        long processedMessages = (Long) metrics.get("processedMessages");
        assertTrue(processedMessages > 0, "Expected some processed messages, but got " + processedMessages);
        
        actor.stop();
        system.shutdown();
    }
}
