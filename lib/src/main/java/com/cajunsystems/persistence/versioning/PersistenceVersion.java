package com.cajunsystems.persistence.versioning;

/**
 * Utility class for managing persistence schema versions.
 * 
 * <p>This class provides constants and utility methods for working with schema versions
 * in the versioned persistence system. It helps ensure consistency in version handling
 * across the codebase.
 * 
 * <p>Version numbers follow these conventions:
 * <ul>
 *   <li>Version numbers start at 1 (not 0)</li>
 *   <li>Version numbers are sequential integers</li>
 *   <li>Higher version numbers represent newer schemas</li>
 *   <li>Version 0 is reserved for unversioned/legacy data</li>
 * </ul>
 * 
 * <p>Example usage:
 * <pre>{@code
 * // Validate a version number
 * PersistenceVersion.validateVersion(2); // OK
 * PersistenceVersion.validateVersion(-1); // Throws IllegalArgumentException
 * 
 * // Check compatibility
 * if (PersistenceVersion.isCompatible(oldVersion, newVersion)) {
 *     // Can migrate from oldVersion to newVersion
 * }
 * 
 * // Get version range
 * int[] versions = PersistenceVersion.getVersionRange(1, 3); // [1, 2, 3]
 * }</pre>
 */
public final class PersistenceVersion {
    
    /**
     * The default version for new persistence entries.
     * This is the version assigned when no explicit version is specified.
     */
    public static final int DEFAULT_VERSION = 1;
    
    /**
     * The current schema version used by the system.
     * This should be incremented whenever the message or state schema changes
     * in a way that requires migration.
     * 
     * <p>When deploying a new version of your application with schema changes:
     * <ol>
     *   <li>Increment this constant</li>
     *   <li>Register migration functions for the new version</li>
     *   <li>Test the migration thoroughly</li>
     *   <li>Deploy the new version</li>
     * </ol>
     */
    public static final int CURRENT_VERSION = 1;
    
    /**
     * The minimum supported version.
     * Versions below this are considered too old and cannot be migrated.
     */
    public static final int MIN_SUPPORTED_VERSION = 1;
    
    /**
     * Special version number indicating unversioned/legacy data.
     * This is used for backward compatibility with data persisted before
     * the versioning system was introduced.
     */
    public static final int UNVERSIONED = 0;
    
    // Private constructor to prevent instantiation
    private PersistenceVersion() {
        throw new AssertionError("PersistenceVersion is a utility class and should not be instantiated");
    }
    
    /**
     * Validates that a version number is valid.
     * A valid version is either UNVERSIONED (0) or a positive integer.
     *
     * @param version The version number to validate
     * @throws IllegalArgumentException if the version is negative (except for UNVERSIONED)
     */
    public static void validateVersion(int version) {
        if (version < UNVERSIONED) {
            throw new IllegalArgumentException(
                "Invalid version: " + version + ". Version must be >= " + UNVERSIONED
            );
        }
    }
    
    /**
     * Checks if a version is within the supported range.
     * A version is supported if it's between MIN_SUPPORTED_VERSION and CURRENT_VERSION (inclusive),
     * or if it's UNVERSIONED.
     *
     * @param version The version to check
     * @return true if the version is supported, false otherwise
     */
    public static boolean isSupported(int version) {
        return version == UNVERSIONED || 
               (version >= MIN_SUPPORTED_VERSION && version <= CURRENT_VERSION);
    }
    
    /**
     * Checks if two versions are compatible for migration.
     * Migration is possible if both versions are supported.
     * This supports both forward migration (upgrade) and backward migration (rollback).
     *
     * @param fromVersion The source version
     * @param toVersion The target version
     * @return true if migration from fromVersion to toVersion is possible
     */
    public static boolean isCompatible(int fromVersion, int toVersion) {
        validateVersion(fromVersion);
        validateVersion(toVersion);
        
        return isSupported(fromVersion) && isSupported(toVersion);
    }
    
    /**
     * Checks if a version needs migration to the current version.
     *
     * @param version The version to check
     * @return true if migration is needed (version < CURRENT_VERSION)
     */
    public static boolean needsMigration(int version) {
        validateVersion(version);
        return version < CURRENT_VERSION;
    }
    
    /**
     * Gets an array of all version numbers in the range [fromVersion, toVersion] inclusive.
     * This is useful for iterating through migration steps.
     *
     * @param fromVersion The starting version (inclusive)
     * @param toVersion The ending version (inclusive)
     * @return An array of version numbers
     * @throws IllegalArgumentException if fromVersion > toVersion or versions are invalid
     */
    public static int[] getVersionRange(int fromVersion, int toVersion) {
        validateVersion(fromVersion);
        validateVersion(toVersion);
        
        if (fromVersion > toVersion) {
            throw new IllegalArgumentException(
                "Invalid version range: fromVersion (" + fromVersion + 
                ") must be <= toVersion (" + toVersion + ")"
            );
        }
        
        int size = toVersion - fromVersion + 1;
        int[] range = new int[size];
        for (int i = 0; i < size; i++) {
            range[i] = fromVersion + i;
        }
        return range;
    }
    
    /**
     * Gets the number of migration steps required to go from one version to another.
     * Supports both forward (upgrade) and backward (rollback) migrations.
     *
     * @param fromVersion The source version
     * @param toVersion The target version
     * @return The number of migration steps (0 if no migration needed, always positive)
     * @throws IllegalArgumentException if versions are invalid or incompatible
     */
    public static int getMigrationSteps(int fromVersion, int toVersion) {
        if (!isCompatible(fromVersion, toVersion)) {
            throw new IllegalArgumentException(
                "Incompatible versions: cannot migrate from " + fromVersion + " to " + toVersion
            );
        }
        
        return Math.abs(toVersion - fromVersion);
    }
    
    /**
     * Formats a version number as a human-readable string.
     *
     * @param version The version number
     * @return A formatted string representation
     */
    public static String formatVersion(int version) {
        if (version == UNVERSIONED) {
            return "unversioned";
        }
        return "v" + version;
    }
    
    /**
     * Checks if a version is the current version.
     *
     * @param version The version to check
     * @return true if the version matches CURRENT_VERSION
     */
    public static boolean isCurrent(int version) {
        return version == CURRENT_VERSION;
    }
    
    /**
     * Checks if a version is unversioned (legacy data).
     *
     * @param version The version to check
     * @return true if the version is UNVERSIONED
     */
    public static boolean isUnversioned(int version) {
        return version == UNVERSIONED;
    }
    
    /**
     * Gets the next version number after the given version.
     *
     * @param version The current version
     * @return The next version number
     * @throws IllegalArgumentException if version is invalid
     */
    public static int getNextVersion(int version) {
        validateVersion(version);
        return version + 1;
    }
    
    /**
     * Gets the previous version number before the given version.
     * Returns UNVERSIONED if the given version is MIN_SUPPORTED_VERSION.
     *
     * @param version The current version
     * @return The previous version number
     * @throws IllegalArgumentException if version is invalid or is UNVERSIONED
     */
    public static int getPreviousVersion(int version) {
        validateVersion(version);
        
        if (version == UNVERSIONED) {
            throw new IllegalArgumentException("Cannot get previous version of UNVERSIONED");
        }
        
        return version - 1;
    }
}
