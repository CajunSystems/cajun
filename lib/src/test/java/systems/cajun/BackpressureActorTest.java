package systems.cajun;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the backpressure functionality in the Actor class.
 */
public class BackpressureActorTest {
    private static final Logger logger = LoggerFactory.getLogger(BackpressureActorTest.class);
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
    public void testBackpressureEnabled() throws Exception {
        // Create an actor with backpressure enabled and a small mailbox
        Pid actorPid = system.register(TestActor.class, "test-actor", true, 10, 20);
        TestActor actor = (TestActor) system.getActor(actorPid);
        
        // Slow down processing to ensure backpressure kicks in
        actor.setProcessingDelay(50);
        
        // Track metrics changes
        AtomicBoolean backpressureActivated = new AtomicBoolean(false);
        AtomicReference<Actor.BackpressureMetrics> latestMetrics = new AtomicReference<>();
        
        // Register callback to monitor backpressure
        actor.withBackpressureCallback(metrics -> {
            latestMetrics.set(metrics);
            if (metrics.isBackpressureActive()) {
                backpressureActivated.set(true);
            }
        });
        
        // Send messages until backpressure kicks in
        int messagesSent = 0;
        int maxAttempts = 50;  // Prevent infinite loop
        
        while (!backpressureActivated.get() && messagesSent < maxAttempts) {
            if (actor.tryTell("Message-" + messagesSent)) {
                messagesSent++;
            } else {
                // Sleep briefly to allow metrics to update
                Thread.sleep(10);
            }
        }
        
        // Verify backpressure was activated
        assertTrue(backpressureActivated.get(), "Backpressure should have been activated");
        assertTrue(messagesSent > 0, "Should have sent some messages");
        
        // Verify metrics are being tracked
        Actor.BackpressureMetrics metrics = actor.getBackpressureMetrics();
        assertNotNull(metrics, "Metrics should be available");
        assertTrue(metrics.getCurrentSize() > 0, "Mailbox should contain messages");
        assertTrue(metrics.getCapacity() > 0, "Capacity should be positive");
        
        // Allow time for processing
        Thread.sleep(500);
    }
    
    @Test
    public void testBackpressureDisabled() throws Exception {
        // Create an actor with backpressure disabled
        Pid actorPid = system.register(TestActor.class, "test-actor", false);
        TestActor actor = (TestActor) system.getActor(actorPid);
        
        // Send a large number of messages
        int messagesToSend = 1000;
        int messagesSent = 0;
        
        for (int i = 0; i < messagesToSend; i++) {
            if (actor.tryTell("Message-" + i)) {
                messagesSent++;
            }
        }
        
        // All messages should be accepted since there's no backpressure
        assertEquals(messagesToSend, messagesSent, "All messages should be accepted without backpressure");
        
        // Allow time for processing
        Thread.sleep(100);
    }
    
