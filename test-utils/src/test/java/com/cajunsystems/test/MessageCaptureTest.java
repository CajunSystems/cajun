package com.cajunsystems.test;

import com.cajunsystems.ActorContext;
import com.cajunsystems.handler.Handler;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MessageCapture functionality.
 */
class MessageCaptureTest {
    
    @Test
    void shouldCaptureMessages() {
        try (TestKit testKit = TestKit.create()) {
            MessageCapture<String> capture = MessageCapture.create();
            TestPid<String> actor = testKit.spawn(capture.handler());
            
            actor.tell("msg1");
            actor.tell("msg2");
            actor.tell("msg3");
            
            AsyncAssertion.eventually(() -> capture.size() == 3, Duration.ofSeconds(1));
            
            assertEquals(3, capture.size());
            assertEquals("msg1", capture.get(0));
            assertEquals("msg2", capture.get(1));
            assertEquals("msg3", capture.get(2));
        }
    }
    
    @Test
    void shouldCaptureAndDelegate() {
        try (TestKit testKit = TestKit.create()) {
            TestProbe<String> probe = testKit.createProbe();
            
            // Handler that echoes to probe
            Handler<String> echoHandler = (msg, ctx) -> probe.ref().tell(msg);
            
            MessageCapture<String> capture = MessageCapture.create(echoHandler);
            TestPid<String> actor = testKit.spawn(capture.handler());
            
            actor.tell("test");
            
            // Should be captured
            AsyncAssertion.awaitValue(capture::size, 1, Duration.ofSeconds(1));
            assertEquals("test", capture.first());
            
            // Should also be delegated
            String echoed = probe.expectMessage(Duration.ofSeconds(1));
            assertEquals("test", echoed);
        }
    }
    
    @Test
    void shouldGetFirstAndLast() {
        try (TestKit testKit = TestKit.create()) {
            MessageCapture<String> capture = MessageCapture.create();
            TestPid<String> actor = testKit.spawn(capture.handler());
            
            actor.tell("first");
            actor.tell("middle");
            actor.tell("last");
            
            AsyncAssertion.awaitValue(capture::size, 3, Duration.ofSeconds(1));
            
            assertEquals("first", capture.first());
            assertEquals("last", capture.last());
        }
    }
    
    @Test
    void shouldFilterMessages() {
        try (TestKit testKit = TestKit.create()) {
            MessageCapture<String> capture = MessageCapture.create();
            TestPid<String> actor = testKit.spawn(capture.handler());
            
            actor.tell("hello");
            actor.tell("world");
            actor.tell("hello");
            
            AsyncAssertion.awaitValue(capture::size, 3, Duration.ofSeconds(1));
            
            var hellos = capture.filter(msg -> msg.equals("hello"));
            assertEquals(2, hellos.size());
        }
    }
    
    @Test
    void shouldGetAllMessages() {
        try (TestKit testKit = TestKit.create()) {
            MessageCapture<String> capture = MessageCapture.create();
            TestPid<String> actor = testKit.spawn(capture.handler());
            
            actor.tell("a");
            actor.tell("b");
            
            AsyncAssertion.awaitValue(capture::size, 2, Duration.ofSeconds(1));
            
            var all = capture.all();
            assertEquals(2, all.size());
            assertTrue(all.contains("a"));
            assertTrue(all.contains("b"));
        }
    }
    
    @Test
    void shouldClearMessages() {
        try (TestKit testKit = TestKit.create()) {
            MessageCapture<String> capture = MessageCapture.create();
            TestPid<String> actor = testKit.spawn(capture.handler());
            
            actor.tell("msg");
            AsyncAssertion.awaitValue(capture::size, 1, Duration.ofSeconds(1));
            
            capture.clear();
            
            assertTrue(capture.isEmpty());
            assertEquals(0, capture.size());
        }
    }
    
    @Test
    void shouldAwaitCount() throws InterruptedException {
        try (TestKit testKit = TestKit.create()) {
            MessageCapture<String> capture = MessageCapture.create();
            TestPid<String> actor = testKit.spawn(capture.handler());
            
            actor.tell("msg1");
            actor.tell("msg2");
            actor.tell("msg3");
            
            boolean reached = capture.awaitCount(3, Duration.ofSeconds(2));
            
            assertTrue(reached);
            assertEquals(3, capture.size());
        }
    }
    
    @Test
    void shouldAwaitMessage() throws InterruptedException {
        try (TestKit testKit = TestKit.create()) {
            MessageCapture<String> capture = MessageCapture.create();
            TestPid<String> actor = testKit.spawn(capture.handler());
            
            actor.tell("hello");
            actor.tell("world");
            
            String found = capture.awaitMessage(msg -> msg.equals("world"), Duration.ofSeconds(2));
            
            assertNotNull(found);
            assertEquals("world", found);
        }
    }
    
    @Test
    void shouldCheckContains() {
        try (TestKit testKit = TestKit.create()) {
            MessageCapture<String> capture = MessageCapture.create();
            TestPid<String> actor = testKit.spawn(capture.handler());
            
            actor.tell("test");
            
            AsyncAssertion.eventually(
                () -> capture.contains(msg -> msg.equals("test")),
                Duration.ofSeconds(1)
            );
        }
    }
    
    @Test
    void shouldCountMatching() {
        try (TestKit testKit = TestKit.create()) {
            MessageCapture<String> capture = MessageCapture.create();
            TestPid<String> actor = testKit.spawn(capture.handler());
            
            actor.tell("a");
            actor.tell("b");
            actor.tell("a");
            actor.tell("a");
            
            AsyncAssertion.awaitValue(capture::size, 4, Duration.ofSeconds(1));
            
            long count = capture.count(msg -> msg.equals("a"));
            assertEquals(3, count);
        }
    }
    
    @Test
    void shouldCreateSnapshot() {
        try (TestKit testKit = TestKit.create()) {
            MessageCapture<String> capture = MessageCapture.create();
            TestPid<String> actor = testKit.spawn(capture.handler());
            
            actor.tell("msg1");
            actor.tell("msg2");
            
            AsyncAssertion.awaitValue(capture::size, 2, Duration.ofSeconds(1));
            
            var snapshot = capture.snapshot();
            
            assertEquals(2, snapshot.size());
            assertFalse(snapshot.isEmpty());
            assertEquals("msg1", snapshot.get(0));
            
            // Snapshot is immutable - adding more messages doesn't affect it
            actor.tell("msg3");
            AsyncAssertion.awaitValue(capture::size, 3, Duration.ofSeconds(1));
            
            assertEquals(2, snapshot.size()); // Still 2
        }
    }
    
    @Test
    void shouldThrowWhenGettingFirstFromEmpty() {
        MessageCapture<String> capture = MessageCapture.create();
        
        assertThrows(IllegalStateException.class, capture::first);
    }
    
    @Test
    void shouldThrowWhenGettingLastFromEmpty() {
        MessageCapture<String> capture = MessageCapture.create();
        
        assertThrows(IllegalStateException.class, capture::last);
    }
}
