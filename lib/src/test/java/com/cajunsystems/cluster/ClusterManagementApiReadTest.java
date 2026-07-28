package com.cajunsystems.cluster;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClusterConfiguration builder and ClusterManagementApi read operations.
 * Uses in-memory stubs — no etcd, no Redis, no real messaging system required.
 * Does NOT call system.start().
 */
class ClusterManagementApiReadTest {

    // ── Shared helpers ─────────────────────────────────────────────────────────

    private InMemoryMetadataStore newStore() {
        return new InMemoryMetadataStore();
    }

    private NoopMessagingSystem newMessaging() {
        return new NoopMessagingSystem();
    }

    // ── ClusterConfiguration builder tests ────────────────────────────────────

    @Test
    void builder_withAllFields_buildsSystem() {
        InMemoryMetadataStore store = newStore();
        NoopMessagingSystem messaging = newMessaging();

        ClusterActorSystem system = ClusterConfiguration.builder()
                .systemId("node-1")
                .metadataStore(store)
                .messagingSystem(messaging)
                .deliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .build();

        assertNotNull(system);
        system.stop();
    }

    @Test
    void builder_withoutSystemId_generatesDefaultId() {
        InMemoryMetadataStore store = newStore();
        NoopMessagingSystem messaging = newMessaging();

        ClusterActorSystem system = ClusterConfiguration.builder()
                .metadataStore(store)
                .messagingSystem(messaging)
                .build();

        assertNotNull(system.getSystemId());
        assertFalse(system.getSystemId().isBlank());
        system.stop();
    }

    @Test
    void builder_missingMetadataStore_throwsIllegalState() {
        NoopMessagingSystem messaging = newMessaging();

        assertThrows(IllegalStateException.class, () ->
                ClusterConfiguration.builder()
                        .messagingSystem(messaging)
                        .build()
        );
    }

    @Test
    void builder_missingMessagingSystem_throwsIllegalState() {
        InMemoryMetadataStore store = newStore();

        assertThrows(IllegalStateException.class, () ->
                ClusterConfiguration.builder()
                        .metadataStore(store)
                        .build()
        );
    }

    @Test
    void builder_withSystemId_usesProvidedId() {
        InMemoryMetadataStore store = newStore();
        NoopMessagingSystem messaging = newMessaging();

        ClusterActorSystem system = ClusterConfiguration.builder()
                .systemId("my-node")
                .metadataStore(store)
                .messagingSystem(messaging)
                .build();

        assertEquals("my-node", system.getSystemId());
        system.stop();
    }

    // ── ClusterManagementApi read tests ───────────────────────────────────────

    @Test
    void listNodes_returnsRegisteredNodes() {
        InMemoryMetadataStore store = newStore();
        store.put("cajun/node/nodeA", "").join();
        store.put("cajun/node/nodeB", "").join();

        ClusterActorSystem system = new ClusterActorSystem("test-node", store, newMessaging());
        ClusterManagementApi api = system.getManagementApi();

        Set<String> nodes = api.listNodes().join();
        assertEquals(Set.of("nodeA", "nodeB"), nodes);
        system.stop();
    }

    @Test
    void listNodes_emptyCluster_returnsEmptySet() {
        InMemoryMetadataStore store = newStore();

        ClusterActorSystem system = new ClusterActorSystem("test-node", store, newMessaging());
        ClusterManagementApi api = system.getManagementApi();

        Set<String> nodes = api.listNodes().join();
        assertTrue(nodes.isEmpty());
        system.stop();
    }

    @Test
    void listActors_returnsActorsForNode() {
        InMemoryMetadataStore store = newStore();
        store.put("cajun/actor/actorX", "nodeA").join();
        store.put("cajun/actor/actorY", "nodeB").join();

        ClusterActorSystem system = new ClusterActorSystem("test-node", store, newMessaging());
        ClusterManagementApi api = system.getManagementApi();

        Set<String> actors = api.listActors("nodeA").join();
        assertEquals(Set.of("actorX"), actors);
        system.stop();
    }

    @Test
    void listActors_noActorsOnNode_returnsEmptySet() {
        InMemoryMetadataStore store = newStore();
        store.put("cajun/actor/actorX", "nodeB").join();

        ClusterActorSystem system = new ClusterActorSystem("test-node", store, newMessaging());
        ClusterManagementApi api = system.getManagementApi();

        Set<String> actors = api.listActors("nodeA").join();
        assertTrue(actors.isEmpty());
        system.stop();
    }

    @Test
    void listActors_multipleActorsOnSameNode_returnsAll() {
        InMemoryMetadataStore store = newStore();
        store.put("cajun/actor/actor1", "nodeA").join();
        store.put("cajun/actor/actor2", "nodeA").join();
        store.put("cajun/actor/actor3", "nodeA").join();
        store.put("cajun/actor/actor4", "nodeB").join();

        ClusterActorSystem system = new ClusterActorSystem("test-node", store, newMessaging());
        ClusterManagementApi api = system.getManagementApi();

        Set<String> actors = api.listActors("nodeA").join();
        assertEquals(Set.of("actor1", "actor2", "actor3"), actors);
        system.stop();
    }

    // ── NoopMessagingSystem stub ───────────────────────────────────────────────

    private static class NoopMessagingSystem implements MessagingSystem {
        @Override
        public <Message> CompletableFuture<Void> sendMessage(String targetSystemId, String actorId, Message message) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> registerMessageHandler(MessageHandler handler) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> start() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> stop() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
