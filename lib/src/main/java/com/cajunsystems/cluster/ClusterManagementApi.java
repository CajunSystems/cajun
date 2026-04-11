package com.cajunsystems.cluster;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Programmatic API for cluster operations.
 *
 * <p>Obtain via {@link ClusterActorSystem#getManagementApi()}.
 */
public interface ClusterManagementApi {

    /** Returns the IDs of all nodes currently registered in the cluster. */
    CompletableFuture<Set<String>> listNodes();

    /**
     * Returns the IDs of all actors currently assigned to the given node.
     * Queries the metadata store directly (not the local TTL cache).
     */
    CompletableFuture<Set<String>> listActors(String nodeId);

    /**
     * Migrates an actor to the specified target node.
     * Updates the metadata store assignment; if the actor is running locally,
     * it is stopped so it can be lazily recovered on the target.
     *
     * @param actorId      the actor to migrate
     * @param targetNodeId the destination node
     */
    CompletableFuture<Void> migrateActor(String actorId, String targetNodeId);

    /**
     * Drains a node: migrates all actors away from {@code nodeId} to other
     * available nodes using rendezvous hashing, excluding the drained node.
     * Returns when all actor assignments have been updated in the metadata store.
     *
     * @param nodeId the node to drain
     */
    CompletableFuture<Void> drainNode(String nodeId);
}
