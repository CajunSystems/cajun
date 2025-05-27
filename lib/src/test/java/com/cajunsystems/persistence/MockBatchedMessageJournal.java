package com.cajunsystems.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A mock implementation of BatchedMessageJournal for testing purposes.
 * Extends MockMessageJournal with batching capabilities.
 *
 * @param <M> The type of the message
 */
public class MockBatchedMessageJournal<M> extends MockMessageJournal<M> implements BatchedMessageJournal<M> {
    
    private int maxBatchSize = 10;
    private long maxBatchDelayMs = 100;
    
    @Override
    public CompletableFuture<List<Long>> appendBatch(String actorId, List<M> messages) {
        List<CompletableFuture<Long>> futures = new ArrayList<>();
        
        for (M message : messages) {
            futures.add(append(actorId, message));
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<Long> sequenceNumbers = new ArrayList<>(futures.size());
                    for (CompletableFuture<Long> future : futures) {
                        sequenceNumbers.add(future.join());
                    }
                    return sequenceNumbers;
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
        // Nothing to flush in the mock implementation
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Gets the configured maximum batch size.
     * 
     * @return The maximum batch size
     */
    public int getMaxBatchSize() {
        return maxBatchSize;
    }
    
    /**
     * Gets the configured maximum batch delay in milliseconds.
     * 
     * @return The maximum batch delay in milliseconds
     */
    public long getMaxBatchDelayMs() {
        return maxBatchDelayMs;
    }
}
