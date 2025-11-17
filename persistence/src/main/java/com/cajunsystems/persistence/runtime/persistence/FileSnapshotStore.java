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
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * A file-based implementation of the SnapshotStore interface.
 * Stores snapshots in files, one directory per actor.
 *
 * @param <S> The type of the state
 */
public class FileSnapshotStore<S> implements SnapshotStore<S> {
    private static final Logger logger = LoggerFactory.getLogger(FileSnapshotStore.class);
    
    private final Path snapshotDir;
    private final ExecutorService ioExecutor;
    private volatile boolean closed = false;
    
    /**
     * Creates a new FileSnapshotStore with the specified directory.
     *
     * @param snapshotDir The directory to store snapshot files in
     */
    public FileSnapshotStore(Path snapshotDir) {
        this.snapshotDir = snapshotDir;
        
        // Create dedicated IO executor
        this.ioExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "file-snapshot-io");
                t.setDaemon(true);
                return t;
            }
        );
        
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
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Snapshot store is closed"));
        }
        
        return CompletableFuture.runAsync(() -> {
            try {
                    Path actorSnapshotDir = getActorSnapshotDir(actorId);
                
                // Create snapshot entry
                SnapshotEntry<S> entry = new SnapshotEntry<>(actorId, state, sequenceNumber, Instant.now());
                
                // Prepare snapshot file path
                String fileName = String.format("snapshot_%020d_%s.snap", 
                        sequenceNumber, System.currentTimeMillis());
                Path snapshotFile = actorSnapshotDir.resolve(fileName);
                
                // Serialize with checksum once
                byte[] data = serializeWithChecksum(entry);
                
                // Atomic move to final location with retry on directory deletion
                boolean moved = false;
                int retries = 3;
                Exception lastException = null;
                Path tempFile = null;
                
                for (int attempt = 0; attempt < retries && !moved; attempt++) {
                    try {
                        // Ensure actor directory exists before each attempt
                        Files.createDirectories(actorSnapshotDir);
                        
                        // Write temp file INSIDE actor directory on each attempt
                        // This ensures temp file exists for the move even if directory was recreated
                        tempFile = actorSnapshotDir.resolve(fileName + ".tmp");
                        try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                            fos.write(data);
                            fos.getFD().sync(); // Ensure data is on disk
                        }
                        
                        // Atomic move to final location
                        Files.move(tempFile, snapshotFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        moved = true;
                    } catch (java.nio.file.NoSuchFileException e) {
                        // Directory was deleted during write or move - retry
                        lastException = e;
                        logger.debug("Directory deleted during save attempt {} for actor {}, retrying...", 
                            attempt + 1, actorId);
                        
                        if (attempt < retries - 1) {
                            try {
                                Thread.sleep(10); // Brief pause before retry
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException("Interrupted during retry", ie);
                            }
                        }
                    } catch (Exception moveEx) {
                        lastException = moveEx;
                        break; // Don't retry on other exceptions
                    }
                }
                
                if (!moved) {
                    // Clean up temp file on failure
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException cleanupEx) {
                        logger.warn("Failed to clean up temp file {}", tempFile, cleanupEx);
                    }
                    
                    if (lastException != null) {
                        throw lastException;
                    } else {
                        throw new RuntimeException("Failed to move snapshot file after " + retries + " attempts");
                    }
                }
                
                logger.debug("Saved snapshot for actor {} at sequence number {}", actorId, sequenceNumber);
            } catch (Exception e) {
                logger.error("Failed to save snapshot for actor {}", actorId, e);
                throw new RuntimeException("Failed to save snapshot for actor " + actorId, e);
            }
        }, ioExecutor);
    }
    
    @Override
    public CompletableFuture<Optional<SnapshotEntry<S>>> getLatestSnapshot(String actorId) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Snapshot store is closed"));
        }
        
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
                    // Read and verify snapshot with checksum
                    try {
                        byte[] data = Files.readAllBytes(latestSnapshotFile.get());
                        SnapshotEntry<S> entry = deserializeWithChecksum(data);
                        logger.debug("Loaded latest snapshot for actor {} at sequence number {}", 
                                actorId, entry.getSequenceNumber());
                        return Optional.of(entry);
                    } catch (IOException e) {
                        logger.error("Failed to read snapshot file {}", latestSnapshotFile.get(), e);
                        throw new RuntimeException("Failed to read snapshot", e);
                    }
                } else {
                    logger.debug("No snapshot found for actor {}", actorId);
                    return Optional.empty();
                }
            } catch (IOException e) {
                logger.error("Failed to get latest snapshot for actor {}", actorId, e);
                throw new RuntimeException("Failed to get latest snapshot for actor " + actorId, e);
            }
        }, ioExecutor);
    }
    
    @Override
    public CompletableFuture<Void> deleteSnapshots(String actorId) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Snapshot store is closed"));
        }
        
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
        }, ioExecutor);
    }
    
    @Override
    public CompletableFuture<List<SnapshotMetadata>> listSnapshots(String actorId) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Snapshot store is closed"));
        }
        
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
        }, ioExecutor);
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
    
    /**
     * Serializes a snapshot entry with CRC32 checksum.
     */
    private byte[] serializeWithChecksum(SnapshotEntry<S> entry) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(entry);
            oos.flush();
            
            byte[] data = baos.toByteArray();
            
            // Calculate CRC32
            CRC32 crc = new CRC32();
            crc.update(data);
            long checksum = crc.getValue();
            
            // Prepend checksum to data
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(result);
            dos.writeLong(checksum);
            dos.write(data);
            dos.flush();
            
            return result.toByteArray();
        }
    }
    
    /**
     * Deserializes a snapshot entry and verifies CRC32 checksum.
     */
    @SuppressWarnings("unchecked")
    private SnapshotEntry<S> deserializeWithChecksum(byte[] bytes) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             DataInputStream dis = new DataInputStream(bais)) {
            
            // Read checksum
            long expectedChecksum = dis.readLong();
            
            // Read data
            byte[] data = dis.readAllBytes();
            
            // Verify checksum
            CRC32 crc = new CRC32();
            crc.update(data);
            long actualChecksum = crc.getValue();
            
            if (expectedChecksum != actualChecksum) {
                throw new IOException("Snapshot checksum mismatch: expected=" + expectedChecksum + 
                                    ", actual=" + actualChecksum);
            }
            
            // Deserialize
            try (ByteArrayInputStream dataBais = new ByteArrayInputStream(data);
                 ObjectInputStream ois = new ObjectInputStream(dataBais)) {
                return (SnapshotEntry<S>) ois.readObject();
            } catch (ClassNotFoundException e) {
                throw new IOException("Failed to deserialize snapshot entry", e);
            }
        }
    }
    
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        
        // Shutdown IO executor with short timeout - cleanup operations are best-effort
        ioExecutor.shutdown();
        try {
            // Only wait 1 second for graceful shutdown, then force shutdown
            // Prune and cleanup operations are non-critical and can be interrupted
            if (!ioExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                logger.debug("IO executor did not terminate gracefully, forcing shutdown");
                ioExecutor.shutdownNow();
                // Give interrupted tasks a brief moment to clean up
                if (!ioExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    logger.warn("IO executor did not terminate after forced shutdown");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
        
        logger.info("File snapshot store closed");
    }
}
