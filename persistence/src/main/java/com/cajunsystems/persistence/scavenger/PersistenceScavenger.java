package com.cajunsystems.persistence.scavenger;

import com.cajunsystems.persistence.SnapshotMetadata;
import com.cajunsystems.persistence.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Async background scavenger for persistence cleanup.
 * 
 * <p>The scavenger runs on a scheduled interval, scanning all actor directories
 * and cleaning up old journal entries and snapshots based on configured policies.
 * 
 * <h2>Features</h2>
 * <ul>
 *   <li>System-wide cleanup across all actors</li>
 *   <li>Time-based retention policies</li>
 *   <li>Size-based storage limits</li>
 *   <li>Batch processing to avoid I/O spikes</li>
 *   <li>Comprehensive metrics and observability</li>
 *   <li>Graceful shutdown</li>
 * </ul>
 * 
 * <h2>Usage</h2>
 * <pre>{@code
 * ScavengerConfig config = ScavengerConfig.builder()
 *     .enabled(true)
 *     .scanIntervalMinutes(60)
 *     .snapshotsToKeep(3)
 *     .journalRetentionDays(7)
 *     .build();
 * 
 * PersistenceScavenger scavenger = new PersistenceScavenger(
 *     persistenceBasePath,
 *     snapshotStore,
 *     config
 * );
 * 
 * scavenger.start();
 * 
 * // Later...
 * scavenger.stop();
 * }</pre>
 * 
 * @since 1.0
 */
