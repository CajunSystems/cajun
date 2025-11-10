package com.cajunsystems.persistence.impl;

import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.MessageJournal;
import com.cajunsystems.persistence.PersistenceProvider;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.runtime.persistence.LmdbMessageJournal;
import com.cajunsystems.runtime.persistence.LmdbBatchedMessageJournal;
import com.cajunsystems.runtime.persistence.LmdbSnapshotStore;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * LMDB implementation of the PersistenceProvider interface.
 * This provider creates LMDB-based persistence components for high performance.
 */
public class LmdbPersistenceProvider implements PersistenceProvider {
    
    private static final String DEFAULT_BASE_DIR = "cajun_persistence_lmdb";
    private static final String JOURNAL_DIR = "journal";
    private static final String SNAPSHOT_DIR = "snapshots";
    
    private final String baseDir;
    private final long mapSize;
    private final int maxDbs;
    
    /**
     * Creates a new LmdbPersistenceProvider with default settings.
     */
    public LmdbPersistenceProvider() {
        this(DEFAULT_BASE_DIR, 10_485_760_000L, 100); // 10GB map size, 100 databases
    }
    
    /**
     * Creates a new LmdbPersistenceProvider with custom base directory.
     *
     * @param baseDir The base directory for persistence
     */
    public LmdbPersistenceProvider(String baseDir) {
        this(baseDir, 10_485_760_000L, 100);
    }
    
    /**
     * Creates a new LmdbPersistenceProvider with custom settings.
     *
     * @param baseDir The base directory for persistence
     * @param mapSize The memory map size for LMDB environment
     * @param maxDbs The maximum number of databases in the environment
     */
    public LmdbPersistenceProvider(String baseDir, long mapSize, int maxDbs) {
        this.baseDir = baseDir != null ? baseDir : DEFAULT_BASE_DIR;
        this.mapSize = mapSize;
        this.maxDbs = maxDbs;
    }
    
    @Override
    public <M> MessageJournal<M> createMessageJournal() {
        Path journalDir = Paths.get(baseDir, JOURNAL_DIR);
        return new LmdbMessageJournal<>(journalDir, mapSize, maxDbs);
    }
    
    @Override
    public <M> MessageJournal<M> createMessageJournal(String actorId) {
        Path journalDir = Paths.get(baseDir, JOURNAL_DIR, actorId);
        return new LmdbMessageJournal<>(journalDir, mapSize, maxDbs);
    }
    
    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal() {
        Path journalDir = Paths.get(baseDir, JOURNAL_DIR);
        return new LmdbBatchedMessageJournal<>(journalDir, mapSize, maxDbs);
    }
    
    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal(String actorId) {
        Path journalDir = Paths.get(baseDir, JOURNAL_DIR, actorId);
        return new LmdbBatchedMessageJournal<>(journalDir, mapSize, maxDbs);
    }
    
    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal(
            String actorId, int maxBatchSize, long maxBatchDelayMs) {
        Path journalDir = Paths.get(baseDir, JOURNAL_DIR, actorId);
        LmdbBatchedMessageJournal<M> journal = new LmdbBatchedMessageJournal<>(journalDir, mapSize, maxDbs);
        journal.setMaxBatchSize(maxBatchSize);
        journal.setMaxBatchDelayMs(maxBatchDelayMs);
        return journal;
    }
    
    @Override
    public <S> SnapshotStore<S> createSnapshotStore() {
        Path snapshotDir = Paths.get(baseDir, SNAPSHOT_DIR);
        return new LmdbSnapshotStore<>(snapshotDir, mapSize, maxDbs);
    }
    
    @Override
    public <S> SnapshotStore<S> createSnapshotStore(String actorId) {
        Path snapshotDir = Paths.get(baseDir, SNAPSHOT_DIR, actorId);
        return new LmdbSnapshotStore<>(snapshotDir, mapSize, maxDbs);
    }
    
    @Override
    public String getProviderName() {
        return "lmdb";
    }
    
    @Override
    public boolean isHealthy() {
        try {
            // LMDB health check - try to access the environment
            // For now, return true as long as no exceptions are thrown
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Gets the base directory for this persistence provider.
     *
     * @return The base directory path
     */
    public String getBaseDir() {
        return baseDir;
    }
    
    /**
     * Gets the memory map size for LMDB environment.
     *
     * @return The map size in bytes
     */
    public long getMapSize() {
        return mapSize;
    }
    
    /**
     * Gets the maximum number of databases for LMDB environment.
     *
     * @return The maximum number of databases
     */
    public int getMaxDbs() {
        return maxDbs;
    }
}
