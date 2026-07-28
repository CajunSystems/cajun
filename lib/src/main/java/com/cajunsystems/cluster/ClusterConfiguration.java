package com.cajunsystems.cluster;

import com.cajunsystems.persistence.PersistenceProvider;

import java.util.UUID;

/**
 * Fluent builder for ClusterActorSystem.
 *
 * <pre>
 *   ClusterActorSystem system = ClusterConfiguration.builder()
 *       .systemId("node-1")
 *       .metadataStore(etcdStore)
 *       .messagingSystem(rms)
 *       .deliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
 *       .persistenceProvider(redisProvider)
 *       .build();
 * </pre>
 *
 * {@code metadataStore} and {@code messagingSystem} are required;
 * all other fields are optional.
 */
public final class ClusterConfiguration {

    private final String systemId;
    private final MetadataStore metadataStore;
    private final MessagingSystem messagingSystem;
    private final DeliveryGuarantee deliveryGuarantee;
    private final PersistenceProvider persistenceProvider;

    private ClusterConfiguration(Builder b) {
        this.systemId = b.systemId;
        this.metadataStore = b.metadataStore;
        this.messagingSystem = b.messagingSystem;
        this.deliveryGuarantee = b.deliveryGuarantee;
        this.persistenceProvider = b.persistenceProvider;
    }

    /** Entry point for the builder. */
    public static Builder builder() { return new Builder(); }

    /** Builds and returns a fully configured but not-yet-started ClusterActorSystem. */
    public ClusterActorSystem build() {
        if (metadataStore == null) throw new IllegalStateException("metadataStore is required");
        if (messagingSystem == null) throw new IllegalStateException("messagingSystem is required");

        ClusterActorSystem system = new ClusterActorSystem(systemId, metadataStore, messagingSystem);
        if (deliveryGuarantee != null) system.withDeliveryGuarantee(deliveryGuarantee);
        if (persistenceProvider != null) system.withPersistenceProvider(persistenceProvider);
        return system;
    }

    // ── accessors ──────────────────────────────────────────────────────────────

    public String systemId()              { return systemId; }
    public MetadataStore metadataStore()  { return metadataStore; }
    public MessagingSystem messagingSystem() { return messagingSystem; }
    public DeliveryGuarantee deliveryGuarantee() { return deliveryGuarantee; }
    public PersistenceProvider persistenceProvider() { return persistenceProvider; }

    // ── Builder ────────────────────────────────────────────────────────────────

    public static final class Builder {
        private String systemId = "cajun-node-" + UUID.randomUUID().toString().substring(0, 8);
        private MetadataStore metadataStore;
        private MessagingSystem messagingSystem;
        private DeliveryGuarantee deliveryGuarantee;
        private PersistenceProvider persistenceProvider;

        private Builder() {}

        public Builder systemId(String systemId) {
            this.systemId = systemId; return this;
        }

        public Builder metadataStore(MetadataStore metadataStore) {
            this.metadataStore = metadataStore; return this;
        }

        public Builder messagingSystem(MessagingSystem messagingSystem) {
            this.messagingSystem = messagingSystem; return this;
        }

        public Builder deliveryGuarantee(DeliveryGuarantee deliveryGuarantee) {
            this.deliveryGuarantee = deliveryGuarantee; return this;
        }

        public Builder persistenceProvider(PersistenceProvider persistenceProvider) {
            this.persistenceProvider = persistenceProvider; return this;
        }

        /** Builds and returns a fully configured but not-yet-started ClusterActorSystem. */
        public ClusterActorSystem build() {
            return new ClusterConfiguration(this).build();
        }
    }
}
