package com.cajunsystems.persistence;

import java.util.Objects;

/**
 * Metadata about a persisted snapshot.
 * 
 * <p>Contains information about a snapshot without loading the actual state data.
 * This is useful for listing, sorting, and pruning snapshots efficiently.
 * 
 * <h2>Usage</h2>
 * <pre>{@code
 * // List all snapshots for an actor
 * List<SnapshotMetadata> snapshots = snapshotStore.listSnapshots("actor-1").join();
 * 
 * // Find the most recent snapshot
 * SnapshotMetadata latest = snapshots.stream()
 *     .max(Comparator.comparingLong(SnapshotMetadata::getSequence))
 *     .orElse(null);
 * }</pre>
 * 
 * @since 1.0
 */
public class SnapshotMetadata {
    
    private final long sequence;
    private final long timestamp;
    private final long fileSize;
    
    /**
     * Creates snapshot metadata without file size information.
     * 
     * @param sequence the sequence number of the snapshot
     * @param timestamp the timestamp when the snapshot was created (milliseconds since epoch)
     */
    public SnapshotMetadata(long sequence, long timestamp) {
        this(sequence, timestamp, -1);
    }
    
    /**
     * Creates snapshot metadata with file size information.
     * 
     * @param sequence the sequence number of the snapshot
     * @param timestamp the timestamp when the snapshot was created (milliseconds since epoch)
     * @param fileSize the size of the snapshot file in bytes, or -1 if unknown
     */
    public SnapshotMetadata(long sequence, long timestamp, long fileSize) {
        this.sequence = sequence;
        this.timestamp = timestamp;
        this.fileSize = fileSize;
    }
    
    /**
     * Returns the sequence number of this snapshot.
     * 
     * @return the sequence number
     */
    public long getSequence() {
        return sequence;
    }
    
    /**
     * Returns the timestamp when this snapshot was created.
     * 
     * @return the timestamp in milliseconds since epoch
     */
    public long getTimestamp() {
        return timestamp;
    }
    
    /**
     * Returns the file size of this snapshot.
     * 
     * @return the file size in bytes, or -1 if unknown
     */
    public long getFileSize() {
        return fileSize;
    }
    
    /**
     * Returns whether file size information is available.
     * 
     * @return true if file size is known
     */
    public boolean hasFileSize() {
        return fileSize >= 0;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SnapshotMetadata that = (SnapshotMetadata) o;
        return sequence == that.sequence &&
               timestamp == that.timestamp &&
               fileSize == that.fileSize;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(sequence, timestamp, fileSize);
    }
    
    @Override
    public String toString() {
        return "SnapshotMetadata{" +
                "sequence=" + sequence +
                ", timestamp=" + timestamp +
                ", fileSize=" + (fileSize >= 0 ? fileSize + " bytes" : "unknown") +
                '}';
    }
}
