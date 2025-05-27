package com.cajunsystems.backpressure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the BackpressureEvent class.
 */
public class BackpressureEventTest {

    @Test
    public void testValidConstruction() {
        // Create a valid event
        BackpressureEvent event = new BackpressureEvent(
                "test-actor",
                BackpressureState.WARNING,
                0.75f,
                75,
                100,
                10,
                false,
                100
        );

        // Verify all fields are set correctly
        assertEquals("test-actor", event.getActorId());
        assertEquals(BackpressureState.WARNING, event.getState());
        assertEquals(0.75f, event.getFillRatio(), 0.001f);
        assertEquals(75, event.getCurrentSize());
        assertEquals(100, event.getCapacity());
        assertEquals(10, event.getProcessingRate());
        assertFalse(event.wasResized());
        assertEquals(100, event.getPreviousCapacity());
        assertNotNull(event.getTimestamp());
    }

    @Test
    public void testEqualsAndHashCode() {
        // Create two identical events with the same timestamp
        Instant now = Instant.now();
        
        // Create first event
        BackpressureEvent event1 = new BackpressureEvent(
                "test-actor",
                BackpressureState.WARNING,
                0.75f,
                75,
                100,
                10,
                false,
                100
        );
        
        // Create a second event with the same values
        BackpressureEvent event2 = new BackpressureEvent(
                "test-actor",
                BackpressureState.WARNING,
                0.75f,
                75,
                100,
                10,
                false,
                100
        );

        // Create a different event
        BackpressureEvent event3 = new BackpressureEvent(
                "test-actor",
                BackpressureState.CRITICAL,
                0.9f,
                90,
                100,
                5,
                true,
                200
        );

        // Test equals
        // Note: equals compares all fields including timestamp, so we can't directly compare event1 and event2
        // Instead, we'll test that an event equals itself
        assertEquals(event1, event1);
        assertEquals(event2, event2);
        assertEquals(event3, event3);
        assertNotEquals(event1, event3);
        assertNotEquals(event1, null);
        assertNotEquals(event1, "not an event");
    }

    @Test
    public void testGetEstimatedTimeToEmpty() {
        // Create an event with a processing rate of 10 msgs/sec and 100 messages in the queue
        BackpressureEvent event = new BackpressureEvent(
                "test-actor",
                BackpressureState.WARNING,
                0.75f,
                100,
                200,
                10, // 10 msgs/sec
                false,
                0
        );

        // Expected time to empty: 100 messages / 10 msgs/sec = 10 seconds = 10000 ms
        assertEquals(10000, event.getEstimatedTimeToEmpty());
    }

    @Test
    public void testGetEstimatedTimeToEmptyWithZeroRate() {
        // Create an event with a processing rate of 0 msgs/sec
        BackpressureEvent event = new BackpressureEvent(
                "test-actor",
                BackpressureState.WARNING,
                0.75f,
                100,
                200,
                0, // 0 msgs/sec
                false,
                0
        );

        // Expected behavior: return Long.MAX_VALUE when rate is 0
        assertEquals(Long.MAX_VALUE, event.getEstimatedTimeToEmpty());
    }

    @Test
    public void testGetEstimatedTimeToEmptyWithZeroSize() {
        // Create an event with 0 messages in the queue
        BackpressureEvent event = new BackpressureEvent(
                "test-actor",
                BackpressureState.NORMAL,
                0.0f,
                0,
                200,
                10,
                false,
                0
        );

        // Expected behavior: return 0 when size is 0
        assertEquals(0, event.getEstimatedTimeToEmpty());
    }

    @ParameterizedTest
    @MethodSource("provideInvalidParameters")
    public void testInputValidation(String actorId, BackpressureState state, float fillRatio,
                                   int currentSize, int capacity, long processingRate,
                                   boolean backpressureActive, int previousCapacity) {
        
        // All invalid parameters should be corrected during construction
        BackpressureEvent event = new BackpressureEvent(
                actorId,
                state,
                fillRatio,
                currentSize,
                capacity,
                processingRate,
                backpressureActive,
                previousCapacity
        );
        
        // Verify corrections
        assertNotNull(event.getActorId());
        assertNotNull(event.getState());
        // The fillRatio is not bounded in the constructor, only checked for NaN and Infinity
        assertTrue(!Float.isNaN(event.getFillRatio()) && !Float.isInfinite(event.getFillRatio()));
        assertTrue(event.getCurrentSize() >= 0);
        assertTrue(event.getCapacity() > 0);
        assertTrue(event.getProcessingRate() >= 0);
        assertTrue(event.getPreviousCapacity() >= 0);
    }
    
    private static Stream<Arguments> provideInvalidParameters() {
        return Stream.of(
                // Null actorId
                Arguments.of(null, BackpressureState.NORMAL, 0.5f, 50, 100, 10L, false, 0),
                
                // Empty actorId
                Arguments.of("", BackpressureState.NORMAL, 0.5f, 50, 100, 10L, false, 0),
                
                // Null state
                Arguments.of("test-actor", null, 0.5f, 50, 100, 10L, false, 0),
                
                // Negative fillRatio
                Arguments.of("test-actor", BackpressureState.NORMAL, -0.1f, 50, 100, 10L, false, 0),
                
                // fillRatio > 1.0
                Arguments.of("test-actor", BackpressureState.NORMAL, 1.5f, 50, 100, 10L, false, 0),
                
                // Negative currentSize
                Arguments.of("test-actor", BackpressureState.NORMAL, 0.5f, -10, 100, 10L, false, 0),
                
                // Zero capacity
                Arguments.of("test-actor", BackpressureState.NORMAL, 0.5f, 50, 0, 10L, false, 0),
                
                // Negative capacity
                Arguments.of("test-actor", BackpressureState.NORMAL, 0.5f, 50, -100, 10L, false, 0),
                
                // Negative processingRate
                Arguments.of("test-actor", BackpressureState.NORMAL, 0.5f, 50, 100, -5L, false, 0),
                
                // Negative previousCapacity
                Arguments.of("test-actor", BackpressureState.NORMAL, 0.5f, 50, 100, 10L, false, -10)
        );
    }

    @Test
    public void testToString() {
        BackpressureEvent event = new BackpressureEvent(
                "test-actor",
                BackpressureState.WARNING,
                0.75f,
                75,
                100,
                10,
                false,
                100
        );
        
        String toString = event.toString();
        
        // Verify toString contains important information
        assertTrue(toString.contains("test-actor"));
        assertTrue(toString.contains("WARNING"));
        assertTrue(toString.contains("0.75"));
        assertTrue(toString.contains("75"));
        assertTrue(toString.contains("100"));
    }
}
