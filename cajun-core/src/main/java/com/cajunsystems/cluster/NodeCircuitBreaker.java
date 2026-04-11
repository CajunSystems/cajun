package com.cajunsystems.cluster;

import java.util.concurrent.atomic.AtomicInteger;

public class NodeCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String nodeId;
    private final int failureThreshold;
    private final long resetTimeoutMs;

    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile long openedAt = 0;

    public NodeCircuitBreaker(String nodeId) {
        this(nodeId, 5, 30_000);
    }

    public NodeCircuitBreaker(String nodeId, int failureThreshold, long resetTimeoutMs) {
        this.nodeId = nodeId;
        this.failureThreshold = failureThreshold;
        this.resetTimeoutMs = resetTimeoutMs;
    }

    public synchronized boolean isCallPermitted() {
        return switch (state) {
            case CLOSED -> true;
            case HALF_OPEN -> true;
            case OPEN -> {
                if (System.currentTimeMillis() - openedAt >= resetTimeoutMs) {
                    state = State.HALF_OPEN;
                    yield true;
                }
                yield false;
            }
        };
    }

    public synchronized void recordSuccess() {
        failureCount.set(0);
        state = State.CLOSED;
    }

    public synchronized void recordFailure() {
        int count = failureCount.incrementAndGet();
        if (state == State.HALF_OPEN || count >= failureThreshold) {
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
            failureCount.set(0);
        }
    }

    public State getState() { return state; }
    public String getNodeId() { return nodeId; }

    @Override
    public String toString() {
        return "NodeCircuitBreaker{nodeId='" + nodeId + "', state=" + state
                + ", failures=" + failureCount.get() + '}';
    }
}
