package com.cajunsystems.persistence.scavenger;

import java.time.Duration;

/**
 * Configuration for the async persistence scavenger.
 * 
 * <p>The scavenger runs in the background on a scheduled interval, cleaning up
 * old journal entries and snapshots across all actors in the system.
 * 
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Conservative configuration (recommended for production)
 * ScavengerConfig config = ScavengerConfig.builder()
 *     .enabled(true)
 *     .scanIntervalMinutes(60)
 *     .snapshotsToKeep(3)
 *     .journalRetentionDays(7)
 *     .snapshotRetentionDays(30)
 *     .build();
 * 
 * // Aggressive configuration (space-constrained)
 * ScavengerConfig aggressive = ScavengerConfig.builder()
 *     .enabled(true)
 *     .scanIntervalMinutes(15)
 *     .snapshotsToKeep(2)
 *     .journalRetentionDays(1)
 *     .snapshotRetentionDays(7)
 *     .maxActorStorageMB(100)
 *     .build();
 * }</pre>
 * 
 * @since 1.0
 */
public class ScavengerConfig {
    
    private final boolean enabled;
    private final int scanIntervalMinutes;
    private final int snapshotsToKeep;
    private final int journalRetentionDays;
    private final int snapshotRetentionDays;
    private final long maxActorStorageMB;
    private final int batchSize;
    private final Duration scanTimeout;
    
    private ScavengerConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.scanIntervalMinutes = builder.scanIntervalMinutes;
        this.snapshotsToKeep = builder.snapshotsToKeep;
        this.journalRetentionDays = builder.journalRetentionDays;
        this.snapshotRetentionDays = builder.snapshotRetentionDays;
        this.maxActorStorageMB = builder.maxActorStorageMB;
        this.batchSize = builder.batchSize;
        this.scanTimeout = builder.scanTimeout;
        
        // Validation
        if (scanIntervalMinutes < 1) {
            throw new IllegalArgumentException("scanIntervalMinutes must be at least 1");
        }
        if (snapshotsToKeep < 1) {
            throw new IllegalArgumentException("snapshotsToKeep must be at least 1");
        }
        if (journalRetentionDays < 0) {
            throw new IllegalArgumentException("journalRetentionDays cannot be negative");
        }
        if (snapshotRetentionDays < 0) {
            throw new IllegalArgumentException("snapshotRetentionDays cannot be negative");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public int getScanIntervalMinutes() {
        return scanIntervalMinutes;
    }
    
    public int getSnapshotsToKeep() {
        return snapshotsToKeep;
    }
    
    public int getJournalRetentionDays() {
        return journalRetentionDays;
    }
    
    public int getSnapshotRetentionDays() {
        return snapshotRetentionDays;
    }
    
    public long getMaxActorStorageMB() {
        return maxActorStorageMB;
    }
    
    public boolean hasStorageLimit() {
        return maxActorStorageMB > 0;
    }
    
    public int getBatchSize() {
        return batchSize;
    }
    
    public Duration getScanTimeout() {
        return scanTimeout;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private boolean enabled = false;  // Safe default: OFF
        private int scanIntervalMinutes = 60;  // Hourly by default
        private int snapshotsToKeep = 3;  // Keep 3 snapshots
        private int journalRetentionDays = 7;  // Keep journals for 7 days
        private int snapshotRetentionDays = 30;  // Keep snapshots for 30 days
        private long maxActorStorageMB = -1;  // No limit by default
        private int batchSize = 10;  // Process 10 actors per batch
        private Duration scanTimeout = Duration.ofMinutes(30);  // 30 minute timeout
        
        /**
         * Enables or disables the scavenger.
         * 
         * @param enabled true to enable the scavenger
         * @return this builder
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        
        /**
         * Sets the scan interval in minutes.
         * 
         * @param minutes the interval between scavenger runs (must be at least 1)
         * @return this builder
         */
        public Builder scanIntervalMinutes(int minutes) {
            if (minutes < 1) {
                throw new IllegalArgumentException("scanIntervalMinutes must be at least 1");
            }
            this.scanIntervalMinutes = minutes;
            return this;
        }
        
        /**
         * Sets the number of snapshots to keep per actor.
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
         * Sets the journal retention period in days.
         * 
         * <p>Journals older than this will be deleted if a snapshot exists
         * covering that period.
         * 
         * @param days the number of days to retain journals (0 or more)
         * @return this builder
         */
        public Builder journalRetentionDays(int days) {
            if (days < 0) {
                throw new IllegalArgumentException("journalRetentionDays cannot be negative");
            }
            this.journalRetentionDays = days;
            return this;
        }
        
        /**
         * Sets the snapshot retention period in days.
         * 
         * <p>Snapshots older than this will be deleted, except the most recent one.
         * 
         * @param days the number of days to retain snapshots (0 or more)
         * @return this builder
         */
        public Builder snapshotRetentionDays(int days) {
            if (days < 0) {
                throw new IllegalArgumentException("snapshotRetentionDays cannot be negative");
            }
            this.snapshotRetentionDays = days;
            return this;
        }
        
        /**
         * Sets the maximum storage per actor in megabytes.
         * 
         * <p>If an actor exceeds this limit, the scavenger will aggressively
         * prune old data. Set to -1 for no limit.
         * 
         * @param megabytes the maximum storage in MB, or -1 for unlimited
         * @return this builder
         */
        public Builder maxActorStorageMB(long megabytes) {
            this.maxActorStorageMB = megabytes;
            return this;
        }
        
        /**
         * Sets the batch size for processing actors.
         * 
         * <p>The scavenger processes actors in batches to avoid I/O spikes.
         * 
         * @param size the number of actors to process per batch (must be at least 1)
         * @return this builder
         */
        public Builder batchSize(int size) {
            if (size < 1) {
                throw new IllegalArgumentException("batchSize must be at least 1");
            }
            this.batchSize = size;
            return this;
        }
        
        /**
         * Sets the timeout for a single scavenger scan.
         * 
         * @param timeout the maximum duration for a scan
         * @return this builder
         */
        public Builder scanTimeout(Duration timeout) {
            this.scanTimeout = timeout;
            return this;
        }
        
        /**
         * Builds the ScavengerConfig.
         * 
         * @return a new ScavengerConfig instance
         */
        public ScavengerConfig build() {
            return new ScavengerConfig(this);
        }
    }
    
    @Override
    public String toString() {
        return "ScavengerConfig{" +
                "enabled=" + enabled +
                ", scanIntervalMinutes=" + scanIntervalMinutes +
                ", snapshotsToKeep=" + snapshotsToKeep +
                ", journalRetentionDays=" + journalRetentionDays +
                ", snapshotRetentionDays=" + snapshotRetentionDays +
                ", maxActorStorageMB=" + maxActorStorageMB +
                ", batchSize=" + batchSize +
                ", scanTimeout=" + scanTimeout +
                '}';
    }
}
