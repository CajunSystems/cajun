package systems.cajun.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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
                Optional<Path> latestSnapshotFile = Files.list(actorSnapshotDir)
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".snap"))
                        .max(Comparator.comparing(p -> p.getFileName().toString()));
                
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
                Files.list(actorSnapshotDir)
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".snap"))
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException e) {
                                logger.error("Failed to delete snapshot file {}", p, e);
                            }
                        });
                
                logger.debug("Deleted all snapshots for actor {}", actorId);
            } catch (IOException e) {
                logger.error("Failed to delete snapshots for actor {}", actorId, e);
                throw new RuntimeException("Failed to delete snapshots for actor " + actorId, e);
            }
        });
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