public class PersistenceScavenger implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(PersistenceScavenger.class);
    
    private final Path persistenceBasePath;
    private final SnapshotStore<?> snapshotStore;
    private final ScavengerConfig config;
    private final ScheduledExecutorService scheduler;
    private final ScavengerMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    
    /**
     * Creates a new PersistenceScavenger.
     * 
     * @param persistenceBasePath the base path where actor persistence data is stored
     * @param snapshotStore the snapshot store for pruning snapshots
     * @param config the scavenger configuration
     */
    public PersistenceScavenger(
            Path persistenceBasePath,
            SnapshotStore<?> snapshotStore,
            ScavengerConfig config) {
        this.persistenceBasePath = persistenceBasePath;
        this.snapshotStore = snapshotStore;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "persistence-scavenger");
                t.setDaemon(true);  // Don't prevent JVM shutdown
                return t;
            });
        this.metrics = new ScavengerMetrics();
    }
    
    /**
     * Starts the scavenger.
     * 
     * <p>If the scavenger is disabled in the configuration, this method does nothing.
     */
    public void start() {
        if (!config.isEnabled()) {
            logger.info("Persistence scavenger is disabled");
            return;
        }
        
        if (started.getAndSet(true)) {
            logger.warn("Persistence scavenger already started");
            return;
        }
        
        running.set(true);
        
        // Schedule the scavenger to run periodically
        scheduler.scheduleAtFixedRate(
            this::runScavenge,
            config.getScanIntervalMinutes(),  // Initial delay
            config.getScanIntervalMinutes(),  // Period
            TimeUnit.MINUTES
        );
        
        logger.info("Persistence scavenger started with interval: {} minutes, config: {}",
            config.getScanIntervalMinutes(), config);
    }
    
    /**
     * Stops the scavenger.
     * 
     * <p>Waits for the current scan to complete before shutting down.
     */
    public void stop() {
        if (!started.get()) {
            return;
        }
        
        logger.info("Stopping persistence scavenger...");
        running.set(false);
        
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("Scavenger did not terminate in time, forcing shutdown");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for scavenger shutdown");
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("Persistence scavenger stopped. Final metrics: {}", metrics);
    }
    
    /**
     * Runs a single scavenge cycle.
     * 
     * <p>This method is called periodically by the scheduler.
     */
    private void runScavenge() {
        if (!running.get()) {
            return;
        }
        
        long startTime = System.currentTimeMillis();
        logger.info("Starting persistence scavenge run");
        
        try {
            // Discover all actor directories
            List<String> actorIds = discoverActors();
            logger.info("Found {} actors to process", actorIds.size());
            
            if (actorIds.isEmpty()) {
                logger.debug("No actors found, skipping scavenge");
                return;
            }
            
            // Process actors in batches
            int batchSize = config.getBatchSize();
            for (int i = 0; i < actorIds.size() && running.get(); i += batchSize) {
                int end = Math.min(i + batchSize, actorIds.size());
                List<String> batch = actorIds.subList(i, end);
                
                processBatch(batch);
                
                // Small delay between batches to avoid I/O storms
                if (i + batchSize < actorIds.size()) {
                    Thread.sleep(100);
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordScanDuration(duration);
            
            logger.info("Scavenge run completed in {}ms. Deleted {} snapshots, {} journals, reclaimed {} MB",
                duration,
                metrics.getTotalSnapshotsDeleted(),
                metrics.getTotalJournalsDeleted(),
                metrics.getTotalBytesReclaimed() / 1024 / 1024);
                
        } catch (Exception e) {
            logger.error("Scavenge run failed", e);
        }
    }
    
    /**
     * Discovers all actor IDs by scanning the persistence base path.
     * 
     * @return list of actor IDs
     */
    private List<String> discoverActors() {
        List<String> actorIds = new ArrayList<>();
        
        if (!Files.exists(persistenceBasePath)) {
            logger.debug("Persistence base path does not exist: {}", persistenceBasePath);
            return actorIds;
        }
        
        try (Stream<Path> paths = Files.list(persistenceBasePath)) {
            paths.filter(Files::isDirectory)
                .forEach(path -> actorIds.add(path.getFileName().toString()));
        } catch (IOException e) {
            logger.error("Failed to discover actors in: {}", persistenceBasePath, e);
        }
        
        return actorIds;
    }
    
    /**
     * Processes a batch of actors.
     * 
     * @param actorIds the actor IDs to process
     */
    private void processBatch(List<String> actorIds) {
        for (String actorId : actorIds) {
            if (!running.get()) {
                break;
            }
            
            try {
                processActor(actorId);
            } catch (Exception e) {
                logger.warn("Failed to process actor: {}", actorId, e);
            }
        }
    }
    
    /**
     * Processes a single actor's persistence data.
     * 
     * @param actorId the actor ID
     */
    private void processActor(String actorId) {
        logger.debug("Processing actor: {}", actorId);
        
        int snapshotsDeleted = 0;
        int journalsDeleted = 0;
        
        // Prune snapshots
        try {
            snapshotsDeleted = pruneSnapshots(actorId);
        } catch (Exception e) {
            logger.warn("Failed to prune snapshots for actor: {}", actorId, e);
        }
        
        // Clean old journals
        try {
            journalsDeleted = cleanOldJournals(actorId);
        } catch (Exception e) {
            logger.warn("Failed to clean journals for actor: {}", actorId, e);
        }
        
        if (snapshotsDeleted > 0 || journalsDeleted > 0) {
            logger.debug("Processed actor {}: deleted {} snapshots, {} journals",
                actorId, snapshotsDeleted, journalsDeleted);
            metrics.recordActorProcessed(snapshotsDeleted, journalsDeleted);
        }
    }
    
    /**
     * Prunes old snapshots for an actor.
     * 
     * @param actorId the actor ID
     * @return the number of snapshots deleted
     */
    private int pruneSnapshots(String actorId) {
        try {
            // Get all snapshots
            List<SnapshotMetadata> snapshots = snapshotStore.listSnapshots(actorId).join();
            
            if (snapshots.isEmpty()) {
                return 0;
            }
            
            int deleted = 0;
            long now = System.currentTimeMillis();
            long retentionMs = config.getSnapshotRetentionDays() * 24L * 60 * 60 * 1000;
            
            // Keep the N most recent snapshots
            int keepCount = config.getSnapshotsToKeep();
            
            for (int i = 0; i < snapshots.size(); i++) {
                SnapshotMetadata snapshot = snapshots.get(i);
                
                // Always keep the most recent snapshots
                if (i < keepCount) {
                    continue;
                }
                
                // Delete snapshots older than retention period
                if (config.getSnapshotRetentionDays() > 0) {
                    long age = now - snapshot.getTimestamp();
                    if (age > retentionMs) {
                        if (deleteSnapshot(actorId, snapshot)) {
                            deleted++;
                        }
                    }
                }
            }
            
            // If we haven't deleted enough and there are more than keepCount, prune excess
            if (snapshots.size() > keepCount) {
                int excess = snapshots.size() - keepCount - deleted;
                if (excess > 0) {
                    int pruned = snapshotStore.pruneOldSnapshots(actorId, keepCount).join();
                    deleted += pruned;
                }
            }
            
            return deleted;
            
        } catch (Exception e) {
            logger.warn("Failed to prune snapshots for actor: {}", actorId, e);
            return 0;
        }
    }
    
    /**
     * Deletes a single snapshot.
     * 
     * @param actorId the actor ID
     * @param snapshot the snapshot metadata
     * @return true if deleted successfully
     */
    private boolean deleteSnapshot(String actorId, SnapshotMetadata snapshot) {
        try {
            Path actorDir = persistenceBasePath.resolve(actorId);
            String filename = String.format("snapshot_%020d_%s.snap",
                snapshot.getSequence(), snapshot.getTimestamp());
            Path snapshotFile = actorDir.resolve(filename);
            
            if (Files.deleteIfExists(snapshotFile)) {
                if (snapshot.hasFileSize()) {
                    metrics.recordBytesReclaimed(snapshot.getFileSize());
                }
                return true;
            }
        } catch (IOException e) {
            logger.warn("Failed to delete snapshot for actor {}: {}", actorId, e.getMessage());
        }
        return false;
    }
    
    /**
     * Cleans old journal files for an actor.
     * 
     * @param actorId the actor ID
     * @return the number of journal files deleted
     */
    private int cleanOldJournals(String actorId) {
        if (config.getJournalRetentionDays() == 0) {
            return 0;  // No time-based cleanup
        }
        
        Path actorDir = persistenceBasePath.resolve(actorId);
        if (!Files.exists(actorDir)) {
            return 0;
        }
        
        int deleted = 0;
        long now = System.currentTimeMillis();
        long retentionMs = config.getJournalRetentionDays() * 24L * 60 * 60 * 1000;
        
        try (Stream<Path> paths = Files.list(actorDir)) {
            List<Path> journalFiles = paths
                .filter(p -> p.getFileName().toString().startsWith("message-"))
                .toList();
            
            for (Path journalFile : journalFiles) {
                try {
                    long lastModified = Files.getLastModifiedTime(journalFile).toMillis();
                    long age = now - lastModified;
                    
                    if (age > retentionMs) {
                        long size = Files.size(journalFile);
                        if (Files.deleteIfExists(journalFile)) {
                            metrics.recordBytesReclaimed(size);
                            deleted++;
                            logger.debug("Deleted old journal: {}", journalFile.getFileName());
                        }
                    }
                } catch (IOException e) {
                    logger.warn("Failed to process journal file: {}", journalFile, e);
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to list journal files for actor: {}", actorId, e);
        }
        
        return deleted;
    }
    
    /**
     * Returns the scavenger metrics.
     * 
     * @return the metrics
     */
    public ScavengerMetrics getMetrics() {
        return metrics;
    }
    
    /**
     * Returns whether the scavenger is currently running.
     * 
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }
    
    @Override
    public void close() {
        stop();
    }
}
