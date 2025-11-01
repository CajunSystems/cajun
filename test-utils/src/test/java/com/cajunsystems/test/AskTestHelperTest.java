package com.cajunsystems.test;

import com.cajunsystems.ActorContext;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AskTestHelper functionality.
 */
class AskTestHelperTest {
    
    // Messages
    public record GetValue() implements Serializable {}
    public record ValueResponse(int value) implements Serializable {}
    public record Echo(String text, Pid replyTo) implements Serializable {}
    
    // Handler that responds to GetValue
    public static class ValueHandler implements Handler<GetValue> {
        @Override
        public void receive(GetValue message, ActorContext context) {
            context.getSender().ifPresent(sender ->
                context.tell(sender, new ValueResponse(42))
            );
        }
    }
    
    // Handler that echoes
    public static class EchoHandler implements Handler<Echo> {
        @Override
        public void receive(Echo message, ActorContext context) {
            context.tell(message.replyTo(), message.text());
        }
    }
    
    @Test
    void shouldAskAndGetResponse() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<GetValue> actor = testKit.spawn(ValueHandler.class);
            
            ValueResponse response = AskTestHelper.ask(actor, new GetValue(), Duration.ofSeconds(2));
            
            assertNotNull(response);
            assertEquals(42, response.value());
        }
    }
    
    @Test
    void shouldAskWithTypeCheck() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<GetValue> actor = testKit.spawn(ValueHandler.class);
            
            ValueResponse response = AskTestHelper.ask(
                actor,
                new GetValue(),
                ValueResponse.class,
                Duration.ofSeconds(2)
            );
            
            assertEquals(42, response.value());
        }
    }
    
    @Test
    void shouldThrowOnWrongType() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<GetValue> actor = testKit.spawn(ValueHandler.class);
            
            assertThrows(AssertionError.class, () -> {
                AskTestHelper.ask(actor, new GetValue(), String.class, Duration.ofSeconds(2));
            });
        }
    }
    
    @Test
    void shouldAskAndAssert() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<GetValue> actor = testKit.spawn(ValueHandler.class);
            
            AskTestHelper.askAndAssert(
                actor,
                new GetValue(),
                (ValueResponse response) -> {
                    assertNotNull(response);
                    assertEquals(42, response.value());
                },
                Duration.ofSeconds(2)
            );
        }
    }
    
    @Test
    void shouldAskAndExpect() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<GetValue> actor = testKit.spawn(ValueHandler.class);
            
            AskTestHelper.askAndExpect(
                actor,
                new GetValue(),
                new ValueResponse(42),
                Duration.ofSeconds(2)
            );
        }
    }
    
    @Test
    void shouldTryAskSuccessfully() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<GetValue> actor = testKit.spawn(ValueHandler.class);
            
            ValueResponse result = AskTestHelper.tryAsk(actor, new GetValue(), Duration.ofSeconds(2));
            
            assertNotNull(result);
            assertEquals(42, result.value());
        }
    }
    
    @Test
    void shouldAskUsingPidDirectly() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<GetValue> actor = testKit.spawn(ValueHandler.class);
            
            ValueResponse response = AskTestHelper.ask(
                actor.pid(),
                testKit.system(),
                new GetValue(),
                Duration.ofSeconds(2)
            );
            
            assertEquals(42, response.value());
        }
    }
    
    @Test
    void shouldAskAndExpectWithPid() {
        try (TestKit testKit = TestKit.create()) {
            TestProbe<String> probe = testKit.createProbe();
            TestPid<Echo> actor = testKit.spawn(EchoHandler.class);
            
            actor.tell(new Echo("hello", probe.ref()));
            
            String received = probe.expectMessage(Duration.ofSeconds(1));
            assertEquals("hello", received);
        }
    }
    
    @Test
    void shouldTryAskReturnNullOnTimeout() {
        try (TestKit testKit = TestKit.create()) {
            // Handler that never responds
            TestPid<String> actor = testKit.spawn((msg, ctx) -> {
                // Do nothing - no response
            });
            
            // tryAsk should return null on timeout, not throw
            String result = AskTestHelper.tryAsk(actor, "test", Duration.ofMillis(100));
            
            assertNull(result);
        }
    }
    
    @Test
    void shouldAskWithTypeCheckSuccessfully() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<GetValue> actor = testKit.spawn(ValueHandler.class);
            
            // Should succeed with correct type
            ValueResponse response = AskTestHelper.ask(
                actor,
                new GetValue(),
                ValueResponse.class,
                Duration.ofSeconds(2)
            );
            
            assertNotNull(response);
            assertEquals(42, response.value());
        }
    }
}
