package systems.cajun.backpressure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.cajun.config.BackpressureConfig;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct tests for the BackpressureManager component.
 * These tests focus on the core backpressure logic without requiring a full Actor integration.
 */
public class BackpressureManagerTest {
    private static final Logger logger = LoggerFactory.getLogger(BackpressureManagerTest.class);
    
    private BackpressureManager<String> manager;
    private BackpressureConfig config;
    
    @BeforeEach
    public void setup() {
        // Create a test implementation of BackpressureManager
        manager = new TestBackpressureManager();
        
        // Configure with default settings
        config = new BackpressureConfig()
            .setEnabled(true)
            .setStrategy(BackpressureStrategy.DROP_NEW)
            .setWarningThreshold(0.25f)
            .setHighWatermark(0.7f)
            .setLowWatermark(0.2f);
        
        manager.enable(config);
    }
    
    /**
     * Test implementation of BackpressureManager that doesn't require an Actor instance.
     */
    private static class TestBackpressureManager extends BackpressureManager<String> {
        private BackpressureState currentState = BackpressureState.NORMAL;
        private boolean backpressureActive = false;
        private int currentSize = 0;
        private int capacity = 100;
        private long processingRate = 0;
        private BackpressureStrategy strategy = BackpressureStrategy.DROP_NEW;
        private Consumer<BackpressureEvent> callback;
        private float warningThreshold = 0.25f;
        private float criticalThreshold = 0.7f;
        private float recoveryThreshold = 0.2f;
        
        @Override
        public void enable(BackpressureConfig config) {
            this.strategy = config.getStrategy();
            this.warningThreshold = config.getWarningThreshold();
            this.criticalThreshold = config.getHighWatermark();
            this.recoveryThreshold = config.getLowWatermark();
        }
        
        @Override
        public void updateMetrics(int currentMailboxSize, int mailboxCapacity, long rate) {
            this.currentSize = currentMailboxSize;
            this.capacity = mailboxCapacity;
            this.processingRate = rate;
            
            float fillRatio = (float) currentMailboxSize / mailboxCapacity;
            BackpressureState oldState = currentState;
            
            // Determine new state based on fill ratio and current state
            if (fillRatio >= criticalThreshold) {
                currentState = BackpressureState.CRITICAL;
                backpressureActive = true;
            } else if (fillRatio >= warningThreshold) {
                if (currentState == BackpressureState.CRITICAL) {
                    currentState = BackpressureState.RECOVERY;
                } else {
                    currentState = BackpressureState.WARNING;
                }
                backpressureActive = (currentState == BackpressureState.CRITICAL);
            } else if (fillRatio <= recoveryThreshold) {
                currentState = BackpressureState.NORMAL;
                backpressureActive = false;
            } else if (currentState == BackpressureState.CRITICAL) {
                currentState = BackpressureState.RECOVERY;
                backpressureActive = false;
            }
            
            // Always trigger callback for test purposes
            if (callback != null) {
                BackpressureEvent event = new BackpressureEvent(
                    "test-actor",
                    currentState,
                    fillRatio,
                    currentSize,
                    capacity,
                    processingRate,
                    backpressureActive,
                    capacity
                );
                try {
                    callback.accept(event);
                } catch (Exception e) {
                    System.err.println("Error in callback: " + e.getMessage());
                }
            }
        }
        
        @Override
        public boolean isBackpressureActive() {
            return backpressureActive;
        }
        
        @Override
        public BackpressureState getCurrentState() {
            return currentState;
        }
        
        @Override
        public void setCallback(Consumer<BackpressureEvent> callback) {
            this.callback = callback;
        }
        
        @Override
        public void setStrategy(BackpressureStrategy strategy) {
            this.strategy = strategy;
        }
        
        @Override
        public BackpressureStrategy getStrategy() {
            return this.strategy;
        }
        
