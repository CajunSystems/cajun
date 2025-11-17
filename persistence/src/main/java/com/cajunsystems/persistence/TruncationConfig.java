package com.cajunsystems.persistence;

/**
 * Configuration for persistence truncation strategies.
 * 
 * <p>Truncation helps manage disk space by removing old journal entries and snapshots
 * that are no longer needed for recovery. This configuration controls when and how
 * truncation occurs.
 * 
 * <p><strong>Default Behavior:</strong> Truncation is <strong>enabled by default</strong>
 * with conservative settings to prevent unbounded disk growth. Use {@link #DISABLED} to
 * explicitly opt out if needed.
 * 
 * <h2>Snapshot-Based Truncation</h2>
 * When enabled, truncation occurs automatically when a snapshot is taken:
 * <ul>
 *   <li>Journal entries before the snapshot sequence are deleted</li>
 *   <li>Old snapshots beyond the retention count are pruned</li>
 * </ul>
 * 
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Use default (recommended for most cases)
 * Pid actor = system.statefulActorOf(Handler.class, state)
 *     .withPersistence(journal, snapshotStore)
 *     .spawn();  // Uses TruncationConfig.DEFAULT automatically
 * 
 * // Explicitly disable (not recommended)
 * Pid actor = system.statefulActorOf(Handler.class, state)
 *     .withTruncationConfig(TruncationConfig.DISABLED)
 *     .withPersistence(journal, snapshotStore)
 *     .spawn();
 * 
 * // Custom configuration
 * TruncationConfig config = TruncationConfig.builder()
 *     .enableSnapshotBasedTruncation(true)
 *     .snapshotsToKeep(5)
 *     .truncateJournalOnSnapshot(true)
 *     .build();
 * }</pre>
 * 
 * @since 1.0
 */
public class TruncationConfig {
    
    /**
     * Default truncation configuration (ENABLED).
     * 
     * <p>Conservative settings suitable for production:
     * <ul>
     *   <li>Snapshot-based truncation: ENABLED</li>
     *   <li>Snapshots to keep: 3 (provides safety margin)</li>
     *   <li>Journal truncation on snapshot: ENABLED</li>
     * </ul>
     * 
     * <p>This prevents unbounded disk growth while maintaining sufficient
     * history for recovery and debugging.
     */
    public static final TruncationConfig DEFAULT = builder()
        .enableSnapshotBasedTruncation(true)
        .snapshotsToKeep(3)
        .truncateJournalOnSnapshot(true)
        .build();
    
    /**
     * Disabled truncation configuration.
     * 
     * <p>Use this to explicitly opt out of truncation. Not recommended for
     * production as it will lead to unbounded disk growth.
     * 
     * <p><strong>Warning:</strong> Without truncation, snapshots and journal
     * files will accumulate indefinitely, eventually exhausting disk space.
     */
    public static final TruncationConfig DISABLED = builder()
        .enableSnapshotBasedTruncation(false)
        .snapshotsToKeep(2)  // Unused when disabled, but must be valid
        .truncateJournalOnSnapshot(false)
        .build();
    
    private final boolean snapshotBasedTruncationEnabled;
    private final int snapshotsToKeep;
    private final boolean truncateJournalOnSnapshot;
    private final long snapshotIntervalMs;
    private final int changesBeforeSnapshot;
    
    private TruncationConfig(Builder builder) {
        this.snapshotBasedTruncationEnabled = builder.snapshotBasedTruncationEnabled;
        this.snapshotsToKeep = builder.snapshotsToKeep;
        this.truncateJournalOnSnapshot = builder.truncateJournalOnSnapshot;
        this.snapshotIntervalMs = builder.snapshotIntervalMs;
        this.changesBeforeSnapshot = builder.changesBeforeSnapshot;
        
        // Validation
        if (snapshotsToKeep < 1) {
            throw new IllegalArgumentException("snapshotsToKeep must be at least 1");
        }
        if (snapshotIntervalMs < 0) {
            throw new IllegalArgumentException("snapshotIntervalMs must be non-negative");
        }
        if (changesBeforeSnapshot < 0) {
            throw new IllegalArgumentException("changesBeforeSnapshot must be non-negative");
        }
    }
    
