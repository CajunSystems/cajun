package com.cajunsystems.persistence.versioning;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks performance metrics for message and state migrations.
 * 
 * <p>This class provides thread-safe counters for monitoring migration performance:
 * <ul>
 *   <li>Total number of migrations performed</li>
 *   <li>Number of successful migrations</li>
 *   <li>Number of failed migrations</li>
 *   <li>Total time spent in migrations</li>
 *   <li>Average migration time</li>
 * </ul>
 * 
 * <p>All operations are thread-safe and lock-free using atomic operations.
 * 
 * <p>Example usage:
 * <pre>{@code
 * MigrationMetrics metrics = new MigrationMetrics();
 * 
 * long startTime = System.nanoTime();
 * try {
 *     // Perform migration
 *     Object migrated = migrate(message);
 *     long duration = System.nanoTime() - startTime;
 *     metrics.recordMigration(duration, true);
 * } catch (Exception e) {
 *     long duration = System.nanoTime() - startTime;
 *     metrics.recordMigration(duration, false);
 * }
 * 
 * // Get statistics
 * MigrationStats stats = metrics.getStats();
 * System.out.println("Success rate: " + stats.getSuccessRate() + "%");
 * }</pre>
 */
public class MigrationMetrics {
    
    /**
     * Creates a new MigrationMetrics instance for tracking migration performance.
     */
    public MigrationMetrics() {
        // Default constructor
    }
    
    private final LongAdder migrationsPerformed = new LongAdder();
    private final LongAdder migrationErrors = new LongAdder();
    private final LongAdder totalMigrationTimeNanos = new LongAdder();
    private final AtomicLong minMigrationTimeNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxMigrationTimeNanos = new AtomicLong(0);
    
    /**
     * Records a migration operation.
     *
     * @param durationNanos The duration of the migration in nanoseconds
     * @param success Whether the migration succeeded
     */
    public void recordMigration(long durationNanos, boolean success) {
        migrationsPerformed.increment();
        totalMigrationTimeNanos.add(durationNanos);
        
        if (!success) {
            migrationErrors.increment();
        }
        
        // Update min/max times
        updateMin(minMigrationTimeNanos, durationNanos);
        updateMax(maxMigrationTimeNanos, durationNanos);
    }
    
    /**
     * Records a successful migration.
     *
     * @param durationNanos The duration of the migration in nanoseconds
     */
    public void recordSuccess(long durationNanos) {
        recordMigration(durationNanos, true);
    }
    
    /**
     * Records a failed migration.
     *
     * @param durationNanos The duration of the migration in nanoseconds
     */
    public void recordFailure(long durationNanos) {
        recordMigration(durationNanos, false);
    }
    
    /**
     * Gets the total number of migrations performed.
     *
     * @return The total count
     */
    public long getMigrationsPerformed() {
        return migrationsPerformed.sum();
    }
    
    /**
     * Gets the number of successful migrations.
     *
     * @return The success count
     */
    public long getSuccessfulMigrations() {
        return migrationsPerformed.sum() - migrationErrors.sum();
    }
    
    /**
     * Gets the number of failed migrations.
     *
     * @return The error count
     */
    public long getFailedMigrations() {
        return migrationErrors.sum();
    }
    
    /**
     * Gets the total time spent in migrations (in nanoseconds).
     *
     * @return The total time
     */
    public long getTotalMigrationTimeNanos() {
        return totalMigrationTimeNanos.sum();
    }
    
    /**
     * Gets the total time spent in migrations (in milliseconds).
     *
     * @return The total time in milliseconds
     */
    public long getTotalMigrationTimeMillis() {
        return totalMigrationTimeNanos.sum() / 1_000_000;
    }
    
    /**
     * Gets the average migration time (in nanoseconds).
     *
     * @return The average time, or 0 if no migrations performed
     */
    public long getAverageMigrationTimeNanos() {
        long count = migrationsPerformed.sum();
        return count > 0 ? totalMigrationTimeNanos.sum() / count : 0;
    }
    
    /**
     * Gets the average migration time (in milliseconds).
     *
     * @return The average time in milliseconds
     */
    public double getAverageMigrationTimeMillis() {
        return getAverageMigrationTimeNanos() / 1_000_000.0;
    }
    
