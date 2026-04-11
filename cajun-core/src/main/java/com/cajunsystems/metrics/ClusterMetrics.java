package com.cajunsystems.metrics;

import java.util.concurrent.atomic.AtomicLong;

public class ClusterMetrics {
    private final String nodeId;
    private final AtomicLong localMessagesRouted = new AtomicLong(0);
    private final AtomicLong remoteMessagesRouted = new AtomicLong(0);
    private final AtomicLong remoteMessagesSent = new AtomicLong(0);
    private final AtomicLong remoteMessagesReceived = new AtomicLong(0);
    private final AtomicLong remoteMessageFailures = new AtomicLong(0);
    private final AtomicLong totalRoutingLatencyNs = new AtomicLong(0);
    private final AtomicLong routingLatencyCount = new AtomicLong(0);
    private final AtomicLong nodeJoinCount = new AtomicLong(0);
    private final AtomicLong nodeDepartureCount = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);

    public ClusterMetrics(String nodeId) { this.nodeId = nodeId; }

    public void incrementLocalMessagesRouted() { localMessagesRouted.incrementAndGet(); }
    public void incrementRemoteMessagesRouted() { remoteMessagesRouted.incrementAndGet(); }
    public void incrementRemoteMessagesSent() { remoteMessagesSent.incrementAndGet(); }
    public void incrementRemoteMessagesReceived() { remoteMessagesReceived.incrementAndGet(); }
    public void incrementRemoteMessageFailures() { remoteMessageFailures.incrementAndGet(); }
    public void recordRoutingLatency(long nanos) {
        totalRoutingLatencyNs.addAndGet(nanos);
        routingLatencyCount.incrementAndGet();
    }
    public void incrementNodeJoinCount() { nodeJoinCount.incrementAndGet(); }
    public void incrementNodeDepartureCount() { nodeDepartureCount.incrementAndGet(); }
    public void incrementCacheHit() { cacheHits.incrementAndGet(); }
    public void incrementCacheMiss() { cacheMisses.incrementAndGet(); }

    public String getNodeId() { return nodeId; }
    public long getLocalMessagesRouted() { return localMessagesRouted.get(); }
    public long getRemoteMessagesRouted() { return remoteMessagesRouted.get(); }
    public long getRemoteMessagesSent() { return remoteMessagesSent.get(); }
    public long getRemoteMessagesReceived() { return remoteMessagesReceived.get(); }
    public long getRemoteMessageFailures() { return remoteMessageFailures.get(); }
    public long getNodeJoinCount() { return nodeJoinCount.get(); }
    public long getNodeDepartureCount() { return nodeDepartureCount.get(); }
    public long getCacheHits() { return cacheHits.get(); }
    public long getCacheMisses() { return cacheMisses.get(); }
    public long getAverageRoutingLatencyNs() {
        long count = routingLatencyCount.get();
        return count == 0 ? 0 : totalRoutingLatencyNs.get() / count;
    }

    @Override
    public String toString() {
        return "ClusterMetrics{nodeId='" + nodeId + "', localRouted=" + getLocalMessagesRouted()
                + ", remoteRouted=" + getRemoteMessagesRouted()
                + ", remoteSent=" + getRemoteMessagesSent()
                + ", remoteReceived=" + getRemoteMessagesReceived()
                + ", failures=" + getRemoteMessageFailures()
                + ", avgRoutingLatencyNs=" + getAverageRoutingLatencyNs()
                + ", nodeJoins=" + getNodeJoinCount()
                + ", nodeDepartures=" + getNodeDepartureCount()
                + ", cacheHits=" + getCacheHits()
                + ", cacheMisses=" + getCacheMisses() + '}';
    }
}