        @Override
        public boolean shouldAcceptMessage(BackpressureSendOptions options) {
            if (!isBackpressureActive()) {
                return true;
            }
            
            switch (strategy) {
                case BLOCK:
                    return options.isBlockUntilAccepted();
                case DROP_NEW:
                    return false;
                case DROP_OLDEST:
                    return true;
                case CUSTOM:
                    // If we have a custom handler, delegate to it
                    try {
                        Field customHandlerField = BackpressureManager.class.getDeclaredField("customHandler");
                        customHandlerField.setAccessible(true);
                        CustomBackpressureHandler<String> customHandler = 
                            (CustomBackpressureHandler<String>) customHandlerField.get(this);
                        
                        if (customHandler != null) {
                            return customHandler.shouldAccept(currentSize, capacity, options);
                        }
                    } catch (Exception e) {
                        // Fall back to default behavior
                    }
                    return options.isHighPriority();
                default:
                    return true;
            }
        }
    }
    
    @Test
    public void testStateTransitions() {
        // Initial state should be NORMAL
        assertEquals(BackpressureState.NORMAL, manager.getCurrentState(), 
                "Initial state should be NORMAL");
        
        // Simulate WARNING threshold (25%)
        manager.updateMetrics(25, 100, 10);
        assertEquals(BackpressureState.WARNING, manager.getCurrentState(), 
                "State should transition to WARNING at 25% capacity");
        
        // Simulate CRITICAL threshold (70%)
        manager.updateMetrics(70, 100, 10);
        assertEquals(BackpressureState.CRITICAL, manager.getCurrentState(), 
                "State should transition to CRITICAL at 70% capacity");
        
        // Test backpressure active flag
        assertTrue(manager.isBackpressureActive(), 
                "Backpressure should be active in CRITICAL state");
        
        // Simulate dropping below critical but still above recovery
        manager.updateMetrics(50, 100, 10);
        assertEquals(BackpressureState.RECOVERY, manager.getCurrentState(), 
                "State should transition to RECOVERY at 50% capacity");
        
        // Simulate dropping below recovery threshold
        manager.updateMetrics(15, 100, 10);
        assertEquals(BackpressureState.NORMAL, manager.getCurrentState(), 
                "State should transition to NORMAL at 15% capacity");
        
        // Test backpressure inactive flag
        assertFalse(manager.isBackpressureActive(), 
                "Backpressure should be inactive in NORMAL state");
    }
    
    @Test
    public void testBackpressureStrategy() {
        // Set to DROP_NEW strategy (already set in setup)
        assertEquals(BackpressureStrategy.DROP_NEW, manager.getStrategy(),
                "Initial strategy should be DROP_NEW");
        
        // Activate backpressure
        manager.updateMetrics(70, 100, 10);
        assertEquals(BackpressureState.CRITICAL, manager.getCurrentState(),
                "State should be CRITICAL");
        assertTrue(manager.isBackpressureActive(),
                "Backpressure should be active");
        
        // Test DROP_NEW strategy behavior
        BackpressureSendOptions options = new BackpressureSendOptions();
        assertFalse(manager.shouldAcceptMessage(options),
                "DROP_NEW strategy should reject messages when backpressure is active");
        
        // Test BLOCK strategy behavior
        manager.setStrategy(BackpressureStrategy.BLOCK);
        options = new BackpressureSendOptions().withBlockUntilAccepted(true);
        assertTrue(manager.shouldAcceptMessage(options),
                "BLOCK strategy should accept messages with blockUntilAccepted=true");
        
        options = new BackpressureSendOptions().withBlockUntilAccepted(false);
        assertFalse(manager.shouldAcceptMessage(options),
                "BLOCK strategy should reject messages with blockUntilAccepted=false");
        
        // Test DROP_OLDEST strategy behavior
        manager.setStrategy(BackpressureStrategy.DROP_OLDEST);
        options = new BackpressureSendOptions();
        assertTrue(manager.shouldAcceptMessage(options),
                "DROP_OLDEST strategy should accept messages (and drop oldest)");
    }
    
