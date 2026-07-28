package com.cajunsystems.metrics;

import java.util.concurrent.atomic.AtomicLong;

public class PersistenceMetrics {
    private final String actorId;
    private final AtomicLong journalAppendCount = new AtomicLong(0);
    private final AtomicLong journalReadCount = new AtomicLong(0);
    private final AtomicLong snapshotSaveCount = new AtomicLong(0);
    private final AtomicLong snapshotLoadCount = new AtomicLong(0);
    private final AtomicLong totalJournalAppendLatencyNs = new AtomicLong(0);
    private final AtomicLong journalAppendLatencyCount = new AtomicLong(0);
    private final AtomicLong totalJournalReadLatencyNs = new AtomicLong(0);
    private final AtomicLong journalReadLatencyCount = new AtomicLong(0);
    private final AtomicLong totalSnapshotSaveLatencyNs = new AtomicLong(0);
    private final AtomicLong snapshotSaveLatencyCount = new AtomicLong(0);
    private final AtomicLong totalSnapshotLoadLatencyNs = new AtomicLong(0);
    private final AtomicLong snapshotLoadLatencyCount = new AtomicLong(0);
    private final AtomicLong journalAppendErrors = new AtomicLong(0);
    private final AtomicLong snapshotErrors = new AtomicLong(0);

    public PersistenceMetrics(String actorId) { this.actorId = actorId; }

    public void recordJournalAppend(long nanos) {
        journalAppendCount.incrementAndGet();
        totalJournalAppendLatencyNs.addAndGet(nanos);
        journalAppendLatencyCount.incrementAndGet();
    }
    public void recordJournalRead(long nanos) {
        journalReadCount.incrementAndGet();
        totalJournalReadLatencyNs.addAndGet(nanos);
        journalReadLatencyCount.incrementAndGet();
    }
    public void recordSnapshotSave(long nanos) {
        snapshotSaveCount.incrementAndGet();
        totalSnapshotSaveLatencyNs.addAndGet(nanos);
        snapshotSaveLatencyCount.incrementAndGet();
    }
    public void recordSnapshotLoad(long nanos) {
        snapshotLoadCount.incrementAndGet();
        totalSnapshotLoadLatencyNs.addAndGet(nanos);
        snapshotLoadLatencyCount.incrementAndGet();
    }
    public void incrementJournalAppendErrors() { journalAppendErrors.incrementAndGet(); }
    public void incrementSnapshotErrors() { snapshotErrors.incrementAndGet(); }

    public String getActorId() { return actorId; }
    public long getJournalAppendCount() { return journalAppendCount.get(); }
    public long getJournalReadCount() { return journalReadCount.get(); }
    public long getSnapshotSaveCount() { return snapshotSaveCount.get(); }
    public long getSnapshotLoadCount() { return snapshotLoadCount.get(); }
    public long getJournalAppendErrors() { return journalAppendErrors.get(); }
    public long getSnapshotErrors() { return snapshotErrors.get(); }
    public long getAverageJournalAppendLatencyNs() {
        long count = journalAppendLatencyCount.get();
        return count == 0 ? 0 : totalJournalAppendLatencyNs.get() / count;
    }
    public long getAverageJournalReadLatencyNs() {
        long count = journalReadLatencyCount.get();
        return count == 0 ? 0 : totalJournalReadLatencyNs.get() / count;
    }
    public long getAverageSnapshotSaveLatencyNs() {
        long count = snapshotSaveLatencyCount.get();
        return count == 0 ? 0 : totalSnapshotSaveLatencyNs.get() / count;
    }
    public long getAverageSnapshotLoadLatencyNs() {
        long count = snapshotLoadLatencyCount.get();
        return count == 0 ? 0 : totalSnapshotLoadLatencyNs.get() / count;
    }

    @Override
    public String toString() {
        return "PersistenceMetrics{actorId='" + actorId
                + "', journalAppends=" + getJournalAppendCount()
                + ", journalReads=" + getJournalReadCount()
                + ", snapshotSaves=" + getSnapshotSaveCount()
                + ", snapshotLoads=" + getSnapshotLoadCount()
                + ", journalErrors=" + getJournalAppendErrors()
                + ", snapshotErrors=" + getSnapshotErrors() + '}';
    }
}
