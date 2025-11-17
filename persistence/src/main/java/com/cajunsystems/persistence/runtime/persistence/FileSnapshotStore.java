package com.cajunsystems.persistence.runtime.persistence;

import com.cajunsystems.persistence.SnapshotEntry;
import com.cajunsystems.persistence.SnapshotMetadata;
import com.cajunsystems.persistence.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A file-based implementation of the SnapshotStore interface.
 * Stores snapshots in files, one directory per actor.
 *
 * @param <S> The type of the state
 */
public class FileSnapshotStore<S> implements SnapshotStore<S> {
    private static final Logger logger = LoggerFactory.getLogger(FileSnapshotStore.class);
    
    private final Path snapshotDir;
    
    /**
     * Creates a new FileSnapshotStore with the specified directory.
     *
     * @param snapshotDir The directory to store snapshot files in
     */
    public FileSnapshotStore(Path snapshotDir) {
        this.snapshotDir = snapshotDir;
        try {
            Files.createDirectories(snapshotDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create snapshot directory: " + snapshotDir, e);
        }
    }
    
    /**
     * Creates a new FileSnapshotStore with the specified directory path.
     *
     * @param snapshotDirPath The path to the directory to store snapshot files in
     */
    public FileSnapshotStore(String snapshotDirPath) {
        this(Paths.get(snapshotDirPath));
    }
    
    @Override
    public CompletableFuture<Void> saveSnapshot(String actorId, S state, long sequenceNumber) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path actorSnapshotDir = getActorSnapshotDir(actorId);
                Files.createDirectories(actorSnapshotDir);
                
                // Create snapshot entry
                SnapshotEntry<S> entry = new SnapshotEntry<>(actorId, state, sequenceNumber, Instant.now());
                
                // Write to file
                String fileName = String.format("snapshot_%020d_%s.snap", 
                        sequenceNumber, System.currentTimeMillis());
                Path snapshotFile = actorSnapshotDir.resolve(fileName);
                
                try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(snapshotFile.toFile()))) {
                    oos.writeObject(entry);
                }
                
                logger.debug("Saved snapshot for actor {} at sequence number {}", actorId, sequenceNumber);
            } catch (Exception e) {
                logger.error("Failed to save snapshot for actor {}", actorId, e);
                throw new RuntimeException("Failed to save snapshot for actor " + actorId, e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Optional<SnapshotEntry<S>>> getLatestSnapshot(String actorId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path actorSnapshotDir = getActorSnapshotDir(actorId);
                if (!Files.exists(actorSnapshotDir)) {
                    return Optional.empty();
                }
                
                // Find the latest snapshot file
                Optional<Path> latestSnapshotFile;
                try (Stream<Path> paths = Files.list(actorSnapshotDir)) {
                    latestSnapshotFile = paths
                            .filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".snap"))
                            .max(Comparator.comparing(p -> p.getFileName().toString()));
                } catch (java.nio.file.NoSuchFileException e) {
                    // Directory was deleted between existence check and listing
                    logger.debug("Snapshot directory no longer exists for actor: {}", actorId);
                    return Optional.empty();
                }
                
                if (latestSnapshotFile.isPresent()) {
                    // Read snapshot entry
                    try (ObjectInputStream is = new ObjectInputStream(
                            new FileInputStream(latestSnapshotFile.get().toFile()))) {
                        @SuppressWarnings("unchecked")
                        SnapshotEntry<S> entry = (SnapshotEntry<S>) is.readObject();
                        logger.debug("Loaded latest snapshot for actor {} at sequence number {}", 
                                actorId, entry.getSequenceNumber());
                        return Optional.of(entry);
                    } catch (ClassNotFoundException e) {
                        logger.error("Failed to deserialize snapshot entry from file {}", 
                                latestSnapshotFile.get(), e);
                        throw new RuntimeException("Failed to deserialize snapshot entry", e);
                    }
                } else {
                    logger.debug("No snapshot found for actor {}", actorId);
                    return Optional.empty();
                }
            } catch (IOException e) {
                logger.error("Failed to get latest snapshot for actor {}", actorId, e);
                throw new RuntimeException("Failed to get latest snapshot for actor " + actorId, e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> deleteSnapshots(String actorId) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path actorSnapshotDir = getActorSnapshotDir(actorId);
                if (!Files.exists(actorSnapshotDir)) {
                    return;
                }
                
                // Delete all snapshot files
                try (Stream<Path> paths = Files.list(actorSnapshotDir)) {
                    paths
                            .filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".snap"))
                            .forEach(p -> {
                                try {
                                    Files.delete(p);
                                } catch (IOException e) {
                                    logger.error("Failed to delete snapshot file {}", p, e);
                                }
                            });
                } catch (java.nio.file.NoSuchFileException e) {
                    // Directory was deleted between existence check and listing
                    logger.debug("Snapshot directory no longer exists for actor: {}", actorId);
                    return;
                }
                
                logger.debug("Deleted all snapshots for actor {}", actorId);
            } catch (IOException e) {
                logger.error("Failed to delete snapshots for actor {}", actorId, e);
                throw new RuntimeException("Failed to delete snapshots for actor " + actorId, e);
            }
        });
    }
    
    @Override
    public CompletableFuture<List<SnapshotMetadata>> listSnapshots(String actorId) {
        return CompletableFuture.supplyAsync(() -> {
            Path actorDir = getActorSnapshotDir(actorId);
            if (!Files.exists(actorDir)) {
                return Collections.emptyList();
            }
            
            try (Stream<Path> paths = Files.list(actorDir)) {
                return paths
                    .filter(p -> p.getFileName().toString().endsWith(".snap"))
                    .map(this::parseSnapshotMetadata)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .sorted(Comparator.comparingLong(SnapshotMetadata::getSequence).reversed())
                    .collect(Collectors.toList());
            } catch (java.nio.file.NoSuchFileException e) {
                // Directory was deleted between existence check and listing (e.g., during shutdown)
                logger.debug("Snapshot directory no longer exists for actor: {}", actorId);
                return Collections.emptyList();
            } catch (IOException e) {
                logger.error("Failed to list snapshots for actor: {}", actorId, e);
                return Collections.emptyList();
            }
        });
    }
    
    @Override
    public CompletableFuture<Integer> pruneOldSnapshots(String actorId, int keepCount) {
        if (keepCount < 1) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("keepCount must be at least 1"));
        }
        
        return listSnapshots(actorId).thenApply(snapshots -> {
            if (snapshots.size() <= keepCount) {
                logger.debug("No snapshots to prune for actor: {} (have {}, keeping {})", 
                    actorId, snapshots.size(), keepCount);
                return 0;
            }
            
            // Keep the N most recent, delete the rest
            List<SnapshotMetadata> toDelete = snapshots.subList(keepCount, snapshots.size());
            int deletedCount = 0;
            
            for (SnapshotMetadata snapshot : toDelete) {
                Path snapshotFile = getSnapshotPath(actorId, snapshot.getSequence(), snapshot.getTimestamp());
                try {
                    if (Files.deleteIfExists(snapshotFile)) {
                        deletedCount++;
                        logger.debug("Deleted old snapshot: seq={} for actor: {}", 
                            snapshot.getSequence(), actorId);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to delete snapshot: seq={} for actor: {}", 
                        snapshot.getSequence(), actorId, e);
                }
            }
            
            if (deletedCount > 0) {
                logger.info("Pruned {} old snapshots for actor: {} (kept {})", 
                    deletedCount, actorId, keepCount);
            }
            
            return deletedCount;
        });
    }
    
    private Optional<SnapshotMetadata> parseSnapshotMetadata(Path snapshotFile) {
        try {
            String filename = snapshotFile.getFileName().toString();
            // Parse: snapshot_%020d_%s.snap
            // Example: snapshot_00000000000000000001_1731684000000.snap
            
            if (!filename.startsWith("snapshot_") || !filename.endsWith(".snap")) {
                logger.warn("Invalid snapshot filename format: {}", filename);
                return Optional.empty();
            }
            
            String withoutPrefix = filename.substring("snapshot_".length());
            String withoutSuffix = withoutPrefix.substring(0, withoutPrefix.length() - ".snap".length());
            String[] parts = withoutSuffix.split("_");
            
            if (parts.length < 2) {
                logger.warn("Invalid snapshot filename format: {}", filename);
                return Optional.empty();
            }
            
            long sequence = Long.parseLong(parts[0]);
            long timestamp = Long.parseLong(parts[1]);
            long fileSize = Files.size(snapshotFile);
            
            return Optional.of(new SnapshotMetadata(sequence, timestamp, fileSize));
        } catch (java.nio.file.NoSuchFileException e) {
            // Snapshot was removed concurrently (e.g., pruning) – not an error, just noisy
            logger.debug("Snapshot file disappeared while parsing metadata: {}", snapshotFile);
            return Optional.empty();
        } catch (IOException | NumberFormatException e) {
            logger.warn("Failed to parse snapshot metadata from: {}", snapshotFile, e);
            return Optional.empty();
        }
    }
    
    private Path getSnapshotPath(String actorId, long sequenceNumber, long timestamp) {
        Path actorDir = getActorSnapshotDir(actorId);
        return actorDir.resolve(String.format("snapshot_%020d_%s.snap", sequenceNumber, timestamp));
    }
    
    private Path getActorSnapshotDir(String actorId) {
        // Sanitize actor ID to be a valid directory name
        String sanitizedId = actorId.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return snapshotDir.resolve(sanitizedId);
    }
    
    @Override
    public void close() {
        // Nothing to close for file-based snapshot store
    }
}
