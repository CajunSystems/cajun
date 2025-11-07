package com.cajunsystems.persistence.versioning;

import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.MessageJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * A message journal that adds versioning support to an existing journal.
 * 
 * <p>This wrapper automatically:
 * <ul>
 *   <li>Wraps messages in {@link VersionedJournalEntry} when appending</li>
 *   <li>Migrates old messages to current version when reading (if auto-migrate enabled)</li>
 *   <li>Tracks version metadata for all persisted messages</li>
 * </ul>
 * 
 * <p>Thread-safe and delegates actual persistence to the underlying journal.
 *
 * @param <M> The message type
 */
public class VersionedMessageJournal<M> implements MessageJournal<M> {
    
    private static final Logger logger = LoggerFactory.getLogger(VersionedMessageJournal.class);
    
    private final MessageJournal<Object> delegate;
    private final MessageMigrator migrator;
    private final int currentVersion;
    private final boolean autoMigrate;
    
    /**
     * Creates a new VersionedMessageJournal.
     *
     * @param delegate The underlying message journal
     * @param migrator The message migrator
     * @param currentVersion The current schema version
     * @param autoMigrate Whether to automatically migrate old messages
     */
    @SuppressWarnings("unchecked")
    public VersionedMessageJournal(MessageJournal<M> delegate,
                                  MessageMigrator migrator,
                                  int currentVersion,
                                  boolean autoMigrate) {
        this.delegate = (MessageJournal<Object>) delegate;
        this.migrator = migrator;
        this.currentVersion = currentVersion;
        this.autoMigrate = autoMigrate;
    }
    
    @Override
    public CompletableFuture<Long> append(String actorId, M message) {
        // Wrap message in versioned entry
        VersionedJournalEntry<M> versionedEntry = new VersionedJournalEntry<>(
            currentVersion,
            0L, // Sequence number will be assigned by delegate
            actorId,
            message
        );
        
        return delegate.append(actorId, versionedEntry);
    }
    
    @Override
    public CompletableFuture<List<JournalEntry<M>>> readFrom(String actorId, long fromSequenceNumber) {
        return delegate.readFrom(actorId, fromSequenceNumber)
            .thenApply(entries -> entries.stream()
                .map(this::unwrapAndMigrate)
                .collect(Collectors.toList())
            );
    }
    
    @Override
    public CompletableFuture<Void> truncateBefore(String actorId, long upToSequenceNumber) {
        return delegate.truncateBefore(actorId, upToSequenceNumber);
    }
    
    @Override
    public CompletableFuture<Long> getHighestSequenceNumber(String actorId) {
        return delegate.getHighestSequenceNumber(actorId);
    }
    
    @Override
    public void close() {
        delegate.close();
    }
    
    /**
     * Unwraps a versioned journal entry and migrates if necessary.
     *
     * @param entry The journal entry to unwrap
     * @return The unwrapped and potentially migrated entry
     */
    @SuppressWarnings("unchecked")
    private JournalEntry<M> unwrapAndMigrate(JournalEntry<Object> entry) {
        Object message = entry.getMessage();
        
        // Handle versioned entries
        if (message instanceof VersionedJournalEntry<?> versioned) {
            int messageVersion = versioned.getVersion();
            M actualMessage = (M) versioned.getMessage();
            
            // Migrate if needed and auto-migrate is enabled
            if (autoMigrate && messageVersion != currentVersion) {
                if (migrator.hasMigrationPath(actualMessage.getClass().getName(), messageVersion, currentVersion)) {
                    try {
                        actualMessage = migrator.migrate(actualMessage, messageVersion, currentVersion);
                        logger.debug("Migrated message from v{} to v{} for actor {}",
                            messageVersion, currentVersion, entry.getActorId());
                    } catch (MigrationException e) {
                        logger.error("Failed to migrate message for actor {}: {}",
                            entry.getActorId(), e.getMessage());
                        throw e;
                    }
                } else {
                    logger.warn("No migration path from v{} to v{} for message type {}",
                        messageVersion, currentVersion, actualMessage.getClass().getName());
                }
            }
            
            return new JournalEntry<>(
                entry.getSequenceNumber(),
                entry.getActorId(),
                actualMessage,
                entry.getTimestamp()
            );
        }
        
        // Handle unversioned entries (legacy data)
        logger.debug("Encountered unversioned message for actor {}, treating as v{}",
            entry.getActorId(), PersistenceVersion.UNVERSIONED);
        
        M actualMessage = (M) message;
        
        // Migrate from unversioned to current if auto-migrate is enabled
        if (autoMigrate && migrator.hasMigrationPath(
                actualMessage.getClass().getName(),
                PersistenceVersion.UNVERSIONED,
                currentVersion)) {
            try {
                actualMessage = migrator.migrate(actualMessage, PersistenceVersion.UNVERSIONED, currentVersion);
                logger.debug("Migrated unversioned message to v{} for actor {}",
                    currentVersion, entry.getActorId());
            } catch (MigrationException e) {
                logger.error("Failed to migrate unversioned message for actor {}: {}",
                    entry.getActorId(), e.getMessage());
                throw e;
            }
        }
        
        return new JournalEntry<>(
            entry.getSequenceNumber(),
            entry.getActorId(),
            actualMessage,
            entry.getTimestamp()
        );
    }
}