    /**
     * Returns whether snapshot-based truncation is enabled.
     * 
     * @return true if snapshot-based truncation is enabled
     */
    public boolean isSnapshotBasedTruncationEnabled() {
        return snapshotBasedTruncationEnabled;
    }
    
    /**
     * Returns the number of snapshots to keep.
     * 
     * @return the number of snapshots to retain
     */
    public int getSnapshotsToKeep() {
        return snapshotsToKeep;
    }
    
    /**
     * Returns whether journal truncation should occur on snapshot.
     * 
     * @return true if journal should be truncated when snapshot is taken
     */
    public boolean isTruncateJournalOnSnapshot() {
        return truncateJournalOnSnapshot;
    }
    
    /**
     * Returns the snapshot interval in milliseconds.
     * 
     * @return the snapshot interval in milliseconds (0 means use default)
     */
    public long getSnapshotIntervalMs() {
        return snapshotIntervalMs;
    }
    
    /**
     * Returns the number of changes before taking a snapshot.
     * 
     * @return the number of changes before snapshot (0 means use default)
     */
    public int getChangesBeforeSnapshot() {
        return changesBeforeSnapshot;
    }
    
    /**
     * Creates a new builder for TruncationConfig.
     * 
     * @return a new builder with default values
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for TruncationConfig with sensible defaults.
     */
    public static class Builder {
        private boolean snapshotBasedTruncationEnabled = false;  // Safe default: OFF
        private int snapshotsToKeep = 2;  // Keep 2 snapshots by default
        private boolean truncateJournalOnSnapshot = false;  // Safe default: OFF
        private long snapshotIntervalMs = 0;  // 0 means use actor's default
        private int changesBeforeSnapshot = 0;  // 0 means use actor's default
        
        /**
         * Enables or disables snapshot-based truncation.
         * 
         * @param enabled true to enable snapshot-based truncation
         * @return this builder
         */
        public Builder enableSnapshotBasedTruncation(boolean enabled) {
            this.snapshotBasedTruncationEnabled = enabled;
            return this;
        }
        
        /**
         * Sets the number of snapshots to keep.
         * 
         * @param count the number of snapshots to retain (must be at least 1)
         * @return this builder
         */
        public Builder snapshotsToKeep(int count) {
            if (count < 1) {
                throw new IllegalArgumentException("snapshotsToKeep must be at least 1");
            }
            this.snapshotsToKeep = count;
            return this;
        }
        
        /**
         * Enables or disables journal truncation on snapshot.
         * 
         * @param enabled true to truncate journal when snapshot is taken
         * @return this builder
         */
        public Builder truncateJournalOnSnapshot(boolean enabled) {
            this.truncateJournalOnSnapshot = enabled;
            return this;
        }
        
        /**
         * Sets the snapshot interval in milliseconds.
         * 
         * @param intervalMs time interval between snapshots (0 to use actor's default)
         * @return this builder
         */
        public Builder snapshotIntervalMs(long intervalMs) {
            if (intervalMs < 0) {
                throw new IllegalArgumentException("snapshotIntervalMs must be non-negative");
            }
            this.snapshotIntervalMs = intervalMs;
            return this;
        }
        
        /**
         * Sets the number of changes before taking a snapshot.
         * 
         * @param changes number of state changes before snapshot (0 to use actor's default)
         * @return this builder
         */
        public Builder changesBeforeSnapshot(int changes) {
            if (changes < 0) {
                throw new IllegalArgumentException("changesBeforeSnapshot must be non-negative");
            }
            this.changesBeforeSnapshot = changes;
            return this;
        }
        
        /**
         * Builds the TruncationConfig.
         * 
         * @return a new TruncationConfig instance
         */
        public TruncationConfig build() {
            return new TruncationConfig(this);
        }
    }
    
    @Override
    public String toString() {
        return "TruncationConfig{" +
                "snapshotBasedTruncationEnabled=" + snapshotBasedTruncationEnabled +
                ", snapshotsToKeep=" + snapshotsToKeep +
                ", truncateJournalOnSnapshot=" + truncateJournalOnSnapshot +
                ", snapshotIntervalMs=" + snapshotIntervalMs +
                ", changesBeforeSnapshot=" + changesBeforeSnapshot +
                '}';
    }
}
