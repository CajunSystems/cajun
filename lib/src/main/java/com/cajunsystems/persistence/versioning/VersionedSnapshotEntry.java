package com.cajunsystems.persistence.versioning;

import com.cajunsystems.persistence.SnapshotEntry;

import java.time.Instant;

/**
 * A versioned wrapper around SnapshotEntry that tracks the schema version of the state.
 * This enables state migration during recovery when the state schema evolves.
 * 
 * <p>The version number represents the schema version of the state at the time it was persisted.
 * During recovery, if the current schema version is higher than the persisted version,
 * the state can be migrated to the current version using registered migration functions.
 * 
 * <p>Example usage:
 * <pre>{@code
 * // Create a versioned snapshot entry
 * SnapshotEntry<OrderState> entry = new SnapshotEntry<>("order-actor", state, 100);
 * VersionedSnapshotEntry<OrderState> versioned = new VersionedSnapshotEntry<>(2, entry);
 * 
 * // Later, during recovery
 * if (versioned.getVersion() < CURRENT_VERSION) {
 *     OrderState migrated = migrator.migrate(versioned.getState(), 
 *                                            versioned.getVersion(), 
 *                                            CURRENT_VERSION);
 * }
 * }</pre>
 *
 * @param <S> The type of the state
 */
public class VersionedSnapshotEntry<S> extends SnapshotEntry<S> {
    private static final long serialVersionUID = 1L;
    
    private final int version;
    
    /**
     * Creates a new versioned snapshot entry from an existing snapshot entry.
     *
     * @param version The schema version of the state
     * @param entry The original snapshot entry to wrap
     * @throws IllegalArgumentException if version is negative
     */
    public VersionedSnapshotEntry(int version, SnapshotEntry<S> entry) {
        super(entry.getActorId(), entry.getState(), entry.getSequenceNumber(), entry.getTimestamp());
        
        if (version < 0) {
            throw new IllegalArgumentException("Version must be non-negative, got: " + version);
        }
        
        this.version = version;
    }
    
    /**
     * Creates a new versioned snapshot entry with all parameters.
     *
     * @param version The schema version of the state
     * @param actorId The ID of the actor
     * @param state The state snapshot
     * @param sequenceNumber The sequence number of the last message processed to reach this state
     * @param timestamp The timestamp when the snapshot was taken
     * @throws IllegalArgumentException if version is negative
     */
    public VersionedSnapshotEntry(int version, String actorId, S state, long sequenceNumber, Instant timestamp) {
        super(actorId, state, sequenceNumber, timestamp);
        
        if (version < 0) {
            throw new IllegalArgumentException("Version must be non-negative, got: " + version);
        }
        
        this.version = version;
    }
    
    /**
     * Creates a new versioned snapshot entry with the current timestamp.
     *
     * @param version The schema version of the state
     * @param actorId The ID of the actor
     * @param state The state snapshot
     * @param sequenceNumber The sequence number of the last message processed to reach this state
     * @throws IllegalArgumentException if version is negative
     */
    public VersionedSnapshotEntry(int version, String actorId, S state, long sequenceNumber) {
        this(version, actorId, state, sequenceNumber, Instant.now());
    }
    
    /**
     * Gets the schema version of the state.
     * This represents the version of the state schema at the time the snapshot was persisted.
     *
     * @return The schema version
     */
    public int getVersion() {
        return version;
    }
    
    /**
     * Checks if this snapshot needs migration to the specified target version.
     *
     * @param targetVersion The target schema version
     * @return true if migration is needed (current version < target version)
     */
    public boolean needsMigration(int targetVersion) {
        return version < targetVersion;
    }
    
    /**
     * Creates a new versioned snapshot entry with an updated state after migration.
     * The version is updated to the target version.
     *
     * @param migratedState The migrated state
     * @param targetVersion The target version after migration
     * @return A new VersionedSnapshotEntry with the migrated state and updated version
     */
    public VersionedSnapshotEntry<S> withMigratedState(S migratedState, int targetVersion) {
        return new VersionedSnapshotEntry<>(
            targetVersion,
            getActorId(),
            migratedState,
            getSequenceNumber(),
            getTimestamp()
        );
    }
    
    /**
     * Converts this versioned entry back to a plain SnapshotEntry.
     * This is useful when the version information is no longer needed.
     *
     * @return A plain SnapshotEntry without version information
     */
    public SnapshotEntry<S> toPlainEntry() {
        return new SnapshotEntry<>(
            getActorId(),
            getState(),
            getSequenceNumber(),
            getTimestamp()
        );
    }
    
    @Override
    public String toString() {
        return "VersionedSnapshotEntry{" +
                "version=" + version +
                ", actorId='" + getActorId() + '\'' +
                ", state=" + getState() +
                ", sequenceNumber=" + getSequenceNumber() +
                ", timestamp=" + getTimestamp() +
                '}';
    }
}
