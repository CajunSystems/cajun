package com.cajunsystems.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ClusterMetricsTest {

    @Test
    void testIncrementCounters() {
        ClusterMetrics metrics = new ClusterMetrics("node-1");

        metrics.incrementLocalMessagesRouted();
        metrics.incrementRemoteMessagesRouted();
        metrics.incrementRemoteMessagesSent();
        metrics.incrementRemoteMessagesReceived();
        metrics.incrementRemoteMessageFailures();
        metrics.incrementNodeJoinCount();
        metrics.incrementNodeDepartureCount();

        assertEquals(1, metrics.getLocalMessagesRouted());
        assertEquals(1, metrics.getRemoteMessagesRouted());
        assertEquals(1, metrics.getRemoteMessagesSent());
        assertEquals(1, metrics.getRemoteMessagesReceived());
        assertEquals(1, metrics.getRemoteMessageFailures());
        assertEquals(1, metrics.getNodeJoinCount());
        assertEquals(1, metrics.getNodeDepartureCount());
    }

    @Test
    void testRoutingLatencyAverage() {
        ClusterMetrics metrics = new ClusterMetrics("node-2");

        metrics.recordRoutingLatency(100L);
        metrics.recordRoutingLatency(200L);
        metrics.recordRoutingLatency(300L);

        // average = (100 + 200 + 300) / 3 = 200
        assertEquals(200L, metrics.getAverageRoutingLatencyNs());
    }

    @Test
    void testAverageLatencyZeroWhenNoSamples() {
        ClusterMetrics metrics = new ClusterMetrics("node-3");

        assertEquals(0L, metrics.getAverageRoutingLatencyNs());
    }

    @Test
    void testToStringContainsNodeId() {
        ClusterMetrics metrics = new ClusterMetrics("my-node-id");

        assertTrue(metrics.toString().contains("my-node-id"));
    }
}