    /**
     * Gets the minimum migration time (in nanoseconds).
     *
     * @return The minimum time, or 0 if no migrations performed
     */
    public long getMinMigrationTimeNanos() {
        long min = minMigrationTimeNanos.get();
        return min == Long.MAX_VALUE ? 0 : min;
    }
    
    /**
     * Gets the maximum migration time (in nanoseconds).
     *
     * @return The maximum time
     */
    public long getMaxMigrationTimeNanos() {
        return maxMigrationTimeNanos.get();
    }
    
    /**
     * Gets the success rate as a percentage.
     *
     * @return The success rate (0-100), or 0 if no migrations performed
     */
    public double getSuccessRate() {
        long total = migrationsPerformed.sum();
        if (total == 0) {
            return 0.0;
        }
        return (getSuccessfulMigrations() * 100.0) / total;
    }
    
    /**
     * Gets the failure rate as a percentage.
     *
     * @return The failure rate (0-100), or 0 if no migrations performed
     */
    public double getFailureRate() {
        return 100.0 - getSuccessRate();
    }
    
    /**
     * Resets all metrics to zero.
     */
    public void reset() {
        migrationsPerformed.reset();
        migrationErrors.reset();
        totalMigrationTimeNanos.reset();
        minMigrationTimeNanos.set(Long.MAX_VALUE);
        maxMigrationTimeNanos.set(0);
    }
    
    /**
     * Gets a snapshot of all migration statistics.
     *
     * @return A MigrationStats object containing all current metrics
     */
    public MigrationStats getStats() {
        return new MigrationStats(
            getMigrationsPerformed(),
            getSuccessfulMigrations(),
            getFailedMigrations(),
            getTotalMigrationTimeNanos(),
            getAverageMigrationTimeNanos(),
            getMinMigrationTimeNanos(),
            getMaxMigrationTimeNanos(),
            getSuccessRate()
        );
    }
    
    private void updateMin(AtomicLong current, long newValue) {
        long currentMin;
        do {
            currentMin = current.get();
            if (newValue >= currentMin) {
                return;
            }
        } while (!current.compareAndSet(currentMin, newValue));
    }
    
    private void updateMax(AtomicLong current, long newValue) {
        long currentMax;
        do {
            currentMax = current.get();
            if (newValue <= currentMax) {
                return;
            }
        } while (!current.compareAndSet(currentMax, newValue));
    }
    
    @Override
    public String toString() {
        MigrationStats stats = getStats();
        return String.format(
            "MigrationMetrics[total=%d, success=%d, failed=%d, avgTime=%.2fms, successRate=%.1f%%]",
            stats.totalMigrations(),
            stats.successfulMigrations(),
            stats.failedMigrations(),
            stats.averageMigrationTimeNanos() / 1_000_000.0,
            stats.successRate()
        );
    }
    
    /**
     * Immutable snapshot of migration statistics.
     *
     * @param totalMigrations Total number of migrations performed
     * @param successfulMigrations Number of successful migrations
     * @param failedMigrations Number of failed migrations
     * @param totalMigrationTimeNanos Total time spent in migrations (nanoseconds)
     * @param averageMigrationTimeNanos Average migration time (nanoseconds)
     * @param minMigrationTimeNanos Minimum migration time (nanoseconds)
     * @param maxMigrationTimeNanos Maximum migration time (nanoseconds)
     * @param successRate Success rate as a percentage (0-100)
     */
    public record MigrationStats(
        long totalMigrations,
        long successfulMigrations,
        long failedMigrations,
        long totalMigrationTimeNanos,
        long averageMigrationTimeNanos,
        long minMigrationTimeNanos,
        long maxMigrationTimeNanos,
        double successRate
    ) {
        /**
         * Gets the total migration time in milliseconds.
         *
         * @return Total time in milliseconds
         */
        public double totalMigrationTimeMillis() {
            return totalMigrationTimeNanos / 1_000_000.0;
        }
        
        /**
         * Gets the average migration time in milliseconds.
         *
         * @return Average time in milliseconds
         */
        public double averageMigrationTimeMillis() {
            return averageMigrationTimeNanos / 1_000_000.0;
        }
        
        /**
         * Gets the failure rate as a percentage.
         *
         * @return Failure rate (0-100)
         */
        public double failureRate() {
            return 100.0 - successRate;
        }
    }
}