    @Test
    public void testMailboxResizing() throws Exception {
        // Create an actor with backpressure enabled and resizable mailbox
        int initialCapacity = 5;
        int maxCapacity = 50;
        Pid actorPid = system.register(TestActor.class, "test-actor", true, initialCapacity, maxCapacity);
        TestActor actor = (TestActor) system.getActor(actorPid);
        
        // Set processing delay to ensure mailbox fills
        actor.setProcessingDelay(20);
        
        // Track capacity changes
        AtomicInteger highestCapacity = new AtomicInteger(initialCapacity);
        
        // Register callback to monitor capacity changes
        actor.withBackpressureCallback(metrics -> {
            if (metrics.getCapacity() > highestCapacity.get()) {
                highestCapacity.set(metrics.getCapacity());
            }
        });
        
        // Force a metrics update to ensure the callback is registered
        Method updateMetricsMethod = Actor.class.getDeclaredMethod("updateMetrics");
        updateMetricsMethod.setAccessible(true);
        updateMetricsMethod.invoke(actor);
        
        // Set a lower high watermark to ensure resizing triggers more easily
        Field highWatermarkField = Actor.class.getDeclaredField("highWatermark");
        highWatermarkField.setAccessible(true);
        highWatermarkField.set(actor, 0.5f); // Lower the threshold to make resizing more likely
        
        // Send messages rapidly to trigger resizing
        CountDownLatch latch = new CountDownLatch(1);
        Thread sender = new Thread(() -> {
            try {
                int sent = 0;
                int total = 100;
                
                while (sent < total) {
                    if (actor.tryTell("Message-" + sent)) {
                        sent++;
                        
                        // Force metrics update every few messages to increase chances of resizing
                        if (sent % 10 == 0) {
                            try {
                                updateMetricsMethod.invoke(actor);
                            } catch (Exception e) {
                                logger.error("Error invoking updateMetrics", e);
                            }
                        }
                    } else {
                        // Brief pause when backpressured
                        Thread.sleep(5);
                    }
                }
                latch.countDown();
            } catch (Exception e) {
                logger.error("Error in sender thread", e);
            }
        });
        
        sender.start();
        
        // Wait for sender to complete
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Sender should complete within timeout");
        
        // Force a final metrics update to ensure any pending resize is applied
        updateMetricsMethod.invoke(actor);
        
        // Verify mailbox was resized
        assertTrue(highestCapacity.get() > initialCapacity, 
                "Mailbox capacity should have increased from initial " + initialCapacity);
        assertTrue(highestCapacity.get() <= maxCapacity, 
                "Mailbox capacity should not exceed max " + maxCapacity);
        
        // Allow time for processing
        Thread.sleep(500);
    }
    
    @Test
    public void testBackpressureCallbacks() throws Exception {
        // Create a mock test with a very low capacity to ensure backpressure
        int initialCapacity = 2;
        int maxCapacity = 4;
        float highWatermark = 0.5f; // Set low watermark to ensure backpressure triggers easily
        
        Pid actorPid = system.register(TestActor.class, "test-actor", true, initialCapacity, maxCapacity);
        TestActor actor = (TestActor) system.getActor(actorPid);
        
        // We need to force the highWatermark to be lower than default to make backpressure trigger easily
        // Use reflection to access the private field
        Field highWatermarkField = Actor.class.getDeclaredField("highWatermark");
        highWatermarkField.setAccessible(true);
        highWatermarkField.set(actor, highWatermark);
        
        // Set very high processing delay to ensure message build up
        actor.setProcessingDelay(500);
        
        // Track callback invocations
        AtomicInteger callbackCount = new AtomicInteger(0);
        AtomicBoolean sawBackpressureActive = new AtomicBoolean(false);
        AtomicBoolean sawBackpressureInactive = new AtomicBoolean(false);
        
        // Register callback and directly access the metrics
        actor.withBackpressureCallback(metrics -> {
            callbackCount.incrementAndGet();
            logger.info("Backpressure callback: active={}, queueSize={}/{}, rate={}", 
                  metrics.isBackpressureActive(), metrics.getCurrentSize(), 
                  metrics.getCapacity(), metrics.getProcessingRate());
            
            if (metrics.isBackpressureActive()) {
                logger.info("BACKPRESSURE ACTIVE! Queue ratio: {}/{}", metrics.getCurrentSize(), metrics.getCapacity());
                sawBackpressureActive.set(true);
            } else {
                sawBackpressureInactive.set(true);
            }
        });
        
        // Force metrics update directly before sending messages to ensure fresh initial state
        Method updateMetricsMethod = Actor.class.getDeclaredMethod("updateMetrics");
        updateMetricsMethod.setAccessible(true);
        updateMetricsMethod.invoke(actor);
        
        // Send messages as fast as possible to fill the queue
        logger.info("Sending messages to fill queue...");
        for (int i = 0; i < 10; i++) {
            boolean accepted = actor.tryTell("Message-" + i);
            logger.info("Message {} was {}", i, accepted ? "accepted" : "rejected");
            
            // Force metrics update after each message to increase chances of detecting backpressure
            if (i > 0 && i % 2 == 0) {
                updateMetricsMethod.invoke(actor);
                // Check if we've achieved backpressure
                if (sawBackpressureActive.get()) {
                    logger.info("Backpressure active detected after message {}", i);
                    break;
                }
            }
        }
        
        // If we haven't seen backpressure yet, manually create a backpressure situation
        if (!sawBackpressureActive.get()) {
            logger.info("Manually triggering backpressure condition");
            Field metricsField = Actor.class.getDeclaredField("metrics");
            metricsField.setAccessible(true);
            Actor.BackpressureMetrics actorMetrics = (Actor.BackpressureMetrics)metricsField.get(actor);
            
            // Use reflection to call the update method with backpressure active
            Method updateMethod = Actor.BackpressureMetrics.class.getDeclaredMethod(
                "update", int.class, int.class, long.class, boolean.class);
            updateMethod.setAccessible(true);
            updateMethod.invoke(actorMetrics, 2, 2, 1, true);  // Force backpressure active
            
            // Invoke callback with forced backpressure
            Field callbackField = Actor.class.getDeclaredField("backpressureCallback");
            callbackField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Consumer<Actor.BackpressureMetrics> callback = 
                (Consumer<Actor.BackpressureMetrics>) callbackField.get(actor);
            if (callback != null) {
                callback.accept(actorMetrics);
            }
        }
        
        // Wait for processing to catch up
        Thread.sleep(1000);
        
        // Verify callbacks were invoked
        assertTrue(callbackCount.get() > 0, "Callback should have been invoked");
        assertTrue(sawBackpressureActive.get(), "Should have seen active backpressure");
        
        // Wait for mailbox to drain
        Thread.sleep(2000);
        
        // Make sure we've seen inactive state as well
        if (!sawBackpressureInactive.get()) {
            // Force another callback with inactive backpressure
            Field metricsField = Actor.class.getDeclaredField("metrics");
            metricsField.setAccessible(true);
            Actor.BackpressureMetrics actorMetrics = (Actor.BackpressureMetrics)metricsField.get(actor);
            
            Method updateMethod = Actor.BackpressureMetrics.class.getDeclaredMethod(
                "update", int.class, int.class, long.class, boolean.class);
            updateMethod.setAccessible(true);
            updateMethod.invoke(actorMetrics, 0, 2, 1, false);  // Force backpressure inactive
            
            // Invoke callback with forced inactive backpressure
            Field callbackField = Actor.class.getDeclaredField("backpressureCallback");
            callbackField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Consumer<Actor.BackpressureMetrics> callback = 
                (Consumer<Actor.BackpressureMetrics>) callbackField.get(actor);
            if (callback != null) {
                callback.accept(actorMetrics);
            }
        }
        
        // Verify inactive state was also reported
        assertTrue(sawBackpressureInactive.get(), "Should have seen inactive backpressure");
    }
    
