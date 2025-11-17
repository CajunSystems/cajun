package com.cajunsystems.persistence.runtime.persistence;

import com.cajunsystems.persistence.SnapshotEntry;
import com.cajunsystems.persistence.SnapshotMetadata;
import com.cajunsystems.persistence.SnapshotStore;
import org.lmdbjava.Cursor;
import org.lmdbjava.Dbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.zip.CRC32;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * LMDB-backed snapshot store implementation.
 * 
 * Features:
 * - Persistent snapshot storage using LMDB
 * - CRC32 checksums for data integrity
 * - Efficient key-value storage
 * - Automatic pruning support
 * 
 * @param <S> The type of the state
 */
public class LmdbSnapshotStore<S> implements SnapshotStore<S> {
    
    private static final Logger logger = LoggerFactory.getLogger(LmdbSnapshotStore.class);
    
    private final LmdbEnvironmentManager envManager;
    private final Dbi<ByteBuffer> snapshotDb;
    private final String actorId;
    
    /**
     * Creates a new LMDB snapshot store for the specified actor.
     *
     * @param actorId The ID of the actor this store is for
     * @param envManager The environment manager for LMDB operations
     */
    public LmdbSnapshotStore(String actorId, LmdbEnvironmentManager envManager) {
        this.actorId = actorId;
        this.envManager = envManager;
        this.snapshotDb = envManager.getDatabase("snapshot_" + actorId);
        
        logger.info("LMDB Snapshot Store initialized for actor: {}", actorId);
    }
    
    @Override
    public CompletableFuture<Void> saveSnapshot(String actorId, S state, long sequenceNumber) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Serialize the snapshot entry
                SnapshotEntry<S> entry = new SnapshotEntry<>(actorId, state, sequenceNumber, Instant.now());
                byte[] entryBytes = serializeWithChecksum(entry);
                
                // Store in LMDB
                envManager.writeTransaction(txn -> {
                    ByteBuffer key = createSnapshotKey(sequenceNumber);
                    ByteBuffer value = bytesToBuffer(entryBytes);
                    snapshotDb.put(txn, key, value);
                    
                    // Update latest snapshot pointer
                    ByteBuffer latestKey = bytesToBuffer("latest_snapshot");
                    ByteBuffer latestValue = ByteBuffer.allocateDirect(8);
                    latestValue.putLong(sequenceNumber).flip();
                    snapshotDb.put(txn, latestKey, latestValue);
                    
                    return null;
                });
                
