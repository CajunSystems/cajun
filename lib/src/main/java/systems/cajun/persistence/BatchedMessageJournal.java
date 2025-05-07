package systems.cajun.persistence;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Extension of the MessageJournal interface that supports batched operations.
 * This allows for more efficient persistence of multiple messages at once.
 *
 * @param <M> The type of the message
 */
public interface BatchedMessageJournal<M> extends MessageJournal<M> {
    
    /**
     * Appends multiple messages to the journal for the specified actor in a single batch operation.
     * This is more efficient than appending messages individually.
     *
     * @param actorId The ID of the actor the messages are for
     * @param messages The list of messages to append
     * @return A CompletableFuture that completes with a list of sequence numbers assigned to the messages
     */
    CompletableFuture<List<Long>> appendBatch(String actorId, List<M> messages);
    
    /**
     * Sets the maximum batch size for this journal.
     * Messages will be accumulated until this size is reached before being flushed to storage.
     *
     * @param maxBatchSize The maximum number of messages to accumulate before flushing
     */
    void setMaxBatchSize(int maxBatchSize);
    
    /**
     * Sets the maximum time in milliseconds that messages can be held in the batch
     * before being flushed to storage, even if the batch is not full.
     *
     * @param maxBatchDelayMs The maximum delay in milliseconds
     */
    void setMaxBatchDelayMs(long maxBatchDelayMs);
    
    /**
     * Manually flushes any pending messages in the batch to storage.
     *
     * @return A CompletableFuture that completes when the flush is done
     */
    CompletableFuture<Void> flush();
}