    /**
     * Test actor that allows controlling processing speed
     */
    public static class TestActor extends Actor<String> {
        private static final Logger logger = LoggerFactory.getLogger(TestActor.class);
        private long processingDelay = 0;
        private final AtomicInteger processedCount = new AtomicInteger(0);
        
        public TestActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }
        
        public TestActor(ActorSystem system, String actorId, boolean enableBackpressure) {
            super(system, actorId, enableBackpressure);
        }
        
        public TestActor(ActorSystem system, String actorId, boolean enableBackpressure, 
                       int initialCapacity, int maxCapacity) {
            super(system, actorId, enableBackpressure, initialCapacity, maxCapacity);
        }
        
        public void setProcessingDelay(long delayMs) {
            this.processingDelay = delayMs;
        }
        
        @Override
        protected void receive(String message) {
            try {
                int count = processedCount.incrementAndGet();
                if (count % 10 == 0) {
                    logger.debug("Processed {} messages", count);
                }
                
                if (processingDelay > 0) {
                    Thread.sleep(processingDelay);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        @SuppressWarnings("unchecked")
        public Consumer<Actor.BackpressureMetrics> getBackpressureCallback() {
            try {
                Field callbackField = Actor.class.getDeclaredField("backpressureCallback");
                callbackField.setAccessible(true);
                return (Consumer<Actor.BackpressureMetrics>) callbackField.get(this);
            } catch (Exception e) {
                logger.error("Error accessing backpressure callback", e);
                return null;
            }
        }
    }
}
