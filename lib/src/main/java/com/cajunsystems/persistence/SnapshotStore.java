package com.cajunsystems.persistence;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for snapshot persistence operations.
 * Provides methods to store and retrieve snapshots of actor state along with metadata.
 *
 * @param <S> The type of the state
 */
public interface SnapshotStore<S> {
    
    /**
     * Stores a snapshot of the actor's state along with the sequence number of the last message
     * that was processed to reach this state.
     *
     * @param actorId The ID of the actor
     * @param state The state to snapshot
     * @param sequenceNumber The sequence number of the last processed message
     * @return A CompletableFuture that completes when the operation is done
     */
    CompletableFuture<Void> saveSnapshot(String actorId, S state, long sequenceNumber);
    
    /**
     * Retrieves the latest snapshot for the specified actor.
     *
     * @param actorId The ID of the actor
     * @return A CompletableFuture that completes with a SnapshotEntry containing the state and sequence number,
     *         or empty if no snapshot exists
     */
    CompletableFuture<Optional<SnapshotEntry<S>>> getLatestSnapshot(String actorId);
    
    /**
     * Deletes all snapshots for the specified actor.
     *
     * @param actorId The ID of the actor
     * @return A CompletableFuture that completes when the operation is done
     */
    CompletableFuture<Void> deleteSnapshots(String actorId);
    
    /**
     * Closes the snapshot store, releasing any resources.
     */
    void close();
    
    /**
     * Checks if the snapshot store is healthy and operational.
     * 
     * @return true if the snapshot store is healthy, false otherwise
     */
    default boolean isHealthy() {
        return true;
    }
}
