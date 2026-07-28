package com.cajunsystems.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClusterManagementApi.migrateActor().
 * Uses InMemoryMetadataStore and NoopMessagingSystem — no etcd required.
 * Does NOT call system.start().
 */
class ClusterManagementApiMigrateTest {

    private InMemoryMetadataStore store;
    private ClusterActorSystem system;
    private ClusterManagementApi api;

    @BeforeEach
    void setUp() {
        store = new InMemoryMetadataStore();
        system = new ClusterActorSystem("source-node", store, new NoopMessagingSystem());
        api = system.getManagementApi();

        // Register nodes in the metadata store so listNodes() returns them
        store.put("cajun/node/source-node", "").join();
        store.put("cajun/node/target-node", "").join();
    }

    @AfterEach
    void tearDown() {
        system.stop();
    }

    @Test
    void migrateActor_updatesMetadataStore() {
        store.put("cajun/actor/actorX", "source-node").join();

        api.migrateActor("actorX", "target-node").join();

        String newAssignment = store.store.get("cajun/actor/actorX");
        assertEquals("target-node", newAssignment);
    }

    @Test
    void migrateActor_unknownTarget_returnsFailedFuture() {
        store.put("cajun/actor/actorX", "source-node").join();

        CompletableFuture<Void> future = api.migrateActor("actorX", "unknown-node");

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("unknown-node"));
    }

    @Test
    void migrateActor_actorNotCurrentlyAssigned_writesNewAssignment() {
        // No pre-existing assignment for actorZ

        api.migrateActor("actorZ", "target-node").join();

        String assignment = store.store.get("cajun/actor/actorZ");
        assertEquals("target-node", assignment);
    }

    @Test
    void listActors_afterMigration_reflectsNewAssignment() {
        store.put("cajun/actor/actorX", "source-node").join();

        api.migrateActor("actorX", "target-node").join();

        Set<String> onSource = api.listActors("source-node").join();
        Set<String> onTarget = api.listActors("target-node").join();

        assertFalse(onSource.contains("actorX"), "actorX should no longer be on source-node");
        assertTrue(onTarget.contains("actorX"), "actorX should now be on target-node");
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