                logger.debug("Saved snapshot for actor {} at sequence {}", actorId, sequenceNumber);
                
            } catch (Exception e) {
                // Reduce shutdown noise: if the environment manager is closed, treat as benign
                Throwable cause = e;
                while (cause.getCause() != null && cause != cause.getCause()) {
                    cause = cause.getCause();
                }
                if (cause instanceof IllegalStateException &&
                    cause.getMessage() != null &&
                    cause.getMessage().contains("Environment manager is closed")) {
                    logger.debug("Ignoring snapshot save for actor {} - LMDB environment is already closed", actorId);
                    return;
                }

                logger.error("Failed to save snapshot for actor: {}", actorId, e);
                throw new RuntimeException("Failed to save snapshot", e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Optional<SnapshotEntry<S>>> getLatestSnapshot(String actorId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return envManager.readTransaction(txn -> {
                    // Get latest snapshot sequence number
                    ByteBuffer latestKey = bytesToBuffer("latest_snapshot");
                    ByteBuffer latestValue = snapshotDb.get(txn, latestKey);
                    
                    if (latestValue == null) {
                        logger.debug("No snapshot found for actor {}", actorId);
                        return Optional.empty();
                    }
                    
                    long latestSequence = latestValue.getLong();
                    
                    // Retrieve the snapshot
                    ByteBuffer snapshotKey = createSnapshotKey(latestSequence);
                    ByteBuffer snapshotValue = snapshotDb.get(txn, snapshotKey);
                    
                    if (snapshotValue == null) {
                        logger.warn("Latest snapshot pointer exists but snapshot data missing for actor {}", actorId);
                        return Optional.empty();
                    }
                    
                    byte[] snapshotBytes = new byte[snapshotValue.remaining()];
                    snapshotValue.get(snapshotBytes);
                    
                    SnapshotEntry<S> entry = deserializeWithChecksum(snapshotBytes);
                    logger.debug("Loaded latest snapshot for actor {} at sequence {}", actorId, entry.getSequenceNumber());
                    return Optional.of(entry);
                });
                
            } catch (Exception e) {
                logger.error("Failed to get latest snapshot for actor: {}", actorId, e);
                throw new RuntimeException("Failed to get latest snapshot", e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> deleteSnapshots(String actorId) {
        return CompletableFuture.runAsync(() -> {
            try {
                envManager.writeTransaction(txn -> {
                    // Delete all snapshots for this actor
                    try (Cursor<ByteBuffer> cursor = snapshotDb.openCursor(txn)) {
                        while (cursor.next()) {
                            cursor.delete();
                        }
                    }
                    return null;
                });
                
                logger.debug("Deleted all snapshots for actor {}", actorId);
                
            } catch (Exception e) {
                logger.error("Failed to delete snapshots for actor: {}", actorId, e);
                throw new RuntimeException("Failed to delete snapshots", e);
            }
        });
    }
    
    @Override
    public CompletableFuture<List<SnapshotMetadata>> listSnapshots(String actorId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return envManager.readTransaction(txn -> {
                    List<SnapshotMetadata> snapshots = new ArrayList<>();
                    
                    try (Cursor<ByteBuffer> cursor = snapshotDb.openCursor(txn)) {
                        while (cursor.next()) {
                            ByteBuffer key = cursor.key();
                            ByteBuffer value = cursor.val();
                            
                            // Skip metadata keys
                            if (key.remaining() != 8) {
                                continue;
                            }
                            
                            long sequence = key.getLong();
                            key.rewind();
                            
                            byte[] snapshotBytes = new byte[value.remaining()];
                            value.get(snapshotBytes);
                            
                            try {
                                SnapshotEntry<S> entry = deserializeWithChecksum(snapshotBytes);
                                long timestamp = entry.getTimestamp().toEpochMilli();
                                snapshots.add(new SnapshotMetadata(sequence, timestamp, snapshotBytes.length));
                            } catch (Exception e) {
                                logger.warn("Failed to parse snapshot metadata at sequence {}", sequence, e);
                            }
                        }
                    }
                    
                    snapshots.sort(Comparator.comparingLong(SnapshotMetadata::getSequence).reversed());
                    return snapshots;
                });
                
            } catch (Exception e) {
                logger.error("Failed to list snapshots for actor: {}", actorId, e);
                return new ArrayList<>();
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
            
            try {
                deletedCount = envManager.writeTransaction(txn -> {
                    int deleted = 0;
                    for (SnapshotMetadata snapshot : toDelete) {
                        ByteBuffer key = createSnapshotKey(snapshot.getSequence());
                        if (snapshotDb.delete(txn, key)) {
                            deleted++;
                            logger.debug("Deleted old snapshot: seq={} for actor: {}", 
                                snapshot.getSequence(), actorId);
                        }
                    }
                    return deleted;
                });
                
                if (deletedCount > 0) {
                    logger.info("Pruned {} old snapshots for actor: {} (kept {})", 
                        deletedCount, actorId, keepCount);
                }
            } catch (Exception e) {
                logger.error("Failed to prune snapshots for actor: {}", actorId, e);
            }
            
            return deletedCount;
        });
    }
    
    @Override
    public void close() {
        logger.info("Closing LMDB Snapshot Store for actor: {}", actorId);
        // Environment is managed by envManager, no cleanup needed here
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
    
    private ByteBuffer createSnapshotKey(long sequenceNumber) {
        ByteBuffer key = ByteBuffer.allocateDirect(8);
        key.putLong(sequenceNumber).flip();
        return key;
    }
    
    private ByteBuffer bytesToBuffer(String str) {
        byte[] bytes = str.getBytes(UTF_8);
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes).flip();
        return buffer;
    }
    
    private ByteBuffer bytesToBuffer(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes).flip();
        return buffer;
    }
}
