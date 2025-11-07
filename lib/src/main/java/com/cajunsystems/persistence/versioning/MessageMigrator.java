package com.cajunsystems.persistence.versioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Central registry for message and state migration functions.
 * 
 * <p>The MessageMigrator manages all registered migration functions and handles:
 * <ul>
 *   <li>Single-step migrations (v1 → v2)</li>
 *   <li>Multi-hop migrations (v1 → v2 → v3)</li>
 *   <li>Migration path validation</li>
 *   <li>Performance metrics tracking</li>
 * </ul>
 * 
 * <p>Thread-safe and can be shared across multiple actors.
 * 
 * <p>Example usage:
 * <pre>{@code
 * MessageMigrator migrator = new MessageMigrator();
 * 
 * // Register migration from v1 to v2
 * migrator.register(OrderMessageV1.class, 1, 2, msg -> {
 *     OrderMessageV1 v1 = (OrderMessageV1) msg;
 *     return new OrderMessageV2(v1.orderId(), 
 *                                BigDecimal.valueOf(v1.amount()), 
 *                                "USD");
 * });
 * 
 * // Perform migration
 * OrderMessageV1 oldMsg = loadFromDisk();
 * OrderMessageV2 newMsg = migrator.migrate(oldMsg, 1, 2);
 * }</pre>
 */
public class MessageMigrator {
    
    private static final Logger logger = LoggerFactory.getLogger(MessageMigrator.class);
    
    private final Map<MigrationKey, Function<Object, Object>> migrations;
    private final MigrationMetrics metrics;
    private final boolean enableMetrics;
    
    /**
     * Creates a new MessageMigrator with metrics enabled.
     */
    public MessageMigrator() {
        this(true);
    }
    
    /**
     * Creates a new MessageMigrator.
     *
     * @param enableMetrics Whether to track migration metrics
     */
    public MessageMigrator(boolean enableMetrics) {
        this.migrations = new ConcurrentHashMap<>();
        this.metrics = new MigrationMetrics();
        this.enableMetrics = enableMetrics;
    }
    
    /**
     * Registers a migration function for a specific message type and version transition.
     *
     * @param messageClass The message class
     * @param fromVersion The source version
     * @param toVersion The target version
     * @param migrationFn The migration function
     * @param <M> The message type
     * @throws IllegalArgumentException if a migration is already registered for this key
     */
    public <M> void register(Class<?> messageClass, int fromVersion, int toVersion, 
                             Function<Object, Object> migrationFn) {
        register(messageClass.getName(), fromVersion, toVersion, migrationFn);
    }
    
    /**
     * Registers a migration function using a message type name.
     *
     * @param messageType The fully qualified message type name
     * @param fromVersion The source version
     * @param toVersion The target version
     * @param migrationFn The migration function
     * @throws IllegalArgumentException if a migration is already registered for this key
     */
    public void register(String messageType, int fromVersion, int toVersion,
                        Function<Object, Object> migrationFn) {
        if (migrationFn == null) {
            throw new IllegalArgumentException("Migration function cannot be null");
        }
        
        MigrationKey key = new MigrationKey(messageType, fromVersion, toVersion);
        
        Function<Object, Object> existing = migrations.putIfAbsent(key, migrationFn);
        if (existing != null) {
            throw new IllegalArgumentException(
                "Migration already registered for " + key
            );
        }
        
        logger.debug("Registered migration: {}", key);
    }
    
    /**
     * Registers a bidirectional migration (both forward and backward).
     *
     * @param messageClass The message class
     * @param fromVersion The source version
     * @param toVersion The target version
     * @param forwardFn The forward migration function (fromVersion → toVersion)
     * @param backwardFn The backward migration function (toVersion → fromVersion)
     */
    public void registerBidirectional(Class<?> messageClass, int fromVersion, int toVersion,
                                     Function<Object, Object> forwardFn,
                                     Function<Object, Object> backwardFn) {
        register(messageClass, fromVersion, toVersion, forwardFn);
        register(messageClass, toVersion, fromVersion, backwardFn);
    }
    
    /**
     * Checks if a migration is registered for the given key.
     *
     * @param messageType The message type
     * @param fromVersion The source version
     * @param toVersion The target version
     * @return true if a migration is registered
     */
    public boolean hasMigration(String messageType, int fromVersion, int toVersion) {
        MigrationKey key = new MigrationKey(messageType, fromVersion, toVersion);
        return migrations.containsKey(key);
    }
    
