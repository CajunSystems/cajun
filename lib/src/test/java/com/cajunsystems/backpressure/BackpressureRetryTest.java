package com.cajunsystems.backpressure;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.config.BackpressureConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the retry functionality in BackpressureManager.
 */
public class BackpressureRetryTest {

    @Mock
    private Actor<String> mockActor;
    
    @Mock
    private Pid mockPid;
    
    @Mock
    private ActorSystem mockSystem;

    private BackpressureManager<String> manager;
    private TestCustomHandler customHandler;
    private BackpressureSendOptions defaultOptions;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(mockActor.getActorId()).thenReturn("test-actor");
        when(mockActor.self()).thenReturn(mockPid);
        when(mockPid.system()).thenReturn(mockSystem);
        
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
        
        // Verify the message was sent to the actor via the Pid
        verify(mockPid, times(1)).tell(eq(message));
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
        verify(mockPid, never()).tell(any());
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
        verify(mockPid, times(1)).tell(eq(message));
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
        verify(mockPid, times(1)).tell(eq(message));
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
        
        // Mock the Pid's tell method to count down the latch
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mockPid).tell(any());
        
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
        verify(mockPid, times(messageCount)).tell(any());
    }

    /**
     * Custom handler for testing backpressure actions.
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
            return action == BackpressureAction.ACCEPT;
        }
    }
}
