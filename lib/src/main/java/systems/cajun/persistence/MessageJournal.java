package systems.cajun.persistence;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for message journaling operations.
 * Provides methods to append messages to a journal and read messages for replay.
 *
 * @param <M> The type of the message
 */
public interface MessageJournal<M> {
    
    /**
     * Appends a message to the journal for the specified actor.
     *
     * @param actorId The ID of the actor the message is for
     * @param message The message to append
     * @return A CompletableFuture that completes with the sequence number assigned to the message
     */
    CompletableFuture<Long> append(String actorId, M message);
    
    /**
     * Reads messages from the journal starting from the specified sequence number.
     *
     * @param actorId The ID of the actor to read messages for
     * @param fromSequenceNumber The sequence number to start reading from (inclusive)
     * @return A CompletableFuture that completes with a list of journal entries
     */
    CompletableFuture<List<JournalEntry<M>>> readFrom(String actorId, long fromSequenceNumber);
    
    /**
     * Truncates the journal by removing entries with sequence numbers less than the specified number.
     * This is typically used after taking a snapshot to free up storage space.
     *
     * @param actorId The ID of the actor to truncate messages for
     * @param upToSequenceNumber The sequence number up to which messages should be truncated (exclusive)
     * @return A CompletableFuture that completes when the operation is done
     */
    CompletableFuture<Void> truncateBefore(String actorId, long upToSequenceNumber);
    
    /**
     * Gets the highest sequence number in the journal for the specified actor.
     *
     * @param actorId The ID of the actor
     * @return A CompletableFuture that completes with the highest sequence number, or -1 if the journal is empty
     */
    CompletableFuture<Long> getHighestSequenceNumber(String actorId);
    
    /**
     * Closes the journal, releasing any resources.
     */
    void close();
}
