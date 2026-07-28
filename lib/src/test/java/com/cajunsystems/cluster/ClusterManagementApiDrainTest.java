package com.cajunsystems.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClusterManagementApi.drainNode().
 * Uses InMemoryMetadataStore and NoopMessagingSystem — no etcd required.
 * Does NOT call system.start().
 */
class ClusterManagementApiDrainTest {

    private InMemoryMetadataStore store;
    private ClusterActorSystem system;
    private ClusterManagementApi api;

    @BeforeEach
    void setUp() {
        store = new InMemoryMetadataStore();
        system = new ClusterActorSystem("nodeA", store, new NoopMessagingSystem());
        api = system.getManagementApi();
    }

    @AfterEach
    void tearDown() {
        system.stop();
    }

    @Test
    void drainNode_migratesAllActorsToOtherNodes() {
        // Register 3 nodes, 3 actors all on nodeA
        store.put("cajun/node/nodeA", "").join();
        store.put("cajun/node/nodeB", "").join();
        store.put("cajun/node/nodeC", "").join();
        store.put("cajun/actor/actor1", "nodeA").join();
        store.put("cajun/actor/actor2", "nodeA").join();
        store.put("cajun/actor/actor3", "nodeA").join();

        api.drainNode("nodeA").join();

        // Verify no actors remain on nodeA
        Set<String> onNodeA = api.listActors("nodeA").join();
        assertTrue(onNodeA.isEmpty(), "No actors should remain on drained nodeA");

        // Verify all actors are now assigned to nodeB or nodeC
        Set<String> onNodeB = api.listActors("nodeB").join();
        Set<String> onNodeC = api.listActors("nodeC").join();
        Set<String> allMigrated = new java.util.HashSet<>(onNodeB);
        allMigrated.addAll(onNodeC);
        assertEquals(Set.of("actor1", "actor2", "actor3"), allMigrated);
    }

    @Test
    void drainNode_emptyNode_completesSuccessfully() {
        store.put("cajun/node/nodeA", "").join();
        store.put("cajun/node/nodeB", "").join();
        // No actors on nodeA

        assertDoesNotThrow(() -> api.drainNode("nodeA").join());
    }

    @Test
    void drainNode_singleNodeCluster_failsWithIllegalState() {
        store.put("cajun/node/nodeA", "").join();
        // Only one node — drain would leave no home for actors

        CompletableFuture<Void> future = api.drainNode("nodeA");

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("no other nodes available"));
    }

    @Test
    void drainNode_usesConsistentHashingForPlacement() {
        // 5 nodes, drain nodeA — verify actors are distributed (not all on one node)
        store.put("cajun/node/nodeA", "").join();
        store.put("cajun/node/nodeB", "").join();
        store.put("cajun/node/nodeC", "").join();
        store.put("cajun/node/nodeD", "").join();
        store.put("cajun/node/nodeE", "").join();

        // 10 actors on nodeA
        for (int i = 1; i <= 10; i++) {
            store.put("cajun/actor/actor" + i, "nodeA").join();
        }

        api.drainNode("nodeA").join();

        // Count actors per remaining node
        Map<String, Long> distribution = store.store.entrySet().stream()
                .filter(e -> e.getKey().startsWith("cajun/actor/"))
                .collect(Collectors.groupingBy(Map.Entry::getValue, Collectors.counting()));

        // nodeA should have 0
        assertEquals(0L, distribution.getOrDefault("nodeA", 0L));
        // At least 2 of the remaining nodes should have actors (basic distribution check)
        long nodesWithActors = distribution.entrySet().stream()
                .filter(e -> !e.getKey().equals("nodeA") && e.getValue() > 0)
                .count();
        assertTrue(nodesWithActors >= 2, "Actors should be distributed across multiple nodes, got: " + distribution);
    }

    @Test
    void drainNode_rejoinAfterDrain() {
        // Setup: 3 nodes, 5 actors on nodeA
        store.put("cajun/node/nodeA", "").join();
        store.put("cajun/node/nodeB", "").join();
        store.put("cajun/node/nodeC", "").join();
        for (int i = 1; i <= 5; i++) {
            store.put("cajun/actor/actor" + i, "nodeA").join();
        }

        // Drain nodeA
        api.drainNode("nodeA").join();

        // Verify old actors moved off nodeA
        Set<String> onNodeA = api.listActors("nodeA").join();
        assertTrue(onNodeA.isEmpty(), "Old actors should have been drained from nodeA");

        // Simulate rejoin: register a new actor on nodeA
        store.put("cajun/actor/newActor", "nodeA").join();

        // nodeA is still in the node registry (drain does not remove the node itself)
        Set<String> nodes = api.listNodes().join();
        assertTrue(nodes.contains("nodeA"), "nodeA should still be a known node after drain");

        // New actor is on nodeA; old actors are elsewhere
        Set<String> onNodeAAfterRejoin = api.listActors("nodeA").join();
        assertEquals(Set.of("newActor"), onNodeAAfterRejoin);

        Set<String> onBandC = new java.util.HashSet<>(api.listActors("nodeB").join());
        onBandC.addAll(api.listActors("nodeC").join());
        assertEquals(5, onBandC.size(), "All 5 original actors should be on nodeB or nodeC");
    }

    // ── NoopMessagingSystem stub ──────────────────────────────────────────────

    private static class NoopMessagingSystem implements MessagingSystem {
        @Override
        public <Message> CompletableFuture<Void> sendMessage(String t, String a, Message m) {
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
