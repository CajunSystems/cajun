package com.cajunsystems.persistence.scavenger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for the persistence scavenger.
 * 
 * <p>Tracks the performance and effectiveness of the scavenger's cleanup operations.
 * 
 * @since 1.0
 */
public class ScavengerMetrics {
    
    private final AtomicLong totalScans = new AtomicLong(0);
    private final AtomicLong totalActorsProcessed = new AtomicLong(0);
    private final AtomicLong totalSnapshotsDeleted = new AtomicLong(0);
    private final AtomicLong totalJournalsDeleted = new AtomicLong(0);
    private final AtomicLong totalBytesReclaimed = new AtomicLong(0);
    private final AtomicLong lastScanDurationMs = new AtomicLong(0);
    private final AtomicLong totalScanDurationMs = new AtomicLong(0);
    private volatile long lastScanTimestamp = 0;
    
    /**
     * Records the completion of a scavenger scan.
     * 
     * @param durationMs the duration of the scan in milliseconds
     */
    public void recordScanDuration(long durationMs) {
        totalScans.incrementAndGet();
        lastScanDurationMs.set(durationMs);
        totalScanDurationMs.addAndGet(durationMs);
        lastScanTimestamp = System.currentTimeMillis();
    }
    
    /**
     * Records the processing of an actor.
     * 
     * @param snapshotsDeleted the number of snapshots deleted for this actor
     * @param journalsDeleted the number of journal files deleted for this actor
     */
    public void recordActorProcessed(int snapshotsDeleted, int journalsDeleted) {
        totalActorsProcessed.incrementAndGet();
        totalSnapshotsDeleted.addAndGet(snapshotsDeleted);
        totalJournalsDeleted.addAndGet(journalsDeleted);
    }
    
    /**
     * Records bytes reclaimed from deleted files.
     * 
     * @param bytes the number of bytes reclaimed
     */
    public void recordBytesReclaimed(long bytes) {
        totalBytesReclaimed.addAndGet(bytes);
    }
    
    /**
     * Returns the total number of scavenger scans performed.
     * 
     * @return the total scan count
     */
    public long getTotalScans() {
        return totalScans.get();
    }
    
    /**
     * Returns the total number of actors processed.
     * 
     * @return the total actor count
     */
    public long getTotalActorsProcessed() {
        return totalActorsProcessed.get();
    }
    
    /**
     * Returns the total number of snapshots deleted.
     * 
     * @return the total snapshot deletion count
     */
    public long getTotalSnapshotsDeleted() {
        return totalSnapshotsDeleted.get();
    }
    
    /**
     * Returns the total number of journal files deleted.
     * 
     * @return the total journal deletion count
     */
    public long getTotalJournalsDeleted() {
        return totalJournalsDeleted.get();
    }
    
    /**
     * Returns the total bytes reclaimed.
     * 
     * @return the total bytes reclaimed
     */
    public long getTotalBytesReclaimed() {
        return totalBytesReclaimed.get();
    }
    
    /**
     * Returns the duration of the last scan in milliseconds.
     * 
     * @return the last scan duration
     */
    public long getLastScanDurationMs() {
        return lastScanDurationMs.get();
    }
    
    /**
     * Returns the average scan duration in milliseconds.
     * 
     * @return the average scan duration, or 0 if no scans have been performed
     */
    public long getAverageScanDurationMs() {
        long scans = totalScans.get();
        return scans > 0 ? totalScanDurationMs.get() / scans : 0;
    }
    
    /**
     * Returns the timestamp of the last scan.
     * 
     * @return the last scan timestamp in milliseconds since epoch
     */
    public long getLastScanTimestamp() {
        return lastScanTimestamp;
    }
    
    /**
     * Resets all metrics to zero.
     */
    public void reset() {
        totalScans.set(0);
        totalActorsProcessed.set(0);
        totalSnapshotsDeleted.set(0);
        totalJournalsDeleted.set(0);
        totalBytesReclaimed.set(0);
        lastScanDurationMs.set(0);
        totalScanDurationMs.set(0);
        lastScanTimestamp = 0;
    }
    
    @Override
    public String toString() {
        return "ScavengerMetrics{" +
                "totalScans=" + totalScans.get() +
                ", totalActorsProcessed=" + totalActorsProcessed.get() +
                ", totalSnapshotsDeleted=" + totalSnapshotsDeleted.get() +
                ", totalJournalsDeleted=" + totalJournalsDeleted.get() +
                ", totalBytesReclaimed=" + totalBytesReclaimed.get() +
                ", lastScanDurationMs=" + lastScanDurationMs.get() +
                ", averageScanDurationMs=" + getAverageScanDurationMs() +
                '}';
    }
}