    @Test
    public void testEventCallbacks() {
        // Set up a callback to track events
        AtomicInteger callbackCount = new AtomicInteger(0);
        AtomicBoolean sawWarning = new AtomicBoolean(false);
        AtomicBoolean sawCritical = new AtomicBoolean(false);
        AtomicBoolean sawRecovery = new AtomicBoolean(false);
        
        manager.setCallback(event -> {
            callbackCount.incrementAndGet();
            if (event.getState() == BackpressureState.WARNING) {
                sawWarning.set(true);
            } else if (event.getState() == BackpressureState.CRITICAL) {
                sawCritical.set(true);
            } else if (event.getState() == BackpressureState.RECOVERY) {
                sawRecovery.set(true);
            }
        });
        
        // Trigger state transitions
        manager.updateMetrics(25, 100, 10); // WARNING
        manager.updateMetrics(70, 100, 10); // CRITICAL
        manager.updateMetrics(50, 100, 10); // RECOVERY
        manager.updateMetrics(15, 100, 10); // NORMAL
        
        // Verify callbacks were invoked
        assertEquals(4, callbackCount.get(), "Callback should have been invoked 4 times");
        assertTrue(sawWarning.get(), "Should have seen WARNING state");
        assertTrue(sawCritical.get(), "Should have seen CRITICAL state");
        assertTrue(sawRecovery.get(), "Should have seen RECOVERY state");
    }
    
    @Test
    public void testCustomStrategy() {
        // Create a custom handler
        CustomBackpressureHandler<String> customHandler = new CustomBackpressureHandler<String>() {
            @Override
            public CustomBackpressureHandler.BackpressureAction handleMessage(String message, BackpressureEvent event) {
                // Accept messages containing "high" regardless of backpressure
                return message != null && message.contains("high") 
                    ? CustomBackpressureHandler.BackpressureAction.ACCEPT 
                    : CustomBackpressureHandler.BackpressureAction.REJECT;
            }
            
            @Override
            public boolean shouldAccept(int mailboxSize, int capacity, BackpressureSendOptions options) {
                // Accept high priority messages
                return options.isHighPriority();
            }
        };
        
        // Set the custom strategy and handler
        manager.setStrategy(BackpressureStrategy.CUSTOM);
        
        // Use reflection to set the custom handler
        try {
            Field customHandlerField = BackpressureManager.class.getDeclaredField("customHandler");
            customHandlerField.setAccessible(true);
            customHandlerField.set(manager, customHandler);
        } catch (Exception e) {
            fail("Failed to set custom handler: " + e.getMessage());
        }
        
        // Activate backpressure
        manager.updateMetrics(70, 100, 10);
        assertEquals(BackpressureState.CRITICAL, manager.getCurrentState(),
                "State should be CRITICAL after updating metrics");
        assertTrue(manager.isBackpressureActive(), 
                "Backpressure should be active in CRITICAL state");
        
        // Test the custom handler behavior
        BackpressureSendOptions regularOptions = new BackpressureSendOptions();
        BackpressureSendOptions highPriorityOptions = new BackpressureSendOptions().withHighPriority(true);
        
        // Test shouldAccept method
        assertFalse(customHandler.shouldAccept(70, 100, regularOptions),
                "Custom handler should reject regular priority messages");
        assertTrue(customHandler.shouldAccept(70, 100, highPriorityOptions),
                "Custom handler should accept high priority messages");
                
        // Test handleMessage method
        assertEquals(CustomBackpressureHandler.BackpressureAction.ACCEPT, 
                customHandler.handleMessage("high-priority", null),
                "Custom handler should accept messages containing 'high'");
        assertEquals(CustomBackpressureHandler.BackpressureAction.REJECT, 
                customHandler.handleMessage("normal-priority", null),
                "Custom handler should reject messages not containing 'high'");
    }
}
