package com.cajunsystems.persistence.versioning;

import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.JournalEntry;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * A batched message journal that adds versioning support.
 * 
 * <p>Extends {@link VersionedMessageJournal} with batched operations support.
 *
 * @param <M> The message type
 */
public class VersionedBatchedMessageJournal<M> extends VersionedMessageJournal<M> implements BatchedMessageJournal<M> {
    
    private final BatchedMessageJournal<Object> delegate;
    private final int currentVersion;
    
    /**
     * Creates a new VersionedBatchedMessageJournal.
     *
     * @param delegate The underlying batched message journal
     * @param migrator The message migrator
     * @param currentVersion The current schema version
     * @param autoMigrate Whether to automatically migrate old messages
     */
    @SuppressWarnings("unchecked")
    public VersionedBatchedMessageJournal(BatchedMessageJournal<M> delegate,
                                         MessageMigrator migrator,
                                         int currentVersion,
                                         boolean autoMigrate) {
        super(delegate, migrator, currentVersion, autoMigrate);
        this.delegate = (BatchedMessageJournal<Object>) delegate;
        this.currentVersion = currentVersion;
    }
    
    @Override
    public CompletableFuture<List<Long>> appendBatch(String actorId, List<M> messages) {
        // Wrap all messages in versioned entries
        List<Object> versionedEntries = messages.stream()
            .map(msg -> (Object) new VersionedJournalEntry<>(
                currentVersion,
                0L, // Sequence number will be assigned by delegate
                actorId,
                msg
            ))
            .collect(Collectors.toList());
        
        return delegate.appendBatch(actorId, versionedEntries);
    }
    
    @Override
    public void setMaxBatchSize(int maxBatchSize) {
        delegate.setMaxBatchSize(maxBatchSize);
    }
    
    @Override
    public void setMaxBatchDelayMs(long maxBatchDelayMs) {
        delegate.setMaxBatchDelayMs(maxBatchDelayMs);
    }
    
    @Override
    public CompletableFuture<Void> flush() {
        return delegate.flush();
    }
    
    @Override
    public boolean isHealthy() {
        return delegate.isHealthy();
    }
}
