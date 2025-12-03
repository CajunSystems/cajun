package com.cajunsystems.persistence.impl;

import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.MessageJournal;
import com.cajunsystems.persistence.PersistenceProvider;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.runtime.persistence.BatchedFileMessageJournal;
import com.cajunsystems.runtime.persistence.FileMessageJournal;
import com.cajunsystems.runtime.persistence.FileSnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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

    @Override
    public List<String> listPersistedActors() {
        List<String> actorIds = new ArrayList<>();
        Path snapshotDir = Paths.get(baseDir, SNAPSHOT_DIR);

        // Check if snapshot directory exists
        if (!Files.exists(snapshotDir)) {
            logger.debug("Snapshot directory does not exist: {}", snapshotDir);
            return actorIds;
        }

        try {
            // Scan for snapshot files
            scanSnapshotFiles(snapshotDir, actorIds);

            logger.debug("Found {} persisted actors", actorIds.size());
            return actorIds;

        } catch (IOException e) {
            logger.warn("Failed to scan persisted actors from {}", snapshotDir, e);
            return actorIds;
        }
    }

    /**
     * Recursively scan for snapshot files and extract actor IDs.
     * Snapshot files are named: actorId.snapshot
     */
    private void scanSnapshotFiles(Path dir, List<String> actorIds) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }

        try (Stream<Path> paths = Files.list(dir)) {
            paths.forEach(path -> {
                try {
                    if (Files.isDirectory(path)) {
                        // Recursively scan subdirectories
                        scanSnapshotFiles(path, actorIds);
                    } else if (Files.isRegularFile(path)) {
                        String fileName = path.getFileName().toString();
                        if (fileName.endsWith(".snapshot")) {
                            // Extract actor ID from filename
                            String actorId = fileName.substring(0, fileName.length() - ".snapshot".length());
                            actorIds.add(actorId);
                        }
                    }
                } catch (IOException e) {
                    logger.warn("Error scanning path: {}", path, e);
                }
            });
        }
    }
}
