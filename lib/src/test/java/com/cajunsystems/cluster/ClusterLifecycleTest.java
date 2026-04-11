package com.cajunsystems.cluster;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lifecycle tests for planned drain-before-shutdown and migration operations.
 * Tests call system.start() and system.stop() and depend on the cluster's
 * heartbeat + reassignment scheduler. Marked @Tag("slow") for CI filtering.
 * No external services required.
 */
@Tag("slow")
public class ClusterLifecycleTest {

    private WatchableInMemoryMetadataStore metadataStore;
    private final List<ClusterActorSystem> startedSystems = new ArrayList<>();

    @BeforeEach
    void setUp() {
        metadataStore = new WatchableInMemoryMetadataStore();
    }

    @AfterEach
    void tearDown() {
        for (ClusterActorSystem system : startedSystems) {
            try {
                system.stop().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("Error stopping system during teardown: " + e.getMessage());
            }
        }
        startedSystems.clear();
    }

    private ClusterActorSystem createSystem(String id) {
        return new ClusterActorSystem(id, metadataStore, new InMemoryMessagingSystem(id));
    }

    private void startSystem(ClusterActorSystem system) throws Exception {
        system.start().get(5, TimeUnit.SECONDS);
        startedSystems.add(system);
    }

    private void stopSystem(ClusterActorSystem system) throws Exception {
        system.stop().get(5, TimeUnit.SECONDS);
        startedSystems.remove(system);
    }

