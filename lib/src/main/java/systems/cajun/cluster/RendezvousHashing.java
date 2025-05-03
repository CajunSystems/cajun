package systems.cajun.cluster;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Implementation of Rendezvous Hashing (Highest Random Weight) for consistent actor assignment.
 * This algorithm provides a way to assign actors to nodes in a distributed system in a consistent manner,
 * minimizing reassignments when nodes join or leave the cluster.
 */
public class RendezvousHashing {

    private static final String HASH_ALGORITHM = "SHA-256";
    
    /**
     * Assigns a key to a node using rendezvous hashing.
     *
     * @param key The key to assign (actor ID)
     * @param nodes The collection of available nodes (actor system IDs)
     * @return The selected node, or empty if no nodes are available
     */
    public static Optional<String> assignKey(String key, Collection<String> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Optional.empty();
        }
        
        String selectedNode = null;
        long highestScore = Long.MIN_VALUE;
        
        for (String node : nodes) {
            long score = computeScore(key, node);
            if (score > highestScore) {
                highestScore = score;
                selectedNode = node;
            }
        }
        
        return Optional.ofNullable(selectedNode);
    }
    
    /**
     * Computes the score for a key-node pair.
     *
     * @param key The key
     * @param node The node
     * @return The score (higher is better)
     */
    private static long computeScore(String key, String node) {
        try {
            MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
            String combined = key + ":" + node;
            byte[] hashBytes = md.digest(combined.getBytes(StandardCharsets.UTF_8));
            
            // Convert the first 8 bytes of the hash to a long
            long hash = 0;
            for (int i = 0; i < 8 && i < hashBytes.length; i++) {
                hash = (hash << 8) | (hashBytes[i] & 0xff);
            }
            
            return hash;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to compute hash", e);
        }
    }
    
    /**
     * Gets the top N nodes for a key, ordered by score (highest first).
     * This is useful for replication or fallback.
     *
     * @param key The key to assign
     * @param nodes The collection of available nodes
     * @param count The number of nodes to return
     * @return A list of the top N nodes, or fewer if not enough nodes are available
     */
    public static List<String> getTopNodes(String key, Collection<String> nodes, int count) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        
        return nodes.stream()
                .map(node -> new NodeScore(node, computeScore(key, node)))
                .sorted((a, b) -> Long.compare(b.score, a.score)) // Sort by score, descending
                .limit(count)
                .map(nodeScore -> nodeScore.node)
                .collect(Collectors.toList());
    }
    
    /**
     * Helper class to hold a node and its score.
     */
    private static class NodeScore {
        final String node;
        final long score;
        
        NodeScore(String node, long score) {
            this.node = node;
            this.score = score;
        }
    }
}
