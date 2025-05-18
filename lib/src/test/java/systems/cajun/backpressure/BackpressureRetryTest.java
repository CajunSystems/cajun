package systems.cajun.backpressure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import systems.cajun.Actor;
import systems.cajun.config.BackpressureConfig;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the retry functionality in BackpressureManager.
 */
public class BackpressureRetryTest {

    @Mock
    private Actor<String> mockActor;

    private BackpressureManager<String> manager;
    private TestCustomHandler customHandler;
    private BackpressureSendOptions defaultOptions;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(mockActor.getActorId()).thenReturn("test-actor");
        
        customHandler = new TestCustomHandler();
        
        // Configure backpressure
        BackpressureConfig config = new BackpressureConfig()
                .setHighWatermark(0.8f)
                .setLowWatermark(0.5f);
                
        // Create manager with actor and config
        manager = new BackpressureManager<String>(mockActor, config);
        manager.setStrategy(BackpressureStrategy.CUSTOM);
        manager.setCustomHandler(customHandler);
        
        // Set a shorter retry timeout for testing
        manager.setDefaultRetryTimeoutMs(100);
        
        // Create default send options
        defaultOptions = new BackpressureSendOptions();
    }

    @Test
    public void testRetryWithTimeoutSuccess() throws Exception {
        // Set up the manager to be in CRITICAL state
        manager.updateMetrics(80, 100, 10);
        assertTrue(manager.isBackpressureActive());
        
        // Set the custom handler to return RETRY_WITH_TIMEOUT
        customHandler.setAction(CustomBackpressureHandler.BackpressureAction.RETRY_WITH_TIMEOUT);
        
        // Try to send a message
        String message = "test message";
        boolean result = manager.handleMessage(message, defaultOptions);
        
        // The message should be accepted for retry
        assertTrue(result);
        
        // Disable backpressure to allow the retry to succeed
        manager.disable();
        
        // Wait for retry to happen
        Thread.sleep(200);
        
        // Verify the message was sent to the actor
        verify(mockActor, times(1)).tell(eq(message));
    }

    @Test
    public void testRetryWithTimeoutBackpressureStillActive() throws Exception {
        // Set up the manager to be in CRITICAL state
        manager.updateMetrics(90, 100, 10);
        assertTrue(manager.isBackpressureActive());
        
        // Set the custom handler to return RETRY_WITH_TIMEOUT
        customHandler.setAction(CustomBackpressureHandler.BackpressureAction.RETRY_WITH_TIMEOUT);
        
        // Try to send a message
        String message = "test message";
        boolean result = manager.handleMessage(message, defaultOptions);
        
        // The message should be accepted for retry
        assertTrue(result);
        
        // Keep backpressure active by updating metrics again
        manager.updateMetrics(95, 100, 10);
        
        // Wait for retry to happen
        Thread.sleep(200);
        
        // Verify the message was not sent to the actor because backpressure is still active
        verify(mockActor, never()).tell(any());
    }

    @Test
    public void testRetryWithTimeoutBackpressureImproved() throws Exception {
        // Set up the manager to be in CRITICAL state
        manager.updateMetrics(90, 100, 10);
        assertTrue(manager.isBackpressureActive());
        
        // Set the custom handler to return RETRY_WITH_TIMEOUT
        customHandler.setAction(CustomBackpressureHandler.BackpressureAction.RETRY_WITH_TIMEOUT);
        
        // Try to send a message
        String message = "test message";
        boolean result = manager.handleMessage(message, defaultOptions);
        
        // The message should be accepted for retry
        assertTrue(result);
        
        // Improve backpressure situation
        manager.updateMetrics(70, 100, 10);
        
        // Wait for retry to happen
        Thread.sleep(200);
        
        // Verify the message was sent to the actor because backpressure improved
        verify(mockActor, times(1)).tell(eq(message));
    }

    @Test
    public void testRetryWithTimeoutBackpressureDisabled() throws Exception {
        // Set up the manager to be in CRITICAL state
        manager.updateMetrics(90, 100, 10);
        assertTrue(manager.isBackpressureActive());
        
        // Set the custom handler to return RETRY_WITH_TIMEOUT
        customHandler.setAction(CustomBackpressureHandler.BackpressureAction.RETRY_WITH_TIMEOUT);
        
        // Try to send a message
        String message = "test message";
        boolean result = manager.handleMessage(message, defaultOptions);
        
        // The message should be accepted for retry
        assertTrue(result);
        
        // Disable backpressure
        manager.disable();
        
        // Wait for retry to happen
        Thread.sleep(200);
        
        // Verify the message was sent to the actor because backpressure was disabled
        verify(mockActor, times(1)).tell(eq(message));
    }

    @Test
    public void testMultipleRetriesWithConcurrency() throws Exception {
        // Set up the manager to be in CRITICAL state
        manager.updateMetrics(90, 100, 10);
        assertTrue(manager.isBackpressureActive());
        
        // Set the custom handler to return RETRY_WITH_TIMEOUT
        customHandler.setAction(CustomBackpressureHandler.BackpressureAction.RETRY_WITH_TIMEOUT);
        
        // Create a latch to wait for all messages to be processed
        final int messageCount = 10;
        CountDownLatch latch = new CountDownLatch(messageCount);
        
        // Mock the actor's tell method to count down the latch
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mockActor).tell(any());
        
        // Send multiple messages concurrently
        for (int i = 0; i < messageCount; i++) {
            final String message = "test message " + i;
            new Thread(() -> {
                manager.handleMessage(message, defaultOptions);
            }).start();
        }
        
        // Wait a bit for messages to be queued
        Thread.sleep(50);
        
        // Disable backpressure to allow all messages to be sent
        manager.disable();
        
        // Wait for all messages to be processed
        boolean allProcessed = latch.await(1, TimeUnit.SECONDS);
        
        // Verify all messages were processed
        assertTrue(allProcessed, "Not all messages were processed");
        verify(mockActor, times(messageCount)).tell(any());
    }

    @Test
    public void testShutdownCleansUpResources() throws Exception {
        // Set up the manager to be in CRITICAL state
        manager.updateMetrics(90, 100, 10);
        assertTrue(manager.isBackpressureActive());
        
        // Set the custom handler to return RETRY_WITH_TIMEOUT
        customHandler.setAction(CustomBackpressureHandler.BackpressureAction.RETRY_WITH_TIMEOUT);
        
        // Try to send a message
        String message = "test message";
        boolean result = manager.handleMessage(message, defaultOptions);
        
        // The message should be accepted for retry
        assertTrue(result);
        
        // Shutdown the manager
        manager.shutdown();
        
        // Wait for retry to happen (but it shouldn't because we shut down)
        Thread.sleep(200);
        
        // Verify the message was not sent to the actor because we shut down
        verify(mockActor, never()).tell(any());
    }

    @Test
    public void testThreadSafetyWithMultipleThreads() throws Exception {
        // Set up a callback to track state transitions
        AtomicInteger callbackCount = new AtomicInteger(0);
        manager.setCallback(event -> callbackCount.incrementAndGet());
        
        // Create threads to update metrics and handle messages concurrently
        final int threadCount = 10;
        final int iterationsPerThread = 100;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch finishLatch = new CountDownLatch(threadCount);
        
        // Create and start threads
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();
                    
                    for (int j = 0; j < iterationsPerThread; j++) {
                        // Alternate between updating metrics and handling messages
                        if (j % 2 == 0) {
                            // Update metrics with varying values
                            int size = 50 + (threadId * j) % 50;
                            manager.updateMetrics(size, 100, 10);
                        } else {
                            // Handle a message
                            String message = "thread " + threadId + " message " + j;
                            manager.handleMessage(message, defaultOptions);
                        }
                    }
                } catch (Exception e) {
                    fail("Exception in test thread: " + e.getMessage());
                } finally {
                    finishLatch.countDown();
                }
            }).start();
        }
        
        // Start all threads at once
        startLatch.countDown();
        
        // Wait for all threads to finish
        boolean allFinished = finishLatch.await(10, TimeUnit.SECONDS);
        assertTrue(allFinished, "Not all threads completed in time");
        
        // Verify that the callback was called at least once
        assertTrue(callbackCount.get() > 0, "Callback should have been called at least once");
    }

    /**
     * Custom handler implementation for testing.
     */
    private static class TestCustomHandler implements CustomBackpressureHandler<String> {
        private BackpressureAction action = BackpressureAction.REJECT;
        
        public void setAction(BackpressureAction action) {
            this.action = action;
        }
        
        @Override
        public BackpressureAction handleMessage(String message, BackpressureEvent event) {
            return action;
        }
        
        @Override
        public boolean shouldAccept(int mailboxSize, int capacity, BackpressureSendOptions options) {
            return false; // Default to reject for testing
        }
    }
}
