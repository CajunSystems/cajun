package com.cajunsystems.persistence.versioning;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key for identifying a specific migration path between two schema versions.
 * 
 * <p>A migration key uniquely identifies a migration function by combining:
 * <ul>
 *   <li>The fully qualified class name of the message type</li>
 *   <li>The source version (fromVersion)</li>
 *   <li>The target version (toVersion)</li>
 * </ul>
 * 
 * <p>This allows the {@link MessageMigrator} to look up the appropriate migration function
 * for transforming a message from one version to another.
 * 
 * <p>Example usage:
 * <pre>{@code
 * // Create a migration key for OrderMessage v1 -> v2
 * MigrationKey key = new MigrationKey(
 *     "com.example.OrderMessageV1",
 *     1,
 *     2
 * );
 * 
 * // Use as a map key
 * Map<MigrationKey, Function<Object, Object>> migrations = new HashMap<>();
 * migrations.put(key, migrationFunction);
 * }</pre>
 * 
 * <p>This is an immutable record, making it safe to use as a map key.
 *
 * @param messageType The fully qualified class name of the message type
 * @param fromVersion The source schema version
 * @param toVersion The target schema version
 */
public record MigrationKey(
    String messageType,
    int fromVersion,
    int toVersion
) implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * Compact constructor with validation.
     *
     * @throws IllegalArgumentException if messageType is null/empty or versions are invalid
     */
    public MigrationKey {
        if (messageType == null || messageType.isBlank()) {
            throw new IllegalArgumentException("Message type cannot be null or empty");
        }
        
        PersistenceVersion.validateVersion(fromVersion);
        PersistenceVersion.validateVersion(toVersion);
        
        if (fromVersion == toVersion) {
            throw new IllegalArgumentException(
                "Migration key cannot have same fromVersion and toVersion: " + fromVersion
            );
        }
    }
    
    /**
     * Creates a migration key from a message class.
     *
     * @param messageClass The message class
     * @param fromVersion The source version
     * @param toVersion The target version
     * @return A new MigrationKey
     */
    public static MigrationKey of(Class<?> messageClass, int fromVersion, int toVersion) {
        return new MigrationKey(messageClass.getName(), fromVersion, toVersion);
    }
    
    /**
     * Checks if this is a forward migration (upgrade).
     *
     * @return true if toVersion > fromVersion
     */
    public boolean isForwardMigration() {
        return toVersion > fromVersion;
    }
    
    /**
     * Checks if this is a backward migration (rollback).
     *
     * @return true if toVersion < fromVersion
     */
    public boolean isBackwardMigration() {
        return toVersion < fromVersion;
    }
    
    /**
     * Gets the number of version steps in this migration.
     *
     * @return The absolute difference between versions
     */
    public int getSteps() {
        return Math.abs(toVersion - fromVersion);
    }
    
    /**
     * Creates the reverse migration key (for bidirectional migrations).
     *
     * @return A new MigrationKey with fromVersion and toVersion swapped
     */
    public MigrationKey reverse() {
        return new MigrationKey(messageType, toVersion, fromVersion);
    }
    
    @Override
    public String toString() {
        String direction = isForwardMigration() ? "↑" : "↓";
        return String.format("MigrationKey[%s: v%d %s v%d]", 
            messageType.substring(messageType.lastIndexOf('.') + 1),
            fromVersion,
            direction,
            toVersion
        );
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MigrationKey that = (MigrationKey) o;
        return fromVersion == that.fromVersion &&
               toVersion == that.toVersion &&
               Objects.equals(messageType, that.messageType);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(messageType, fromVersion, toVersion);
    }
}
