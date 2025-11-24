package com.cajunsystems.persistence.lmdb;

import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.MessageJournal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Simple batched journal implementation that delegates to an underlying MessageJournal.
 *
 * This implementation focuses on API compatibility rather than maximum performance:
 * - appendBatch is implemented by issuing individual append calls and aggregating results.
 * - Batch size and delay are configurable but not currently used for scheduling.
 */
public class SimpleBatchedMessageJournal<M> implements BatchedMessageJournal<M> {

    private final MessageJournal<M> delegate;
    private volatile int maxBatchSize;
    private volatile long maxBatchDelayMs;

    public SimpleBatchedMessageJournal(MessageJournal<M> delegate, int maxBatchSize, long maxBatchDelayMs) {
        this.delegate = delegate;
        this.maxBatchSize = maxBatchSize;
        this.maxBatchDelayMs = maxBatchDelayMs;
    }

    @Override
    public CompletableFuture<Long> append(String actorId, M message) {
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
        List<CompletableFuture<Long>> futures = new ArrayList<>(messages.size());
        for (M message : messages) {
            futures.add(delegate.append(actorId, message));
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        return all.thenApply(v -> {
            List<Long> result = new ArrayList<>(futures.size());
            for (CompletableFuture<Long> f : futures) {
                result.add(f.join());
            }
            return result;
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
        // No internal buffering yet, so flush is a no-op.
        return CompletableFuture.completedFuture(null);
    }
}
