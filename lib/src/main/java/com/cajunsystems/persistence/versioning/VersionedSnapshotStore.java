package com.cajunsystems.persistence.versioning;

import com.cajunsystems.persistence.SnapshotEntry;
import com.cajunsystems.persistence.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A snapshot store that adds versioning support to an existing store.
 * 
 * <p>This wrapper automatically:
 * <ul>
 *   <li>Wraps state in {@link VersionedSnapshotEntry} when saving</li>
 *   <li>Migrates old state to current version when loading (if auto-migrate enabled)</li>
 *   <li>Tracks version metadata for all persisted snapshots</li>
 * </ul>
 * 
 * <p>Thread-safe and delegates actual persistence to the underlying store.
 *
 * @param <S> The state type
 */
public class VersionedSnapshotStore<S> implements SnapshotStore<S> {
    
    private static final Logger logger = LoggerFactory.getLogger(VersionedSnapshotStore.class);
    
    private final SnapshotStore<Object> delegate;
    private final MessageMigrator migrator;
    private final int currentVersion;
    private final boolean autoMigrate;
    
    /**
     * Creates a new VersionedSnapshotStore.
     *
     * @param delegate The underlying snapshot store
     * @param migrator The message migrator
     * @param currentVersion The current schema version
     * @param autoMigrate Whether to automatically migrate old state
     */
    @SuppressWarnings("unchecked")
    public VersionedSnapshotStore(SnapshotStore<S> delegate,
                                 MessageMigrator migrator,
                                 int currentVersion,
                                 boolean autoMigrate) {
        this.delegate = (SnapshotStore<Object>) delegate;
        this.migrator = migrator;
        this.currentVersion = currentVersion;
        this.autoMigrate = autoMigrate;
    }
    
    @Override
    public CompletableFuture<Void> saveSnapshot(String actorId, S state, long sequenceNumber) {
        // Wrap state in versioned entry
        VersionedSnapshotEntry<S> versionedEntry = new VersionedSnapshotEntry<>(
            currentVersion,
            actorId,
            state,
            sequenceNumber
        );
        
        return delegate.saveSnapshot(actorId, versionedEntry, sequenceNumber);
    }
    
    @Override
    public CompletableFuture<Optional<SnapshotEntry<S>>> getLatestSnapshot(String actorId) {
        return delegate.getLatestSnapshot(actorId)
            .thenApply(optEntry -> optEntry.map(this::unwrapAndMigrate));
    }
    
    @Override
    public CompletableFuture<Void> deleteSnapshots(String actorId) {
        return delegate.deleteSnapshots(actorId);
    }
    
    @Override
    public void close() {
        delegate.close();
    }
    
    @Override
    public boolean isHealthy() {
        return delegate.isHealthy();
    }
    
    /**
     * Unwraps a versioned snapshot entry and migrates if necessary.
     *
     * @param entry The snapshot entry to unwrap
     * @return The unwrapped and potentially migrated entry
     */
    @SuppressWarnings("unchecked")
    private SnapshotEntry<S> unwrapAndMigrate(SnapshotEntry<Object> entry) {
        Object state = entry.getState();
        
        // Handle versioned entries
        if (state instanceof VersionedSnapshotEntry<?> versioned) {
            int stateVersion = versioned.getVersion();
            S actualState = (S) versioned.getState();
            
            // Migrate if needed and auto-migrate is enabled
            if (autoMigrate && stateVersion != currentVersion) {
                if (actualState != null && migrator.hasMigrationPath(
                        actualState.getClass().getName(), stateVersion, currentVersion)) {
                    try {
                        actualState = migrator.migrate(actualState, stateVersion, currentVersion);
                        logger.debug("Migrated snapshot from v{} to v{} for actor {}",
                            stateVersion, currentVersion, entry.getActorId());
                    } catch (MigrationException e) {
                        logger.error("Failed to migrate snapshot for actor {}: {}",
                            entry.getActorId(), e.getMessage());
                        throw e;
                    }
                } else if (actualState != null) {
                    logger.warn("No migration path from v{} to v{} for state type {}",
                        stateVersion, currentVersion, actualState.getClass().getName());
                }
            }
            
            return new SnapshotEntry<>(
                entry.getActorId(),
                actualState,
                entry.getSequenceNumber(),
                entry.getTimestamp()
            );
        }
        
        // Handle unversioned entries (legacy data)
        logger.debug("Encountered unversioned snapshot for actor {}, treating as v{}",
            entry.getActorId(), PersistenceVersion.UNVERSIONED);
        
        S actualState = (S) state;
        
        // Migrate from unversioned to current if auto-migrate is enabled
        if (autoMigrate && actualState != null && migrator.hasMigrationPath(
                actualState.getClass().getName(),
                PersistenceVersion.UNVERSIONED,
                currentVersion)) {
            try {
                actualState = migrator.migrate(actualState, PersistenceVersion.UNVERSIONED, currentVersion);
                logger.debug("Migrated unversioned snapshot to v{} for actor {}",
                    currentVersion, entry.getActorId());
            } catch (MigrationException e) {
                logger.error("Failed to migrate unversioned snapshot for actor {}: {}",
                    entry.getActorId(), e.getMessage());
                throw e;
            }
        }
        
        return new SnapshotEntry<>(
            entry.getActorId(),
            actualState,
            entry.getSequenceNumber(),
            entry.getTimestamp()
        );
    }
}
