package com.cajunsystems.persistence;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A mock implementation of SnapshotStore for testing purposes.
 * This implementation doesn't actually serialize state objects, avoiding serialization issues in tests.
 *
 * @param <T> The type of the state
 */
public class MockSnapshotStore<T> implements SnapshotStore<T> {
    
    private final Map<String, SnapshotEntry<T>> snapshots = new ConcurrentHashMap<>();
    
    /**
     * Clear all snapshots for all actors.
     * This is useful for testing to reset the snapshot store state.
     */
    public void clear() {
        snapshots.clear();
    }
    
    /**
     * Get all snapshots stored in this mock store.
     * This is useful for testing to verify snapshot storage.
     * 
     * @return The map of actor IDs to snapshots
     */
    public Map<String, SnapshotEntry<T>> getSnapshots() {
        return snapshots;
    }
    
    @Override
    public CompletableFuture<Void> saveSnapshot(String actorId, T state, long sequenceNumber) {
        return CompletableFuture.runAsync(() -> {
            snapshots.put(actorId, new SnapshotEntry<T>(actorId, state, sequenceNumber, Instant.now()));
        });
    }
    
    @Override
    public CompletableFuture<Optional<SnapshotEntry<T>>> getLatestSnapshot(String actorId) {
        return CompletableFuture.supplyAsync(() -> {
            return Optional.ofNullable(snapshots.get(actorId));
        });
    }
    
    /**
     * Get the snapshot for a specific actor.
     * This is useful for testing to verify the snapshot contents.
     * 
     * @param actorId The actor ID
     * @return The snapshot entry, or null if none exists
     */
    public SnapshotEntry<T> getSnapshot(String actorId) {
        return snapshots.get(actorId);
    }
    
    @Override
    public CompletableFuture<Void> deleteSnapshots(String actorId) {
        return CompletableFuture.runAsync(() -> {
            snapshots.remove(actorId);
        });
    }
    
    @Override
    public void close() {
        // No resources to release
    }
}
