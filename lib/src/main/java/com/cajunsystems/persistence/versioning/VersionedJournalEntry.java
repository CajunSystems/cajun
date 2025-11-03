package com.cajunsystems.persistence.versioning;

import com.cajunsystems.persistence.JournalEntry;

import java.time.Instant;

/**
 * A versioned wrapper around JournalEntry that tracks the schema version of the message.
 * This enables message migration during recovery when the message schema evolves.
 * 
 * <p>The version number represents the schema version of the message at the time it was persisted.
 * During recovery, if the current schema version is higher than the persisted version,
 * the message can be migrated to the current version using registered migration functions.
 * 
 * <p>Example usage:
 * <pre>{@code
 * // Create a versioned journal entry
 * JournalEntry<OrderMessage> entry = new JournalEntry<>(1, "order-actor", message);
 * VersionedJournalEntry<OrderMessage> versioned = new VersionedJournalEntry<>(2, entry);
 * 
 * // Later, during recovery
 * if (versioned.getVersion() < CURRENT_VERSION) {
 *     OrderMessage migrated = migrator.migrate(versioned.getMessage(), 
 *                                              versioned.getVersion(), 
 *                                              CURRENT_VERSION);
 * }
 * }</pre>
 *
 * @param <M> The type of the message
 */
public class VersionedJournalEntry<M> extends JournalEntry<M> {
    private static final long serialVersionUID = 1L;
    
    /** The schema version of the message. */
    private final int version;
    
    /**
     * Creates a new versioned journal entry from an existing journal entry.
     *
     * @param version The schema version of the message
     * @param entry The original journal entry to wrap
     * @throws IllegalArgumentException if version is negative or entry is null
     */
    public VersionedJournalEntry(int version, JournalEntry<M> entry) {
        super(entry.getSequenceNumber(), entry.getActorId(), entry.getMessage(), entry.getTimestamp());
        
        if (version < 0) {
            throw new IllegalArgumentException("Version must be non-negative, got: " + version);
        }
        
        this.version = version;
    }
    
    /**
     * Creates a new versioned journal entry with all parameters.
     *
     * @param version The schema version of the message
     * @param sequenceNumber The sequence number of the entry
     * @param actorId The ID of the actor the message is for
     * @param message The message
     * @param timestamp The timestamp when the message was journaled
     * @throws IllegalArgumentException if version is negative
     */
    public VersionedJournalEntry(int version, long sequenceNumber, String actorId, M message, Instant timestamp) {
        super(sequenceNumber, actorId, message, timestamp);
        
        if (version < 0) {
            throw new IllegalArgumentException("Version must be non-negative, got: " + version);
        }
        
        this.version = version;
    }
    
    /**
     * Creates a new versioned journal entry with the current timestamp.
     *
     * @param version The schema version of the message
     * @param sequenceNumber The sequence number of the entry
     * @param actorId The ID of the actor the message is for
     * @param message The message
     * @throws IllegalArgumentException if version is negative
     */
    public VersionedJournalEntry(int version, long sequenceNumber, String actorId, M message) {
        this(version, sequenceNumber, actorId, message, Instant.now());
    }
    
    /**
     * Gets the schema version of the message.
     * This represents the version of the message schema at the time the message was persisted.
     *
     * @return The schema version
     */
    public int getVersion() {
        return version;
    }
    
    /**
     * Checks if this entry needs migration to the specified target version.
     *
     * @param targetVersion The target schema version
     * @return true if migration is needed (current version &lt; target version)
     */
    public boolean needsMigration(int targetVersion) {
        return version < targetVersion;
    }
    
    /**
     * Creates a new versioned journal entry with an updated message after migration.
     * The version is updated to the target version.
     *
     * @param migratedMessage The migrated message
     * @param targetVersion The target version after migration
     * @return A new VersionedJournalEntry with the migrated message and updated version
     */
    public VersionedJournalEntry<M> withMigratedMessage(M migratedMessage, int targetVersion) {
        return new VersionedJournalEntry<>(
            targetVersion,
            getSequenceNumber(),
            getActorId(),
            migratedMessage,
            getTimestamp()
        );
    }
    
    /**
     * Converts this versioned entry back to a plain JournalEntry.
     * This is useful when the version information is no longer needed.
     *
     * @return A plain JournalEntry without version information
     */
    public JournalEntry<M> toPlainEntry() {
        return new JournalEntry<>(
            getSequenceNumber(),
            getActorId(),
            getMessage(),
            getTimestamp()
        );
    }
    
    @Override
    public String toString() {
        return "VersionedJournalEntry{" +
                "version=" + version +
                ", sequenceNumber=" + getSequenceNumber() +
                ", actorId='" + getActorId() + '\'' +
                ", message=" + getMessage() +
                ", timestamp=" + getTimestamp() +
                '}';
    }
}
