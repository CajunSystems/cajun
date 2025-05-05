package systems.cajun.cluster;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the RendezvousHashing class to verify its consistent hashing properties.
 */
public class RendezvousHashingTest {

    /**
     * Test that keys are assigned to nodes consistently.
     */
    @Test
    public void testBasicAssignment() {
        // Set up a collection of nodes
        Collection<String> nodes = List.of("node1", "node2", "node3");
        
        // Assign a key to a node
        String key = "actor1";
        Optional<String> assignedNode = RendezvousHashing.assignKey(key, nodes);
        
        // Verify that a node was assigned
        assertTrue(assignedNode.isPresent(), "No node was assigned to the key");
        
        // Verify that the same node is assigned when called again
        Optional<String> reassignedNode = RendezvousHashing.assignKey(key, nodes);
        assertEquals(assignedNode.get(), reassignedNode.get(), 
                "The same key was assigned to different nodes on subsequent calls");
    }
    
    /**
     * Test that different keys are distributed across nodes.
     */
    @Test
    public void testDistribution() {
        // Set up a collection of nodes
        Collection<String> nodes = List.of("node1", "node2", "node3", "node4", "node5");
        
        // Create a large number of keys
        int keyCount = 1000;
        Map<String, Integer> nodeCounts = new HashMap<>();
        
        // Initialize counts for each node
        for (String node : nodes) {
            nodeCounts.put(node, 0);
        }
        
        // Assign each key to a node and count assignments
        for (int i = 0; i < keyCount; i++) {
            String key = "actor" + i;
            Optional<String> assignedNode = RendezvousHashing.assignKey(key, nodes);
            assertTrue(assignedNode.isPresent(), "No node was assigned to key " + key);
            
            nodeCounts.put(assignedNode.get(), nodeCounts.get(assignedNode.get()) + 1);
        }
        
        // Verify that each node got some keys (distribution)
        for (String node : nodes) {
            int count = nodeCounts.get(node);
            assertTrue(count > 0, "Node " + node + " was not assigned any keys");
            
            // Check that the distribution is roughly even (within 30% of expected)
            double expected = keyCount / (double) nodes.size();
            double deviation = Math.abs(count - expected) / expected;
            assertTrue(deviation < 0.3,
                    STR."Distribution for node \{node} deviates too much from expected: \{count} vs expected ~\{expected}");
        }
    }
    
    /**
     * Test that when nodes are added or removed, most keys stay assigned to the same nodes.
     */
    @Test
    public void testConsistencyWhenNodesChange() {
        // Set up initial nodes
        List<String> initialNodes = List.of("node1", "node2", "node3", "node4");
        
        // Create a set of keys
        int keyCount = 1000;
        Map<String, String> initialAssignments = new HashMap<>();
        
        // Assign each key to a node
        for (int i = 0; i < keyCount; i++) {
            String key = "actor" + i;
            Optional<String> assignedNode = RendezvousHashing.assignKey(key, initialNodes);
            assertTrue(assignedNode.isPresent(), STR."No node was assigned to key \{key}");
            initialAssignments.put(key, assignedNode.get());
        }
        
        // Add a new node
        List<String> nodesWithAddition = new ArrayList<>(initialNodes);
        nodesWithAddition.add("node5");
        
        // Check how many assignments changed
        int changesAfterAddition = 0;
        for (int i = 0; i < keyCount; i++) {
            String key = "actor" + i;
            Optional<String> newAssignedNode = RendezvousHashing.assignKey(key, nodesWithAddition);
            assertTrue(newAssignedNode.isPresent(), STR."No node was assigned to key \{key}");
            
            if (!initialAssignments.get(key).equals(newAssignedNode.get())) {
                changesAfterAddition++;
            }
        }
        
        // Only a small percentage of keys should be reassigned when adding a node
        // Theoretically, with N nodes and adding 1 node, about 1/N keys should be reassigned
        double expectedReassignmentRate = 1.0 / initialNodes.size();
        double actualReassignmentRate = changesAfterAddition / (double) keyCount;
        
        assertTrue(Math.abs(actualReassignmentRate - expectedReassignmentRate) < 0.05,
                STR."Too many keys were reassigned after adding a node: \{changesAfterAddition} out of \{keyCount} (rate: \{actualReassignmentRate}, expected ~\{expectedReassignmentRate})");
        
        // Remove a node
        List<String> nodesWithRemoval = new ArrayList<>(initialNodes);
        nodesWithRemoval.remove("node3");
        
        // Check how many assignments changed
        int changesAfterRemoval = 0;
        for (int i = 0; i < keyCount; i++) {
            String key = "actor" + i;
            Optional<String> newAssignedNode = RendezvousHashing.assignKey(key, nodesWithRemoval);
            assertTrue(newAssignedNode.isPresent(), "No node was assigned to key " + key);
            
            if (initialAssignments.get(key).equals("node3") || 
                    !initialAssignments.get(key).equals(newAssignedNode.get())) {
                changesAfterRemoval++;
            }
        }
        
        // Only keys assigned to the removed node plus a small margin of error should be reassigned
        int keysOnRemovedNode = 0;
        for (String assignedNode : initialAssignments.values()) {
            if (assignedNode.equals("node3")) {
                keysOnRemovedNode++;
            }
        }
        
        // Allow for a small margin of error in our expected count
        int maxExpectedChanges = (int) (keysOnRemovedNode * 1.05);
        assertTrue(changesAfterRemoval <= maxExpectedChanges,
                STR."Too many keys were reassigned after removing a node: \{changesAfterRemoval} vs expected ~\{keysOnRemovedNode}");
    }
    
