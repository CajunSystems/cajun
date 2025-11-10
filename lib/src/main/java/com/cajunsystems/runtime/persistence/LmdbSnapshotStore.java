package com.cajunsystems.runtime.persistence;

import com.cajunsystems.persistence.SnapshotEntry;
import com.cajunsystems.persistence.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * LMDB-based implementation of the SnapshotStore interface.
 * Provides high-performance snapshot storage using memory-mapped I/O.
 *
 * @param <S> The type of the snapshot state
 */
public class LmdbSnapshotStore<S> implements SnapshotStore<S> {
    private static final Logger logger = LoggerFactory.getLogger(LmdbSnapshotStore.class);
    
    private final Path snapshotDir;
    private final LmdbEnvironmentManager envManager;
    
    // Database names in LMDB
    private static final String SNAPSHOT_DB = "snapshots";
    private static final String METADATA_DB = "metadata";
    
    /**
     * Creates a new LmdbSnapshotStore with the specified directory.
     *
     * @param snapshotDir The directory to store LMDB database files
     * @param mapSize The memory map size for LMDB
     * @param maxDbs The maximum number of databases
     */
    public LmdbSnapshotStore(Path snapshotDir, long mapSize, int maxDbs) {
        this.snapshotDir = snapshotDir;
        this.envManager = new LmdbEnvironmentManager(snapshotDir, mapSize, maxDbs);
        
        try {
            Files.createDirectories(snapshotDir);
            envManager.initialize();
            
            logger.debug("LmdbSnapshotStore initialized for directory: {}", snapshotDir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize LMDB snapshot store: " + snapshotDir, e);
        }
    }
    
    @Override
    public CompletableFuture<Void> saveSnapshot(String actorId, S state, long sequenceNumber) {
        return CompletableFuture.runAsync(() -> {
            try {
                SnapshotEntry<S> entry = new SnapshotEntry<>(actorId, state, sequenceNumber, Instant.now());
                
                // Store in LMDB
                envManager.writeEntry(SNAPSHOT_DB, actorId, entry);
                
                // Update metadata
                envManager.writeEntry(METADATA_DB, actorId + ":latest_seq", sequenceNumber);
                envManager.writeEntry(METADATA_DB, actorId + ":latest_timestamp", entry.getTimestamp().toEpochMilli());
                
                logger.debug("Saved snapshot for actor {} with sequence number {}", actorId, sequenceNumber);
            } catch (Exception e) {
                logger.error("Failed to save snapshot for actor {}", actorId, e);
                throw new RuntimeException("Failed to save snapshot for actor " + actorId, e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Optional<SnapshotEntry<S>>> getLatestSnapshot(String actorId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                @SuppressWarnings("unchecked")
                SnapshotEntry<S> entry = envManager.readValue(SNAPSHOT_DB, actorId, SnapshotEntry.class);
                
                if (entry != null) {
                    logger.debug("Loaded latest snapshot for actor {} with sequence number {}", 
                                actorId, entry.getSequenceNumber());
                    return Optional.of(entry);
                } else {
                    logger.debug("No snapshot found for actor {}", actorId);
                    return Optional.empty();
                }
            } catch (Exception e) {
                logger.error("Failed to load latest snapshot for actor {}", actorId, e);
                throw new RuntimeException("Failed to load latest snapshot for actor " + actorId, e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> deleteSnapshots(String actorId) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Delete snapshot and metadata
                envManager.deleteRange(SNAPSHOT_DB, actorId, actorId + "\uFFFF");
                envManager.deleteRange(METADATA_DB, actorId + ":", actorId + ":\uFFFF");
                
                logger.debug("Deleted all snapshots for actor {}", actorId);
            } catch (Exception e) {
                logger.error("Failed to delete snapshots for actor {}", actorId, e);
                throw new RuntimeException("Failed to delete snapshots for actor " + actorId, e);
            }
        });
    }
    
    @Override
    public void close() {
        try {
            if (envManager != null) {
                envManager.close();
            }
            logger.debug("LmdbSnapshotStore closed for directory: {}", snapshotDir);
        } catch (Exception e) {
            logger.error("Error closing LMDB snapshot store", e);
        }
    }
    
    /**
     * Gets the snapshot directory.
     *
     * @return The snapshot directory path
     */
    protected Path getSnapshotDir() {
        return snapshotDir;
    }
}
