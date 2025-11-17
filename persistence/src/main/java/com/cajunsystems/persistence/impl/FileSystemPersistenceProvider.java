package com.cajunsystems.persistence.impl;

import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.MessageJournal;
import com.cajunsystems.persistence.PersistenceProvider;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.persistence.runtime.persistence.BatchedFileMessageJournal;
import com.cajunsystems.persistence.runtime.persistence.FileMessageJournal;
import com.cajunsystems.persistence.runtime.persistence.FileSnapshotStore;
import com.cajunsystems.persistence.scavenger.PersistenceScavenger;
import com.cajunsystems.persistence.scavenger.ScavengerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * File system implementation of the PersistenceProvider interface.
 * This provider creates file-based persistence components.
 */
public class FileSystemPersistenceProvider implements PersistenceProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(FileSystemPersistenceProvider.class);
    private static final String DEFAULT_BASE_DIR = "cajun_persistence";
    private static final String JOURNAL_DIR = "journal";
    private static final String SNAPSHOT_DIR = "snapshots";
    
    private final String baseDir;
    private PersistenceScavenger scavenger;
    
    /**
     * Creates a new FileSystemPersistenceProvider with the default base directory.
     */
    public FileSystemPersistenceProvider() {
        this(DEFAULT_BASE_DIR);
    }
    
    /**
     * Creates a new FileSystemPersistenceProvider with a custom base directory.
     *
     * @param baseDir The base directory for persistence
     */
    public FileSystemPersistenceProvider(String baseDir) {
        this.baseDir = baseDir;
    }
    
    @Override
    public <M> MessageJournal<M> createMessageJournal() {
        Path journalDir = Paths.get(baseDir, JOURNAL_DIR);
        return new FileMessageJournal<>(journalDir);
    }
    
    @Override
    public <M> MessageJournal<M> createMessageJournal(String actorId) {
        Path journalDir = Paths.get(baseDir, JOURNAL_DIR, actorId);
        return new FileMessageJournal<>(journalDir);
    }
    
    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal() {
        Path journalDir = Paths.get(baseDir, JOURNAL_DIR);
        return new BatchedFileMessageJournal<>(journalDir);
    }
    
    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal(String actorId) {
        Path journalDir = Paths.get(baseDir, JOURNAL_DIR, actorId);
        return new BatchedFileMessageJournal<>(journalDir);
    }
    
    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal(
            String actorId, int maxBatchSize, long maxBatchDelayMs) {
        Path journalDir = Paths.get(baseDir, JOURNAL_DIR, actorId);
        BatchedFileMessageJournal<M> journal = new BatchedFileMessageJournal<>(journalDir);
        journal.setMaxBatchSize(maxBatchSize);
        journal.setMaxBatchDelayMs(maxBatchDelayMs);
        return journal;
    }
    
    @Override
    public <S> SnapshotStore<S> createSnapshotStore() {
        Path snapshotDir = Paths.get(baseDir, SNAPSHOT_DIR);
        return new FileSnapshotStore<>(snapshotDir);
    }
    
    @Override
    public <S> SnapshotStore<S> createSnapshotStore(String actorId) {
        Path snapshotDir = Paths.get(baseDir, SNAPSHOT_DIR, actorId);
        return new FileSnapshotStore<>(snapshotDir);
    }
    
    @Override
    public String getProviderName() {
        return "filesystem";
    }
    
    @Override
    public boolean isHealthy() {
        return true;
    }
    
    /**
     * Enables the persistence scavenger for system-wide cleanup.
     * 
     * <p>The scavenger runs in the background, periodically cleaning up
     * old journal entries and snapshots across all actors.
     * 
     * @param config the scavenger configuration
     */
    public void enableScavenger(ScavengerConfig config) {
        if (scavenger != null) {
            logger.warn("Scavenger already enabled");
            return;
        }
        
        Path basePath = Paths.get(baseDir);
        SnapshotStore<?> snapshotStore = createSnapshotStore();
        
        scavenger = new PersistenceScavenger(basePath, snapshotStore, config);
        scavenger.start();
        
        logger.info("Persistence scavenger enabled with config: {}", config);
    }
    
    /**
     * Disables the persistence scavenger if it's running.
     */
    public void disableScavenger() {
        if (scavenger != null) {
            scavenger.stop();
            scavenger = null;
            logger.info("Persistence scavenger disabled");
        }
    }
    
    /**
     * Returns the scavenger if it's enabled.
     * 
     * @return the scavenger, or null if not enabled
     */
    public PersistenceScavenger getScavenger() {
        return scavenger;
    }
    
    /**
     * Closes the persistence provider and stops the scavenger if running.
     */
    public void close() {
        disableScavenger();
    }
}
