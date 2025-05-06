package systems.cajun.runtime.cluster;

import systems.cajun.ActorSystem;
import systems.cajun.cluster.ClusterActorSystem;
import systems.cajun.cluster.MessagingSystem;
import systems.cajun.cluster.MetadataStore;

/**
 * Factory class for creating cluster component implementations.
 */
public class ClusterFactory {
    
    /**
     * Creates a direct messaging system for simple TCP-based communication.
     *
     * @param systemId The ID of this actor system
     * @param port The port to listen on for incoming messages
     * @return A new DirectMessagingSystem instance
     */
    public static MessagingSystem createDirectMessagingSystem(String systemId, int port) {
        return new DirectMessagingSystem(systemId, port);
    }
    
    /**
     * Creates an etcd-based metadata store.
     *
     * @param endpoints The etcd endpoints (e.g., "http://localhost:2379")
     * @return A new EtcdMetadataStore instance
     */
    public static MetadataStore createEtcdMetadataStore(String... endpoints) {
        return new EtcdMetadataStore(endpoints);
    }
    
    /**
     * Creates a cluster actor system.
     *
     * @param systemId The ID of this actor system
     * @param metadataStore The metadata store to use for cluster coordination
     * @param messagingSystem The messaging system to use for inter-node communication
     * @return A new ClusterActorSystem instance
     */
    public static ClusterActorSystem createClusterActorSystem(String systemId, MetadataStore metadataStore, MessagingSystem messagingSystem) {
        return new ClusterActorSystem(systemId, metadataStore, messagingSystem);
    }
}
