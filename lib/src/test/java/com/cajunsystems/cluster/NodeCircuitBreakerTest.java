package com.cajunsystems.cluster;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NodeCircuitBreakerTest {

    @Test
    void testInitialStateClosed() {
        NodeCircuitBreaker cb = new NodeCircuitBreaker("node-1");
        assertEquals(NodeCircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.isCallPermitted());
    }

    @Test
    void testOpensAfterFailureThreshold() {
        NodeCircuitBreaker cb = new NodeCircuitBreaker("node-1", 5, 30_000);
        for (int i = 0; i < 5; i++) cb.recordFailure();
        assertEquals(NodeCircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.isCallPermitted());
    }

    @Test
    void testDoesNotOpenBeforeThreshold() {
        NodeCircuitBreaker cb = new NodeCircuitBreaker("node-1", 5, 30_000);
        for (int i = 0; i < 4; i++) cb.recordFailure();
        assertEquals(NodeCircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.isCallPermitted());
    }

    @Test
    void testHalfOpenAfterResetTimeout() throws InterruptedException {
        NodeCircuitBreaker cb = new NodeCircuitBreaker("node-1", 5, 50);
        for (int i = 0; i < 5; i++) cb.recordFailure();
        assertEquals(NodeCircuitBreaker.State.OPEN, cb.getState());
        Thread.sleep(60);
        assertTrue(cb.isCallPermitted());
        assertEquals(NodeCircuitBreaker.State.HALF_OPEN, cb.getState());
    }

    @Test
    void testClosesOnSuccessFromHalfOpen() throws InterruptedException {
        NodeCircuitBreaker cb = new NodeCircuitBreaker("node-1", 5, 50);
        for (int i = 0; i < 5; i++) cb.recordFailure();
        Thread.sleep(60);
        cb.isCallPermitted(); // transition to HALF_OPEN
        cb.recordSuccess();
        assertEquals(NodeCircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.isCallPermitted());
    }

    @Test
    void testReopensOnFailureFromHalfOpen() throws InterruptedException {
        NodeCircuitBreaker cb = new NodeCircuitBreaker("node-1", 5, 50);
        for (int i = 0; i < 5; i++) cb.recordFailure();
        Thread.sleep(60);
        cb.isCallPermitted(); // HALF_OPEN
        cb.recordFailure();
        assertEquals(NodeCircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.isCallPermitted());
    }

    @Test
    void testCircuitBreakerOpenExceptionContainsNodeId() {
        CircuitBreakerOpenException ex = new CircuitBreakerOpenException("node-42");
        assertEquals("node-42", ex.getNodeId());
        assertTrue(ex.getMessage().contains("node-42"));
    }
}
