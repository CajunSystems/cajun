package com.cajunsystems.persistence.lmdb;

import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.MessageJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * LMDB-specific batched message journal that uses a single LMDB write
 * transaction per batch and avoids per-message asynchronous overhead.
 *
 * @param <M> The type of message (must be Serializable)
 */
public class LmdbBatchedMessageJournal<M extends Serializable> implements BatchedMessageJournal<M> {

    private static final Logger logger = LoggerFactory.getLogger(LmdbBatchedMessageJournal.class);

    private final String actorId;
    private final LmdbMessageJournal<M> delegate;
    private volatile int maxBatchSize;
    private volatile long maxBatchDelayMs;

    public LmdbBatchedMessageJournal(String actorId,
                                     LmdbMessageJournal<M> delegate,
                                     int maxBatchSize,
                                     long maxBatchDelayMs) {
        this.actorId = actorId;
        this.delegate = delegate;
        this.maxBatchSize = maxBatchSize;
        this.maxBatchDelayMs = maxBatchDelayMs;
    }

    @Override
    public CompletableFuture<Long> append(String actorId, M message) {
        // Fall back to delegate's async append for single messages
        return delegate.append(actorId, message);
    }

    @Override
    public CompletableFuture<List<JournalEntry<M>>> readFrom(String actorId, long fromSequenceNumber) {
        return delegate.readFrom(actorId, fromSequenceNumber);
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

    @Override
    public CompletableFuture<List<Long>> appendBatch(String actorId, List<M> messages) {
        if (messages == null || messages.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        // Execute the whole batch in a single async task and a single LMDB txn
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Determine starting sequence number
                long latest = delegate.getLatestSequenceNumber();
                long nextSeq = latest + 1;

                List<JournalEntry<M>> entries = new ArrayList<>(messages.size());
                List<Long> sequenceNumbers = new ArrayList<>(messages.size());

                for (M message : messages) {
                    long seq = nextSeq++;
                    entries.add(new JournalEntry<>(seq, this.actorId, message));
                    sequenceNumbers.add(seq);
                }

                // Single LMDB write transaction for the whole batch
                delegate.appendBatch(entries);

                return sequenceNumbers;
            } catch (IOException e) {
                logger.error("Failed to append LMDB batch for actor {}", actorId, e);
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    @Override
    public void setMaxBatchDelayMs(long maxBatchDelayMs) {
        this.maxBatchDelayMs = maxBatchDelayMs;
    }

    @Override
    public CompletableFuture<Void> flush() {
        // No internal buffering; batches are flushed immediately in appendBatch.
        return CompletableFuture.completedFuture(null);
    }
}
