package com.cajunsystems.persistence.versioning;

import com.cajunsystems.persistence.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * A persistence provider that adds versioning support to an existing provider.
 * 
 * <p>This provider wraps another {@link PersistenceProvider} and automatically handles:
 * <ul>
 *   <li>Version tracking for persisted messages and snapshots</li>
 *   <li>Automatic migration during recovery</li>
 *   <li>Version validation and compatibility checking</li>
 * </ul>
 * 
 * <p>The provider delegates actual persistence operations to the underlying provider
 * while adding a versioning layer on top.
 * 
 * <p>Example usage:
 * <pre>{@code
 * // Create base provider
 * PersistenceProvider baseProvider = new InMemoryPersistenceProvider();
 * 
 * // Create message migrator and register migrations
 * MessageMigrator migrator = new MessageMigrator();
 * migrator.register(OrderMessageV1.class, 1, 2, msg -> {
 *     // Migration logic
 * });
 * 
 * // Wrap with versioning
 * VersionedPersistenceProvider versionedProvider = 
 *     new VersionedPersistenceProvider(baseProvider, migrator);
 * 
 * // Use as normal persistence provider
 * MessageJournal<OrderMessage> journal = versionedProvider.createMessageJournal("order-actor");
 * }</pre>
 */
public class VersionedPersistenceProvider implements PersistenceProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(VersionedPersistenceProvider.class);
    
    private final PersistenceProvider delegate;
    private final MessageMigrator migrator;
    private final int currentVersion;
    private final boolean autoMigrate;
    
    /**
     * Creates a new VersionedPersistenceProvider with auto-migration enabled.
     *
     * @param delegate The underlying persistence provider
     * @param migrator The message migrator for handling version migrations
     */
    public VersionedPersistenceProvider(PersistenceProvider delegate, MessageMigrator migrator) {
        this(delegate, migrator, PersistenceVersion.CURRENT_VERSION, true);
    }
    
    /**
     * Creates a new VersionedPersistenceProvider with custom configuration.
     *
     * @param delegate The underlying persistence provider
     * @param migrator The message migrator for handling version migrations
     * @param currentVersion The current schema version
     * @param autoMigrate Whether to automatically migrate old data during recovery
     */
    public VersionedPersistenceProvider(PersistenceProvider delegate, 
                                       MessageMigrator migrator,
                                       int currentVersion,
                                       boolean autoMigrate) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate provider cannot be null");
        this.migrator = Objects.requireNonNull(migrator, "Message migrator cannot be null");
        
        PersistenceVersion.validateVersion(currentVersion);
        this.currentVersion = currentVersion;
        this.autoMigrate = autoMigrate;
        
        logger.info("Created VersionedPersistenceProvider wrapping {} with version {} (auto-migrate: {})",
            delegate.getProviderName(), currentVersion, autoMigrate);
    }
    
    @Override
    public <M> MessageJournal<M> createMessageJournal() {
        return new VersionedMessageJournal<>(delegate.createMessageJournal(), migrator, currentVersion, autoMigrate);
    }
    
    @Override
    public <M> MessageJournal<M> createMessageJournal(String actorId) {
        return new VersionedMessageJournal<>(delegate.createMessageJournal(actorId), migrator, currentVersion, autoMigrate);
    }
    
    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal() {
        return new VersionedBatchedMessageJournal<>(delegate.createBatchedMessageJournal(), migrator, currentVersion, autoMigrate);
    }
    
    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal(String actorId) {
        return new VersionedBatchedMessageJournal<>(delegate.createBatchedMessageJournal(actorId), migrator, currentVersion, autoMigrate);
    }
    
    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal(String actorId, int maxBatchSize, long maxBatchDelayMs) {
        return new VersionedBatchedMessageJournal<>(
            delegate.createBatchedMessageJournal(actorId, maxBatchSize, maxBatchDelayMs),
            migrator,
            currentVersion,
            autoMigrate
        );
    }
    
    @Override
    public <S> SnapshotStore<S> createSnapshotStore() {
        return new VersionedSnapshotStore<>(delegate.createSnapshotStore(), migrator, currentVersion, autoMigrate);
    }
    
    @Override
    public <S> SnapshotStore<S> createSnapshotStore(String actorId) {
        return new VersionedSnapshotStore<>(delegate.createSnapshotStore(actorId), migrator, currentVersion, autoMigrate);
    }
    
    @Override
    public String getProviderName() {
        return "Versioned(" + delegate.getProviderName() + ")";
    }
    
    @Override
    public boolean isHealthy() {
        return delegate.isHealthy();
    }
    
    /**
     * Gets the underlying persistence provider.
     *
     * @return The delegate provider
     */
    public PersistenceProvider getDelegate() {
        return delegate;
    }
    
    /**
     * Gets the message migrator.
     *
     * @return The migrator
     */
    public MessageMigrator getMigrator() {
        return migrator;
    }
    
    /**
     * Gets the current schema version.
     *
     * @return The current version
     */
    public int getCurrentVersion() {
        return currentVersion;
    }
    
    /**
     * Checks if auto-migration is enabled.
     *
     * @return true if auto-migration is enabled
     */
    public boolean isAutoMigrate() {
        return autoMigrate;
    }
    
    /**
     * Gets migration metrics from the migrator.
     *
     * @return The migration metrics
     */
    public MigrationMetrics.MigrationStats getMigrationStats() {
        return migrator.getMetrics().getStats();
    }
    
    @Override
    public String toString() {
        return String.format(
            "VersionedPersistenceProvider[delegate=%s, version=%d, autoMigrate=%s, migrations=%s]",
            delegate.getProviderName(),
            currentVersion,
            autoMigrate,
            migrator.getMetrics()
        );
    }
}