    /**
     * Polls until an actor appears on the target node or the 30-second timeout expires.
     */
    private void awaitActorOnNode(ClusterManagementApi api, String actorId, String nodeId)
            throws Exception {
        for (int i = 0; i < 30; i++) {
            if (api.listActors(nodeId).join().contains(actorId)) return;
            Thread.sleep(1000);
        }
        fail("Actor '" + actorId + "' not found on node '" + nodeId + "' after 30 seconds");
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Test 1: Planned drain before shutdown — no orphaned actors.
     * Actors are explicitly migrated off the node before it shuts down, so no
     * background reassignment is required.
     */
    @Test
    void plannedDrain_beforeShutdown_noOrphanedActors() throws Exception {
        // Setup: 2 nodes
        ClusterActorSystem system1 = createSystem("system1");
        ClusterActorSystem system2 = createSystem("system2");

        InMemoryMessagingSystem ms1 = (InMemoryMessagingSystem) system1.getMessagingSystem();
        InMemoryMessagingSystem ms2 = (InMemoryMessagingSystem) system2.getMessagingSystem();
        ms1.connectTo(ms2);

        startSystem(system1);
        startSystem(system2);
        Thread.sleep(2000); // allow leader election

        // Register 3 actors on system1
        system1.register(TestActor.class, "actor1");
        system1.register(TestActor.class, "actor2");
        system1.register(TestActor.class, "actor3");
        Thread.sleep(1000); // allow assignments to propagate to the metadata store

        // Drain system1 before shutdown (planned migration)
        system1.getManagementApi().drainNode("system1").join();

        // system1 should be empty immediately after drain
        Set<String> onSystem1AfterDrain = system1.getManagementApi().listActors("system1").join();
        assertTrue(onSystem1AfterDrain.isEmpty(),
            "system1 should have no actors after drain, got: " + onSystem1AfterDrain);

        // Stop system1 (planned shutdown)
        stopSystem(system1);

        // All 3 actors should now be recorded on system2
        Set<String> onSystem2 = system2.getManagementApi().listActors("system2").join();
        assertTrue(onSystem2.contains("actor1"), "actor1 should be on system2, got: " + onSystem2);
        assertTrue(onSystem2.contains("actor2"), "actor2 should be on system2, got: " + onSystem2);
        assertTrue(onSystem2.contains("actor3"), "actor3 should be on system2, got: " + onSystem2);
    }

    /**
     * Test 2: Planned migration — moves a single actor to a target node and
     * updates the routing metadata so the cluster knows the new location.
     */
    @Test
    void plannedMigration_movesActorToTargetNode() throws Exception {
        // Setup: 2 nodes
        ClusterActorSystem system1 = createSystem("system1");
        ClusterActorSystem system2 = createSystem("system2");

        InMemoryMessagingSystem ms1 = (InMemoryMessagingSystem) system1.getMessagingSystem();
        InMemoryMessagingSystem ms2 = (InMemoryMessagingSystem) system2.getMessagingSystem();
        ms1.connectTo(ms2);

        startSystem(system1);
        startSystem(system2);
        Thread.sleep(2000); // allow leader election

        // Register actorX on system1
        system1.register(TestActor.class, "actorX");
        Thread.sleep(1000); // allow assignment to propagate

        // Confirm initial placement
        Set<String> initialOnSystem1 = system1.getManagementApi().listActors("system1").join();
        assertTrue(initialOnSystem1.contains("actorX"),
            "actorX should start on system1, got: " + initialOnSystem1);

        // Migrate actorX from system1 → system2
        system1.getManagementApi().migrateActor("actorX", "system2").join();

        Thread.sleep(1000); // allow metadata propagation

        // actorX should now be recorded on system2
        Set<String> onSystem2 = system2.getManagementApi().listActors("system2").join();
        assertTrue(onSystem2.contains("actorX"),
            "actorX should now be on system2, got: " + onSystem2);

        // actorX should no longer be recorded on system1
        Set<String> onSystem1 = system1.getManagementApi().listActors("system1").join();
        assertFalse(onSystem1.contains("actorX"),
            "actorX should no longer be on system1, got: " + onSystem1);
    }

    /**
     * Test 3: Graceful shutdown without prior drain — the surviving leader
     * detects the departed node and reassigns its actors automatically.
     * This simulates an unplanned failure (or omitting the drain step).
     * The background reassignment scheduler may take up to ~15 seconds to
     * kick in, so this test polls for up to 30 seconds.
     */
    @Test
    void gracefulShutdown_undrained_actorsReassignedByLeader() throws Exception {
        // Setup: 2 nodes
        ClusterActorSystem system1 = createSystem("system1");
        ClusterActorSystem system2 = createSystem("system2");

        InMemoryMessagingSystem ms1 = (InMemoryMessagingSystem) system1.getMessagingSystem();
        InMemoryMessagingSystem ms2 = (InMemoryMessagingSystem) system2.getMessagingSystem();
        ms1.connectTo(ms2);

        startSystem(system1);
        startSystem(system2);
        Thread.sleep(2000); // allow leader election

        // Register actor1 on system1
        system1.register(TestActor.class, "actor1");
        Thread.sleep(1000); // allow assignment to propagate

        // Confirm initial placement
        Set<String> initialOnSystem1 = system1.getManagementApi().listActors("system1").join();
        assertTrue(initialOnSystem1.contains("actor1"),
            "actor1 should start on system1, got: " + initialOnSystem1);

        // Stop system1 WITHOUT draining first (unplanned / omitted drain)
        stopSystem(system1);

        // Poll until the surviving leader (system2) reassigns actor1.
        // The background leader-election timer may take up to ~15 seconds
        // before system2 wins leadership and triggers reassignment.
        awaitActorOnNode(system2.getManagementApi(), "actor1", "system2");

        Set<String> onSystem2 = system2.getManagementApi().listActors("system2").join();
        assertTrue(onSystem2.contains("actor1"),
            "actor1 should be reassigned to system2 after system1 shutdown, got: " + onSystem2);
    }

    // ── Inner classes ─────────────────────────────────────────────────────────

    /**
     * Minimal test actor — no-op receive; only assignment and routing metadata are tested.
     */
    public static class TestActor extends Actor<Object> {

        public TestActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        @Override
        protected void receive(Object message) {
            // no-op — lifecycle tests care about assignment, not message content
        }
    }
}
