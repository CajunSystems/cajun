package com.cajunsystems.test;

import com.cajunsystems.ActorContext;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.StatefulHandler;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StateInspector functionality.
 */
class StateInspectorTest {
    
    // Messages
    public record Add(int value) implements Serializable {}
    public record Multiply(int value) implements Serializable {}
    public record Reset() implements Serializable {}
    
    // Simple calculator handler
    public static class CalculatorHandler implements StatefulHandler<Integer, Object> {
        @Override
        public Integer receive(Object message, Integer state, ActorContext context) {
            if (message instanceof Add add) {
                return state + add.value();
            } else if (message instanceof Multiply mult) {
                return state * mult.value();
            } else if (message instanceof Reset) {
                return 0;
            }
            return state;
        }
    }
    
    @Test
    void shouldInspectInitialState() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<Object> calculator = testKit.spawnStateful(CalculatorHandler.class, 10);
            
            StateInspector<Integer> inspector = calculator.stateInspector();
            
            // Wait for initialization
            int state = AsyncAssertion.awaitValue(inspector::current, 10, Duration.ofSeconds(1));
            assertEquals(10, state);
        }
    }
    
    @Test
    void shouldInspectStateAfterMessages() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<Object> calculator = testKit.spawnStateful(CalculatorHandler.class, 0);
            
            StateInspector<Integer> inspector = calculator.stateInspector();
            
            // Send messages
            calculator.tell(new Add(5));
            calculator.tell(new Add(3));
            
            // Wait for processing
            AsyncAssertion.awaitValue(inspector::current, 8, Duration.ofSeconds(2));
        }
    }
    
    @Test
    void shouldInspectStateWithMultipleOperations() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<Object> calculator = testKit.spawnStateful(CalculatorHandler.class, 2);
            
            StateInspector<Integer> inspector = calculator.stateInspector();
            
            calculator.tell(new Add(3));      // 2 + 3 = 5
            AsyncAssertion.awaitValue(inspector::current, 5, Duration.ofSeconds(1));
            
            calculator.tell(new Multiply(2)); // 5 * 2 = 10
            AsyncAssertion.awaitValue(inspector::current, 10, Duration.ofSeconds(1));
            
            calculator.tell(new Add(5));      // 10 + 5 = 15
            AsyncAssertion.awaitValue(inspector::current, 15, Duration.ofSeconds(1));
        }
    }
    
    @Test
    void shouldCheckStateEquality() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<Object> calculator = testKit.spawnStateful(CalculatorHandler.class, 0);
            
            StateInspector<Integer> inspector = calculator.stateInspector();
            
            calculator.tell(new Add(42));
            
            // Wait for state to update
            AsyncAssertion.awaitValue(inspector::current, 42, Duration.ofSeconds(2));
            
            assertTrue(inspector.stateEquals(42));
            assertFalse(inspector.stateEquals(41));
        }
    }
    
    @Test
    void shouldGetStateAsOptional() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<Object> calculator = testKit.spawnStateful(CalculatorHandler.class, 100);
            
            StateInspector<Integer> inspector = calculator.stateInspector();
            
            // Wait for initialization
            AsyncAssertion.awaitValue(inspector::current, 100, Duration.ofSeconds(1));
            
            var optional = inspector.currentOptional();
            assertTrue(optional.isPresent());
            assertEquals(100, optional.get());
        }
    }
    
    @Test
    void shouldTrackSequenceNumber() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<Object> calculator = testKit.spawnStateful(CalculatorHandler.class, 0);
            
            StateInspector<Integer> inspector = calculator.stateInspector();
            
            // Send multiple messages
            calculator.tell(new Add(1));
            calculator.tell(new Add(2));
            calculator.tell(new Add(3));
            
            // Wait for all messages to be processed (state = 6)
            AsyncAssertion.awaitValue(inspector::current, 6, Duration.ofSeconds(2));
            
            // Sequence should have advanced
            long sequence = inspector.lastProcessedSequence();
            assertTrue(sequence >= 2, "Expected sequence >= 2, got " + sequence);
        }
    }
    
    @Test
    void shouldThrowForNonStatefulActor() {
        try (TestKit testKit = TestKit.create()) {
            // Spawn a regular (non-stateful) actor
            TestPid<String> regularActor = testKit.spawn(
                (msg, ctx) -> { /* do nothing */ }
            );
            
            assertThrows(IllegalArgumentException.class, () -> {
                regularActor.stateInspector();
            });
        }
    }
    
    @Test
    void shouldInspectStateAfterReset() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<Object> calculator = testKit.spawnStateful(CalculatorHandler.class, 0);
            
            StateInspector<Integer> inspector = calculator.stateInspector();
            
            // Wait for initialization
            AsyncAssertion.awaitValue(inspector::current, 0, Duration.ofSeconds(1));
            
            calculator.tell(new Add(100));
            
            // Wait for state to update
            AsyncAssertion.awaitValue(inspector::current, 100, Duration.ofSeconds(2));
            
            calculator.tell(new Reset());
            
            // Wait for reset to complete
            AsyncAssertion.awaitValue(inspector::current, 0, Duration.ofSeconds(2));
        }
    }
}
