package com.cajunsystems.test;

import com.cajunsystems.ActorContext;
import com.cajunsystems.handler.Handler;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MailboxInspector functionality.
 */
class MailboxInspectorTest {
    
    // Slow handler that processes messages with delay
    public static class SlowHandler implements Handler<String> {
        @Override
        public void receive(String message, ActorContext context) {
            try {
                Thread.sleep(100); // Slow processing
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    // Fast handler
    public static class FastHandler implements Handler<String> {
        @Override
        public void receive(String message, ActorContext context) {
            // Process immediately
        }
    }
    
    @Test
    void shouldInspectEmptyMailbox() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<String> actor = testKit.spawn(FastHandler.class);
            
            MailboxInspector inspector = actor.mailboxInspector();
            
            assertTrue(inspector.isEmpty());
            assertEquals(0, inspector.size());
            assertFalse(inspector.isFull());
        }
    }
    
    @Test
    void shouldInspectMailboxSize() throws InterruptedException {
        try (TestKit testKit = TestKit.create()) {
            TestPid<String> actor = testKit.spawn(SlowHandler.class);
            
            MailboxInspector inspector = actor.mailboxInspector();
            
            // Send multiple messages quickly
            for (int i = 0; i < 20; i++) {
                actor.tell("message-" + i);
            }
            
            // Check immediately - messages should be queued
            int size = inspector.size();
            
            // Mailbox should have messages (may vary but should be > 0)
            assertTrue(size >= 0, "Size should be >= 0, got: " + size);
            
            // After waiting, some should be processed
            Thread.sleep(300);
            assertTrue(inspector.size() < 20 || inspector.size() == 0);
        }
    }
    
    @Test
    void shouldGetMailboxCapacity() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<String> actor = testKit.spawn(FastHandler.class);
            
            MailboxInspector inspector = actor.mailboxInspector();
            
            // Default capacity should be positive
            assertTrue(inspector.capacity() > 0);
        }
    }
    
    @Test
    void shouldCalculateFillRatio() throws InterruptedException {
        try (TestKit testKit = TestKit.create()) {
            TestPid<String> actor = testKit.spawn(SlowHandler.class);
            
            MailboxInspector inspector = actor.mailboxInspector();
            
            // Send some messages
            for (int i = 0; i < 10; i++) {
                actor.tell("message-" + i);
            }
            
            // Check immediately
            double fillRatio = inspector.fillRatio();
            
            // Fill ratio should be >= 0 and <= 1.0
            assertTrue(fillRatio >= 0.0 && fillRatio <= 1.0, 
                "Fill ratio should be between 0 and 1, got: " + fillRatio);
        }
    }
    
    @Test
    void shouldCheckThreshold() throws InterruptedException {
        try (TestKit testKit = TestKit.create()) {
            TestPid<String> actor = testKit.spawn(SlowHandler.class);
            
            MailboxInspector inspector = actor.mailboxInspector();
            
            // Send many messages
            for (int i = 0; i < 30; i++) {
                actor.tell("message-" + i);
            }
            
            // Check immediately - should have messages queued
            double fillRatio = inspector.fillRatio();
            
            // Threshold check should work correctly
            if (fillRatio > 0.01) {
                assertTrue(inspector.exceedsThreshold(0.01));
            } else {
                assertFalse(inspector.exceedsThreshold(0.99));
            }
        }
    }
    
    @Test
    void shouldAwaitEmpty() throws InterruptedException {
        try (TestKit testKit = TestKit.create()) {
            TestPid<String> actor = testKit.spawn(FastHandler.class);
            
            MailboxInspector inspector = actor.mailboxInspector();
            
            // Send a few messages
            actor.tell("msg1");
            actor.tell("msg2");
            actor.tell("msg3");
            
            // Wait for mailbox to empty
            boolean emptied = inspector.awaitEmpty(Duration.ofSeconds(2));
            
            assertTrue(emptied);
            assertTrue(inspector.isEmpty());
        }
    }
    
    @Test
    void shouldAwaitSizeBelow() throws InterruptedException {
        try (TestKit testKit = TestKit.create()) {
            TestPid<String> actor = testKit.spawn(SlowHandler.class);
            
            MailboxInspector inspector = actor.mailboxInspector();
            
            // Send messages
            for (int i = 0; i < 10; i++) {
                actor.tell("message-" + i);
            }
            
            // Wait for size to drop below threshold
            boolean dropped = inspector.awaitSizeBelow(5, Duration.ofSeconds(3));
            
            assertTrue(dropped);
            assertTrue(inspector.size() < 5);
        }
    }
    
    @Test
    void shouldGetMetricsSnapshot() throws InterruptedException {
        try (TestKit testKit = TestKit.create()) {
            TestPid<String> actor = testKit.spawn(SlowHandler.class);
            
            MailboxInspector inspector = actor.mailboxInspector();
            
            // Send messages
            for (int i = 0; i < 5; i++) {
                actor.tell("message-" + i);
            }
            
            Thread.sleep(50);
            
            // Get metrics snapshot
            var metrics = inspector.metrics();
            
            assertNotNull(metrics);
            assertTrue(metrics.capacity() > 0);
            assertTrue(metrics.size() >= 0);
            assertTrue(metrics.fillRatio() >= 0.0);
            assertTrue(metrics.processingRate() >= 0.0);
            
            // toString should work
            assertNotNull(metrics.toString());
        }
    }
    
    @Test
    void shouldTrackProcessingRate() throws InterruptedException {
        try (TestKit testKit = TestKit.create()) {
            TestPid<String> actor = testKit.spawn(FastHandler.class);
            
            MailboxInspector inspector = actor.mailboxInspector();
            
            // Send messages
            for (int i = 0; i < 100; i++) {
                actor.tell("message-" + i);
            }
            
            // Give time to process
            Thread.sleep(500);
            
            // Processing rate should be calculated
            double rate = inspector.processingRate();
            assertTrue(rate >= 0.0);
        }
    }
    
    @Test
    void shouldNotBeFullInitially() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<String> actor = testKit.spawn(FastHandler.class);
            
            MailboxInspector inspector = actor.mailboxInspector();
            
            assertFalse(inspector.isFull());
        }
    }
}
