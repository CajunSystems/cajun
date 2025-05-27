package com.cajunsystems.persistence;

import java.io.Serializable;
import java.time.Instant;

/**
 * Represents a snapshot entry in the snapshot store.
 * Contains a state snapshot along with metadata such as sequence number and timestamp.
 *
 * @param <S> The type of the state
 */
public class SnapshotEntry<S> implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String actorId;
    private final S state;
    private final long sequenceNumber;
    private final Instant timestamp;
    
    /**
     * Creates a new snapshot entry.
     *
     * @param actorId The ID of the actor
     * @param state The state snapshot
     * @param sequenceNumber The sequence number of the last message processed to reach this state
     * @param timestamp The timestamp when the snapshot was taken
     */
    public SnapshotEntry(String actorId, S state, long sequenceNumber, Instant timestamp) {
        this.actorId = actorId;
        this.state = state;
        this.sequenceNumber = sequenceNumber;
        this.timestamp = timestamp;
    }
    
    /**
     * Creates a new snapshot entry with the current timestamp.
     *
     * @param actorId The ID of the actor
     * @param state The state snapshot
     * @param sequenceNumber The sequence number of the last message processed to reach this state
     */
    public SnapshotEntry(String actorId, S state, long sequenceNumber) {
        this(actorId, state, sequenceNumber, Instant.now());
    }
    
    /**
     * Gets the ID of the actor.
     *
     * @return The actor ID
     */
    public String getActorId() {
        return actorId;
    }
    
    /**
     * Gets the state snapshot.
     *
     * @return The state
     */
    public S getState() {
        return state;
    }
    
    /**
     * Gets the sequence number of the last message processed to reach this state.
     *
     * @return The sequence number
     */
    public long getSequenceNumber() {
        return sequenceNumber;
    }
    
    /**
     * Gets the timestamp when the snapshot was taken.
     *
     * @return The timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }
    
    @Override
    public String toString() {
        return "SnapshotEntry{" +
                "actorId='" + actorId + '\'' +
                ", state=" + state +
                ", sequenceNumber=" + sequenceNumber +
                ", timestamp=" + timestamp +
                '}';
    }
}
