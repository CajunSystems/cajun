package systems.cajun.persistence;

import java.io.Serializable;
import java.time.Instant;

/**
 * Represents an entry in the message journal.
 * Contains a message along with metadata such as sequence number and timestamp.
 *
 * @param <M> The type of the message
 */
public class JournalEntry<M> implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final long sequenceNumber;
    private final M message;
    private final Instant timestamp;
    private final String actorId;
    
    /**
     * Creates a new journal entry.
     *
     * @param sequenceNumber The sequence number of the entry
     * @param actorId The ID of the actor the message is for
     * @param message The message
     * @param timestamp The timestamp when the message was journaled
     */
    public JournalEntry(long sequenceNumber, String actorId, M message, Instant timestamp) {
        this.sequenceNumber = sequenceNumber;
        this.actorId = actorId;
        this.message = message;
        this.timestamp = timestamp;
    }
    
    /**
     * Creates a new journal entry with the current timestamp.
     *
     * @param sequenceNumber The sequence number of the entry
     * @param actorId The ID of the actor the message is for
     * @param message The message
     */
    public JournalEntry(long sequenceNumber, String actorId, M message) {
        this(sequenceNumber, actorId, message, Instant.now());
    }
    
    /**
     * Gets the sequence number of the entry.
     *
     * @return The sequence number
     */
    public long getSequenceNumber() {
        return sequenceNumber;
    }
    
    /**
     * Gets the message.
     *
     * @return The message
     */
    public M getMessage() {
        return message;
    }
    
    /**
     * Gets the timestamp when the message was journaled.
     *
     * @return The timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }
    
    /**
     * Gets the ID of the actor the message is for.
     *
     * @return The actor ID
     */
    public String getActorId() {
        return actorId;
    }
    
    @Override
    public String toString() {
        return "JournalEntry{" +
                "sequenceNumber=" + sequenceNumber +
                ", actorId='" + actorId + '\'' +
                ", message=" + message +
                ", timestamp=" + timestamp +
                '}';
    }
}
