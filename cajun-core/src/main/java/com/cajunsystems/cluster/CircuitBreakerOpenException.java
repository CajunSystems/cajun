package com.cajunsystems.cluster;

public class CircuitBreakerOpenException extends RuntimeException {
    private final String nodeId;

    public CircuitBreakerOpenException(String nodeId) {
        super("Circuit breaker OPEN for node '" + nodeId + "' — call rejected");
        this.nodeId = nodeId;
    }

    public String getNodeId() { return nodeId; }
}
