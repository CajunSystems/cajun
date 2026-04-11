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
 * Chaos tests for cluster node failures and recovery.
 * These tests call system.start() and system.stop() and depend on the cluster's
 * heartbeat + reassignment scheduler. Marked @Tag("slow") for CI filtering.
 * No external services required.
 */
@Tag("slow")
public class ClusterChaosTest {

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

    /**
     * Creates a ClusterActorSystem and registers it for cleanup.
     */
    private ClusterActorSystem createSystem(String id) {
        return new ClusterActorSystem(id, metadataStore, new InMemoryMessagingSystem(id));
    }

    /**
     * Starts a system and registers it for cleanup.
     */
    private void startSystem(ClusterActorSystem system) throws Exception {
        system.start().get(5, TimeUnit.SECONDS);
        startedSystems.add(system);
    }

    /**
     * Stops a system and removes it from the cleanup list.
     */
    private void stopSystem(ClusterActorSystem system) throws Exception {
        system.stop().get(5, TimeUnit.SECONDS);
        startedSystems.remove(system);
    }

    /**
     * Polls until an actor appears on the target node or the timeout expires.
     */
    private void awaitActorOnNode(ClusterManagementApi api, String actorId, String nodeId)
            throws Exception {
        for (int i = 0; i < 20; i++) {
            if (api.listActors(nodeId).join().contains(actorId)) return;
            Thread.sleep(1000);
        }
        fail("Actor '" + actorId + "' not found on node '" + nodeId + "' after 20 seconds");
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Test 1: Single node failure — actors reassigned to a surviving node.
     * Uses drain-then-stop for deterministic reassignment without waiting for
     * the background heartbeat scheduler.
     */
    @Test
    void singleNodeFailure_actorsReassignedToSurvivingNode() throws Exception {
        // Setup: 3 nodes, all started
        ClusterActorSystem system1 = createSystem("system1");
        ClusterActorSystem system2 = createSystem("system2");
        ClusterActorSystem system3 = createSystem("system3");

        InMemoryMessagingSystem ms1 = (InMemoryMessagingSystem) system1.getMessagingSystem();
        InMemoryMessagingSystem ms2 = (InMemoryMessagingSystem) system2.getMessagingSystem();
        InMemoryMessagingSystem ms3 = (InMemoryMessagingSystem) system3.getMessagingSystem();
        ms1.connectTo(ms2);
        ms1.connectTo(ms3);
        ms2.connectTo(ms3);

        startSystem(system1);
        startSystem(system2);
        startSystem(system3);

        // Allow leader election to complete
        Thread.sleep(2000);

        // Register actor1 on system1
        system1.register(TestActor.class, "actor1");
        Thread.sleep(2000); // allow assignment to propagate

        // Drain system1 before stopping (deterministic reassignment)
        system1.getManagementApi().drainNode("system1").join();

        // Verify actor1 moved off system1 before we stop it
        Set<String> onSystem1 = system1.getManagementApi().listActors("system1").join();
        assertTrue(onSystem1.isEmpty(), "actor1 should have been drained from system1");

        // Stop system1 (simulated failure after drain)
        stopSystem(system1);

        // Assert: actor1 is now on system2 or system3
        ClusterManagementApi api2 = system2.getManagementApi();
        Set<String> onSystem2 = api2.listActors("system2").join();
        Set<String> onSystem3 = system3.getManagementApi().listActors("system3").join();
        assertTrue(
            onSystem2.contains("actor1") || onSystem3.contains("actor1"),
            "actor1 should have been reassigned to system2 or system3. " +
            "system2=" + onSystem2 + ", system3=" + onSystem3
        );
    }

    /**
     * Test 2: Sequential node failures — actors concentrate on the last survivor.
     * Uses drain-then-stop for deterministic reassignment.
     */
    @Test
    void sequentialNodeFailures_actorsConcentrateOnSurvivorNode() throws Exception {
        // Setup: 3 nodes, all started
        ClusterActorSystem system1 = createSystem("system1");
        ClusterActorSystem system2 = createSystem("system2");
        ClusterActorSystem system3 = createSystem("system3");

        InMemoryMessagingSystem ms1 = (InMemoryMessagingSystem) system1.getMessagingSystem();
        InMemoryMessagingSystem ms2 = (InMemoryMessagingSystem) system2.getMessagingSystem();
        InMemoryMessagingSystem ms3 = (InMemoryMessagingSystem) system3.getMessagingSystem();
        ms1.connectTo(ms2);
        ms1.connectTo(ms3);
        ms2.connectTo(ms3);

        startSystem(system1);
        startSystem(system2);
        startSystem(system3);

        // Allow leader election to complete
        Thread.sleep(2000);

        // Register actors on system1 and system2
        system1.register(TestActor.class, "actor1");
        system2.register(TestActor.class, "actor2");
        Thread.sleep(2000); // allow assignments to propagate

        // Drain and stop system1
        system1.getManagementApi().drainNode("system1").join();
        stopSystem(system1);
        Thread.sleep(2000); // allow node deregistration to propagate

        // Drain and stop system2
        system2.getManagementApi().drainNode("system2").join();
        stopSystem(system2);
        Thread.sleep(2000);

        // Assert: system3 has both actor1 and actor2
        ClusterManagementApi api3 = system3.getManagementApi();
        Set<String> onSystem3 = api3.listActors("system3").join();
        assertTrue(onSystem3.contains("actor1"),
            "actor1 should be on system3 after sequential failures, got: " + onSystem3);
        assertTrue(onSystem3.contains("actor2"),
            "actor2 should be on system3 after sequential failures, got: " + onSystem3);

        // Verify system1 and system2 are no longer in the cluster node list
        Set<String> nodes = api3.listNodes().join();
        assertFalse(nodes.contains("system1"), "system1 should not be in cluster nodes: " + nodes);
        assertFalse(nodes.contains("system2"), "system2 should not be in cluster nodes: " + nodes);
    }

    /**
     * Test 3: Node rejoin — a new replacement node is discovered by the cluster.
     */
    @Test
    void nodeRejoin_newNodeDiscoveredByCluster() throws Exception {
        // Setup: 2 nodes started
        ClusterActorSystem system1 = createSystem("system1");
        ClusterActorSystem system2 = createSystem("system2");

        InMemoryMessagingSystem ms1 = (InMemoryMessagingSystem) system1.getMessagingSystem();
        InMemoryMessagingSystem ms2 = (InMemoryMessagingSystem) system2.getMessagingSystem();
        ms1.connectTo(ms2);

        startSystem(system1);
        startSystem(system2);

        // Allow leader election
        Thread.sleep(2000);

        // Register actor1 on system1
        system1.register(TestActor.class, "actor1");
        Thread.sleep(1000);

        // Drain and stop system1
        system1.getManagementApi().drainNode("system1").join();
        stopSystem(system1);
        Thread.sleep(2000);

        // Create and start system3 as a replacement node
        ClusterActorSystem system3 = createSystem("system3");
        InMemoryMessagingSystem ms3 = (InMemoryMessagingSystem) system3.getMessagingSystem();
        ms3.connectTo(ms2);

        startSystem(system3);
        Thread.sleep(2000); // allow system3 to register and be discovered

        // Assert: system2 can see system3 in the cluster
        Set<String> nodes = system2.getManagementApi().listNodes().join();
        assertTrue(nodes.contains("system3"),
            "system3 should be discovered by system2, known nodes: " + nodes);

        // Assert: new actors can be registered on system3 without exception
        assertDoesNotThrow(() -> system3.register(TestActor.class, "actor2"),
            "Should be able to register a new actor on system3 after rejoin");
    }

    // ── Inner classes ─────────────────────────────────────────────────────────

    /**
     * Minimal test actor — no-op receive; only routing behaviour is tested.
     */
    public static class TestActor extends Actor<Object> {

        public TestActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        @Override
        protected void receive(Object message) {
            // no-op — chaos tests only care about routing/assignment, not message content
        }
    }
}
