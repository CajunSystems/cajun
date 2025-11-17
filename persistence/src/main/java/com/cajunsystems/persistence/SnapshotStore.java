package com.cajunsystems.persistence;

import java.util.Collections;
import java.util.List;
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
    
    /**
     * Lists all snapshots for the specified actor, sorted by sequence number (most recent first).
     * 
     * <p>This method returns metadata about snapshots without loading the actual state data,
     * making it efficient for operations like pruning and cleanup.
     * 
     * @param actorId The ID of the actor
     * @return A CompletableFuture that completes with a list of snapshot metadata,
     *         or an empty list if no snapshots exist
     * @since 1.0
     */
    default CompletableFuture<List<SnapshotMetadata>> listSnapshots(String actorId) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }
    
    /**
     * Prunes old snapshots, keeping only the N most recent ones.
     * 
     * <p>Snapshots are ordered by sequence number, and only the most recent snapshots
     * (up to {@code keepCount}) are retained. Older snapshots are deleted.
     * 
     * <p>This operation is useful for managing disk space and preventing unbounded
     * snapshot accumulation.
     * 
     * <h2>Example</h2>
     * <pre>{@code
     * // Keep only the 2 most recent snapshots
     * int deleted = snapshotStore.pruneOldSnapshots("actor-1", 2).join();
     * System.out.println("Deleted " + deleted + " old snapshots");
     * }</pre>
     * 
     * @param actorId The ID of the actor
     * @param keepCount The number of snapshots to keep (must be at least 1)
     * @return A CompletableFuture that completes with the number of snapshots deleted
     * @since 1.0
     */
    default CompletableFuture<Integer> pruneOldSnapshots(String actorId, int keepCount) {
        return CompletableFuture.completedFuture(0);
    }
}