    /**
     * Test that the getTopNodes method returns the correct number of nodes in the right order.
     */
    @Test
    public void testGetTopNodes() {
        // Set up a collection of nodes
        Collection<String> nodes = List.of("node1", "node2", "node3", "node4", "node5");
        
        // Get top 3 nodes for a key
        String key = "actor1";
        List<String> topNodes = RendezvousHashing.getTopNodes(key, nodes, 3);
        
        // Verify that we got the right number of nodes
        assertEquals(3, topNodes.size(), "Wrong number of top nodes returned");
        
        // Verify that the nodes are unique
        assertEquals(3, new HashSet<>(topNodes).size(), "Duplicate nodes in the result");
        
        // Verify that the order is consistent
        List<String> topNodesAgain = RendezvousHashing.getTopNodes(key, nodes, 3);
        assertEquals(topNodes, topNodesAgain, "Top nodes order is not consistent");
        
        // Verify that requesting more nodes than available returns all nodes
        List<String> allNodes = RendezvousHashing.getTopNodes(key, nodes, 10);
        assertEquals(5, allNodes.size(), "Did not return all available nodes when requested");
        
        // Verify that the first nodes in the larger result match the top nodes
        for (int i = 0; i < topNodes.size(); i++) {
            assertEquals(topNodes.get(i), allNodes.get(i), 
                    "Top nodes are not consistent when requesting different counts");
        }
    }
    
    /**
     * Test edge cases like empty node collections and null inputs.
     */
    @Test
    public void testEdgeCases() {
        // Test with empty node collection
        Optional<String> emptyResult = RendezvousHashing.assignKey("actor1", Collections.emptyList());
        assertFalse(emptyResult.isPresent(), "Should return empty Optional for empty node collection");
        
        // Test with null node collection
        Optional<String> nullResult = RendezvousHashing.assignKey("actor1", null);
        assertFalse(nullResult.isPresent(), "Should return empty Optional for null node collection");
        
        // Test getTopNodes with empty node collection
        List<String> emptyTopNodes = RendezvousHashing.getTopNodes("actor1", Collections.emptyList(), 3);
        assertTrue(emptyTopNodes.isEmpty(), "Should return empty list for empty node collection");
        
        // Test getTopNodes with null node collection
        List<String> nullTopNodes = RendezvousHashing.getTopNodes("actor1", null, 3);
        assertTrue(nullTopNodes.isEmpty(), "Should return empty list for null node collection");
    }
}
