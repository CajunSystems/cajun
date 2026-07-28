package com.cajunsystems.cluster;

public record ClusterHealthStatus(
        boolean healthy,
        String nodeId,
        boolean isLeader,
        int knownNodeCount,
        boolean messagingSystemRunning,
        boolean persistenceHealthy,
        String persistenceProviderName
) {
    public static ClusterHealthStatus of(
            String nodeId,
            boolean isLeader,
            int knownNodeCount,
            boolean messagingSystemRunning,
            boolean persistenceHealthy,
            String persistenceProviderName) {
        return new ClusterHealthStatus(
                persistenceHealthy && messagingSystemRunning,
                nodeId, isLeader, knownNodeCount,
                messagingSystemRunning, persistenceHealthy, persistenceProviderName);
    }
}
