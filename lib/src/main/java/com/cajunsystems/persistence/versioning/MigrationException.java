package com.cajunsystems.persistence.versioning;

/**
 * Exception thrown when a message or state migration fails.
 * 
 * <p>This exception provides detailed context about the failed migration, including:
 * <ul>
 *   <li>The message type that failed to migrate</li>
 *   <li>The source version</li>
 *   <li>The target version</li>
 *   <li>The underlying cause of the failure</li>
 * </ul>
 * 
 * <p>Example usage:
 * <pre>{@code
 * try {
 *     OrderMessageV2 migrated = migrator.migrate(oldMessage, 1, 2);
 * } catch (MigrationException e) {
 *     logger.error("Failed to migrate {} from v{} to v{}: {}",
 *         e.getMessageType(),
 *         e.getFromVersion(),
 *         e.getToVersion(),
 *         e.getMessage()
 *     );
 * }
 * }</pre>
 */
public class MigrationException extends RuntimeException {
    
    private static final long serialVersionUID = 1L;
    
    /** The fully qualified class name of the message type. */
    private final String messageType;
    
    /** The source version of the migration. */
    private final int fromVersion;
    
    /** The target version of the migration. */
    private final int toVersion;
    
    /**
     * Creates a new MigrationException with detailed context.
     *
     * @param messageType The fully qualified class name of the message type
     * @param fromVersion The source version
     * @param toVersion The target version
     * @param message The error message
     */
    public MigrationException(String messageType, int fromVersion, int toVersion, String message) {
        super(formatMessage(messageType, fromVersion, toVersion, message));
        this.messageType = messageType;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
    }
    
    /**
     * Creates a new MigrationException with detailed context and a cause.
     *
     * @param messageType The fully qualified class name of the message type
     * @param fromVersion The source version
     * @param toVersion The target version
     * @param message The error message
     * @param cause The underlying cause
     */
    public MigrationException(String messageType, int fromVersion, int toVersion, String message, Throwable cause) {
        super(formatMessage(messageType, fromVersion, toVersion, message), cause);
        this.messageType = messageType;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
    }
    
    /**
     * Creates a new MigrationException from a MigrationKey.
     *
     * @param key The migration key
     * @param message The error message
     */
    public MigrationException(MigrationKey key, String message) {
        this(key.messageType(), key.fromVersion(), key.toVersion(), message);
    }
    
    /**
     * Creates a new MigrationException from a MigrationKey with a cause.
     *
     * @param key The migration key
     * @param message The error message
     * @param cause The underlying cause
     */
    public MigrationException(MigrationKey key, String message, Throwable cause) {
        this(key.messageType(), key.fromVersion(), key.toVersion(), message, cause);
    }
    
    /**
     * Creates a MigrationException for a missing migration path.
     *
     * @param messageType The message type
     * @param fromVersion The source version
     * @param toVersion The target version
     * @return A new MigrationException
     */
    public static MigrationException noMigrationPath(String messageType, int fromVersion, int toVersion) {
        return new MigrationException(
            messageType,
            fromVersion,
            toVersion,
            "No migration path registered"
        );
    }
    
    /**
     * Creates a MigrationException for a migration function failure.
     *
     * @param messageType The message type
     * @param fromVersion The source version
     * @param toVersion The target version
     * @param cause The underlying cause
     * @return A new MigrationException
     */
    public static MigrationException migrationFailed(String messageType, int fromVersion, int toVersion, Throwable cause) {
        return new MigrationException(
            messageType,
            fromVersion,
            toVersion,
            "Migration function threw exception",
            cause
        );
    }
    
    /**
     * Creates a MigrationException for an incompatible version.
     *
     * @param messageType The message type
     * @param fromVersion The source version
     * @param toVersion The target version
     * @return A new MigrationException
     */
    public static MigrationException incompatibleVersions(String messageType, int fromVersion, int toVersion) {
        return new MigrationException(
            messageType,
            fromVersion,
            toVersion,
            "Versions are not compatible for migration"
        );
    }
    
    /**
     * Gets the message type that failed to migrate.
     *
     * @return The fully qualified class name
     */
    public String getMessageType() {
        return messageType;
    }
    
    /**
     * Gets the source version.
     *
     * @return The from version
     */
    public int getFromVersion() {
        return fromVersion;
    }
    
    /**
     * Gets the target version.
     *
     * @return The to version
     */
    public int getToVersion() {
        return toVersion;
    }
    
    /**
     * Gets a short name for the message type (without package).
     *
     * @return The simple class name
     */
    public String getSimpleMessageType() {
        int lastDot = messageType.lastIndexOf('.');
        return lastDot >= 0 ? messageType.substring(lastDot + 1) : messageType;
    }
    
    /**
     * Checks if this was a forward migration (upgrade).
     *
     * @return true if toVersion > fromVersion
     */
    public boolean isForwardMigration() {
        return toVersion > fromVersion;
    }
    
    /**
     * Checks if this was a backward migration (rollback).
     *
     * @return true if toVersion &lt; fromVersion
     */
    public boolean isBackwardMigration() {
        return toVersion < fromVersion;
    }
    
    private static String formatMessage(String messageType, int fromVersion, int toVersion, String message) {
        String simpleType = messageType.substring(messageType.lastIndexOf('.') + 1);
        String direction = toVersion > fromVersion ? "upgrade" : "rollback";
        return String.format(
            "Migration failed for %s (v%d → v%d, %s): %s",
            simpleType,
            fromVersion,
            toVersion,
            direction,
            message
        );
    }
}