    /**
     * Checks if a complete migration path exists between two versions.
     * This checks for multi-hop migrations (e.g., v1 → v2 → v3).
     *
     * @param messageType The message type
     * @param fromVersion The source version
     * @param toVersion The target version
     * @return true if a complete path exists
     */
    public boolean hasMigrationPath(String messageType, int fromVersion, int toVersion) {
        if (fromVersion == toVersion) {
            return true;
        }
        
        if (!PersistenceVersion.isCompatible(fromVersion, toVersion)) {
            return false;
        }
        
        // Check for direct migration
        if (hasMigration(messageType, fromVersion, toVersion)) {
            return true;
        }
        
        // Check for multi-hop path
        int step = fromVersion < toVersion ? 1 : -1;
        int current = fromVersion;
        
        while (current != toVersion) {
            int next = current + step;
            if (!hasMigration(messageType, current, next)) {
                return false;
            }
            current = next;
        }
        
        return true;
    }
    
    /**
     * Migrates a message from one version to another.
     * Supports multi-hop migrations (e.g., v1 → v2 → v3).
     *
     * @param message The message to migrate
     * @param fromVersion The source version
     * @param toVersion The target version
     * @param <M> The message type
     * @return The migrated message
     * @throws MigrationException if migration fails
     */
    @SuppressWarnings("unchecked")
    public <M> M migrate(M message, int fromVersion, int toVersion) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
        
        // No migration needed
        if (fromVersion == toVersion) {
            return message;
        }
        
        String messageType = message.getClass().getName();
        
        // Validate compatibility
        if (!PersistenceVersion.isCompatible(fromVersion, toVersion)) {
            throw MigrationException.incompatibleVersions(messageType, fromVersion, toVersion);
        }
        
        long startTime = enableMetrics ? System.nanoTime() : 0;
        
        try {
            Object current = message;
            int step = fromVersion < toVersion ? 1 : -1;
            int currentVersion = fromVersion;
            
            // Perform multi-hop migration
            while (currentVersion != toVersion) {
                int nextVersion = currentVersion + step;
                current = migrateSingleStep(current, currentVersion, nextVersion);
                currentVersion = nextVersion;
            }
            
            if (enableMetrics) {
                long duration = System.nanoTime() - startTime;
                metrics.recordSuccess(duration);
            }
            
            return (M) current;
            
        } catch (Exception e) {
            if (enableMetrics) {
                long duration = System.nanoTime() - startTime;
                metrics.recordFailure(duration);
            }
            
            if (e instanceof MigrationException) {
                throw e;
            }
            
            throw MigrationException.migrationFailed(messageType, fromVersion, toVersion, e);
        }
    }
    
    /**
     * Performs a single-step migration.
     *
     * @param message The message to migrate
     * @param fromVersion The source version
     * @param toVersion The target version (must be fromVersion ± 1)
     * @return The migrated message
     * @throws MigrationException if no migration function is registered
     */
    private Object migrateSingleStep(Object message, int fromVersion, int toVersion) {
        String messageType = message.getClass().getName();
        MigrationKey key = new MigrationKey(messageType, fromVersion, toVersion);
        
        Function<Object, Object> migrationFn = migrations.get(key);
        if (migrationFn == null) {
            throw MigrationException.noMigrationPath(messageType, fromVersion, toVersion);
        }
        
        try {
            Object result = migrationFn.apply(message);
            if (result == null) {
                throw new IllegalStateException("Migration function returned null");
            }
            return result;
        } catch (Exception e) {
            throw MigrationException.migrationFailed(messageType, fromVersion, toVersion, e);
        }
    }
    
    /**
     * Gets the migration path from one version to another.
     *
     * @param messageType The message type
     * @param fromVersion The source version
     * @param toVersion The target version
     * @return A list of version numbers representing the migration path
     * @throws MigrationException if no path exists
     */
    public List<Integer> getMigrationPath(String messageType, int fromVersion, int toVersion) {
        if (!hasMigrationPath(messageType, fromVersion, toVersion)) {
            throw MigrationException.noMigrationPath(messageType, fromVersion, toVersion);
        }
        
        List<Integer> path = new ArrayList<>();
        path.add(fromVersion);
        
        if (fromVersion == toVersion) {
            return path;
        }
        
        int step = fromVersion < toVersion ? 1 : -1;
        int current = fromVersion;
        
        while (current != toVersion) {
            current += step;
            path.add(current);
        }
        
        return path;
    }
    
    /**
     * Gets the migration metrics.
     *
     * @return The metrics object
     */
    public MigrationMetrics getMetrics() {
        return metrics;
    }
    
    /**
     * Gets the number of registered migrations.
     *
     * @return The count of registered migration functions
     */
    public int getRegisteredMigrationCount() {
        return migrations.size();
    }
    
    /**
     * Clears all registered migrations.
     * This also resets the metrics.
     */
    public void clear() {
        migrations.clear();
        metrics.reset();
        logger.debug("Cleared all migrations");
    }
    
    /**
     * Gets all registered migration keys.
     *
     * @return A list of all migration keys
     */
    public List<MigrationKey> getRegisteredMigrations() {
        return new ArrayList<>(migrations.keySet());
    }
    
    @Override
    public String toString() {
        return String.format(
            "MessageMigrator[migrations=%d, %s]",
            migrations.size(),
            metrics
        );
    }
}
