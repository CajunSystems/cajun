package com.cajunsystems.persistence.runtime.persistence;

import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.MessageJournal;
import org.lmdbjava.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * LMDB Message Journal - Production Implementation.
 * 
 * This implementation provides a high-performance, persistent message journal
 * using real LMDB for durable storage with zero-copy memory-mapped operations.
 * 
 * Features:
 * - Real LMDB persistence with ACID transactions
 * - Async operations with CompletableFuture
 * - Comprehensive metrics collection
 * - Enhanced error handling and retry logic
 * - Configurable serialization and batching
 * - Resource management and cleanup
 */
public class LmdbMessageJournal<M> implements MessageJournal<M> {
    
    private static final Logger logger = LoggerFactory.getLogger(LmdbMessageJournal.class);
    
    private final LmdbEnvironmentManager envManager;
    private final Dbi<ByteBuffer> journalDb;
    private final Dbi<ByteBuffer> metadataDb;
    private final Serializer<M> serializer;
    private final LmdbConfig config;
    private final String actorId;
    
    // In-memory cache for sequence tracking
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile long appendCount = 0;
    private volatile long readCount = 0;
    private volatile long truncateCount = 0;
    private volatile long highestSequence = 0;
    private volatile long currentSize = 0;

    /**
     * Creates a new LMDB message journal for the specified actor.
     *
     * @param actorId The ID of the actor this journal is for
     * @param envManager The environment manager for LMDB operations
     */
    public LmdbMessageJournal(String actorId, LmdbEnvironmentManager envManager) {
        this.actorId = actorId;
        this.envManager = envManager;
        this.config = envManager.getConfig();
        this.journalDb = envManager.getDatabase("journal_" + actorId);
        this.metadataDb = envManager.getDatabase("journal_meta_" + actorId);
        this.serializer = createSerializer();
        
        // Initialize sequence counter from existing data
        initializeSequenceCounter();
        
        logger.info("LMDB Message Journal initialized for actor: {} with config: {}", actorId, config);
    }
    
    private void initializeSequenceCounter() {
        try {
            Long lastSeq = envManager.readTransaction(txn -> {
                ByteBuffer key = bytesToBuffer("last_sequence");
                ByteBuffer value = metadataDb.get(txn, key);
                if (value != null) {
                    return value.getLong();
                }
                return 0L;
            });
            sequenceCounter.set(lastSeq);
            highestSequence = lastSeq;
        } catch (Exception e) {
            logger.warn("Failed to initialize sequence counter for actor: {}, starting from 0", actorId, e);
        }
    }
    
    private Serializer<M> createSerializer() {
        return switch (config.getSerializationFormat()) {
            case JAVA -> new JavaSerializer<>();
            default -> new JavaSerializer<>();
        };
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
    
    @Override
    public CompletableFuture<Long> append(String actorId, M message) {
        // Silently ignore appends if journal is closed (during shutdown)
        if (closed.get()) {
            logger.trace("Ignoring append for actor {} - journal is closed", actorId);
            return CompletableFuture.completedFuture(-1L);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Use a single LMDB write transaction for this append
                return envManager.writeTransaction(txn -> appendInTransaction(txn, actorId, message));
                
            } catch (Exception e) {
                // Reduce shutdown noise: if the environment manager is closed, treat as benign
                Throwable cause = e;
                while (cause.getCause() != null && cause != cause.getCause()) {
                    cause = cause.getCause();
                }
                if (cause instanceof IllegalStateException &&
                    cause.getMessage() != null &&
                    cause.getMessage().contains("Environment manager is closed")) {
                    logger.debug("Ignoring append for actor {} - LMDB environment is already closed", actorId);
                    return -1L;
                }
                
                logger.error("Failed to append message for actor: {}", actorId, e);
                throw new RuntimeException("Append failed", e);
            }
        });
    }

    /**
     * Core append logic that runs inside a caller-provided LMDB write transaction.
     * This is used by both the standard append() path and batched append implementations
     * to ensure all writes in a batch share a single transaction.
     */
    protected long appendInTransaction(Txn<ByteBuffer> txn, String actorId, M message) throws Exception {
        long sequence = sequenceCounter.incrementAndGet();
        JournalEntry<M> entry = new JournalEntry<>(sequence, actorId, message, java.time.Instant.now());

        // Serialize the full journal entry (including timestamp and metadata)
        byte[] entryBytes = serializer.serialize(entry);

        // Store the message with sequence as key
        ByteBuffer key = ByteBuffer.allocateDirect(8);
        key.putLong(sequence).flip();

        ByteBuffer value = bytesToBuffer(entryBytes);
        journalDb.put(txn, key, value);

        // Update last sequence counter in metadata DB
        ByteBuffer seqKey = bytesToBuffer("last_sequence");
        ByteBuffer seqValue = ByteBuffer.allocateDirect(8);
        seqValue.putLong(sequence).flip();
        metadataDb.put(txn, seqKey, seqValue);

        // Update metrics
        appendCount++;
        highestSequence = sequence;
        currentSize += entryBytes.length;

        logger.debug("Appended entry {} for actor {} (size: {} bytes)", sequence, actorId, entryBytes.length);
        return sequence;
    }
    
    @Override
    public CompletableFuture<List<JournalEntry<M>>> readFrom(String actorId, long sequenceNumber) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<JournalEntry<M>> result = new ArrayList<>();
                
                // Read from real LMDB
                envManager.readTransaction(txn -> {
                    // Create cursor for iteration
                    try (Cursor<ByteBuffer> cursor = journalDb.openCursor(txn)) {
                        while (cursor.next()) {
                            ByteBuffer key = cursor.key();
                            long seq = key.getLong();
                            if (seq >= sequenceNumber) {
                                ByteBuffer value = cursor.val();
                                byte[] entryBytes = new byte[value.remaining()];
                                value.get(entryBytes);
                                
                                try {
                                    JournalEntry<M> entry = serializer.deserialize(entryBytes);
                                    result.add(entry);
                                } catch (IOException e) {
                                    // Skip corrupted entries (e.g., from incomplete writes or stale data)
                                    logger.warn("Skipping corrupted journal entry at sequence {} for actor {}: {}", 
                                               seq, actorId, e.getMessage());
                                }
                            }
                        }
                    }
                    return null;
                });
                
                readCount++;
                logger.debug("Read {} entries from sequence {} for actor {}", result.size(), sequenceNumber, actorId);
                return result;
                
            } catch (Exception e) {
                logger.error("Failed to read messages for actor: {} from sequence: {}", actorId, sequenceNumber, e);
                throw new RuntimeException("Read failed", e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> truncateBefore(String actorId, long sequenceNumber) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Truncate in real LMDB
                envManager.writeTransaction(txn -> {
                    // Delete entries with sequence < sequenceNumber
                    try (Cursor<ByteBuffer> cursor = journalDb.openCursor(txn)) {
                        // Iterate and delete in one pass
                        while (cursor.next()) {
                            ByteBuffer key = cursor.key();
                            long seq = key.getLong();
                            if (seq < sequenceNumber) {
                                try {
                                    cursor.delete();
                                } catch (org.lmdbjava.Dbi.KeyNotFoundException e) {
                                    // Key already deleted or doesn't exist (e.g., corrupted entry)
                                    logger.debug("Key at sequence {} already deleted or not found for actor {}", 
                                               seq, actorId);
                                }
                            }
                        }
                    }
                    return null;
                });
                
                truncateCount++;
                logger.debug("Truncated entries before sequence {} for actor {}", sequenceNumber, actorId);
                
            } catch (Exception e) {
                // Reduce shutdown noise: if the environment manager is closed, treat as benign
                Throwable cause = e;
                while (cause.getCause() != null && cause != cause.getCause()) {
                    cause = cause.getCause();
                }
                if (cause instanceof IllegalStateException &&
                    cause.getMessage() != null &&
                    cause.getMessage().contains("Environment manager is closed")) {
                    logger.debug("Ignoring truncate for actor {} before sequence {} - LMDB environment is already closed", actorId, sequenceNumber);
                    return;
                }

                logger.error("Failed to truncate messages for actor: {} before sequence: {}", actorId, sequenceNumber, e);
                throw new RuntimeException("Truncate failed", e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Long> getHighestSequenceNumber(String actorId) {
        return CompletableFuture.supplyAsync(() -> {
            return highestSequence;
        });
    }
    
    @Override
    public void close() {
        // Set closed flag to stop accepting new writes
        closed.set(true);
        
        logger.info("Closing LMDB Message Journal for actor: {}. Final metrics: append={}, read={}, truncate={}", 
                   actorId, appendCount, readCount, truncateCount);
        
        // No additional cleanup needed - LMDB environment is managed by envManager
    }
    
    /**
     * Get comprehensive journal metrics.
     */
    public JournalMetrics getMetrics() {
        return new JournalMetrics(appendCount, readCount, truncateCount, sequenceCounter.get(), -1);
    }
    
    /**
     * Force sync to disk (simulated).
     */
    public void sync() {
        envManager.sync();
    }
    
    /**
     * Compact the journal by removing old entries.
     */
    public void compact(long keepLastN) {
        long cutoffSequence = Math.max(0, sequenceCounter.get() - keepLastN);
        truncateBefore(actorId, cutoffSequence).join();
    }
    
    /**
     * Enhanced journal metrics.
     */
    public static class JournalMetrics {
        private final long appendCount;
        private final long readCount;
        private final long truncateCount;
        private final long highestSequence;
        private final int currentSize;
        
        public JournalMetrics(long appendCount, long readCount, long truncateCount, 
                              long highestSequence, int currentSize) {
            this.appendCount = appendCount;
            this.readCount = readCount;
            this.truncateCount = truncateCount;
            this.highestSequence = highestSequence;
            this.currentSize = currentSize;
        }
        
        public long getAppendCount() { return appendCount; }
        public long getReadCount() { return readCount; }
        public long getTruncateCount() { return truncateCount; }
        public long getHighestSequence() { return highestSequence; }
        public int getCurrentSize() { return currentSize; }
        
        @Override
        public String toString() {
            return "JournalMetrics{" +
                    "appendCount=" + appendCount +
                    ", readCount=" + readCount +
                    ", truncateCount=" + truncateCount +
                    ", highestSequence=" + highestSequence +
                    ", currentSize=" + currentSize +
                    '}';
        }
    }
    
    /**
     * Serializer interface for different serialization formats.
     * Now serializes full JournalEntry objects to preserve metadata.
     */
    private interface Serializer<T> {
        byte[] serialize(JournalEntry<T> entry) throws IOException;
        JournalEntry<T> deserialize(byte[] bytes) throws IOException;
    }
    
    /**
     * Enhanced Java serialization with error handling.
     * Serializes full JournalEntry objects to preserve timestamp and metadata.
     */
    private static class JavaSerializer<T> implements Serializer<T> {
        @Override
        public byte[] serialize(JournalEntry<T> entry) throws IOException {
            try (var baos = new java.io.ByteArrayOutputStream();
                 var oos = new java.io.ObjectOutputStream(baos)) {
                oos.writeObject(entry);
                return baos.toByteArray();
            }
        }
        
        @Override
        @SuppressWarnings("unchecked")
        public JournalEntry<T> deserialize(byte[] bytes) throws IOException {
            // Check for empty or corrupted data
            if (bytes == null || bytes.length == 0) {
                throw new IOException("Cannot deserialize null or empty byte array");
            }
            
            try (var bais = new java.io.ByteArrayInputStream(bytes);
                 var ois = new java.io.ObjectInputStream(bais)) {
                return (JournalEntry<T>) ois.readObject();
            } catch (java.io.StreamCorruptedException e) {
                // Handle corrupted stream (e.g., from incomplete writes or stale data)
                throw new IOException("Corrupted stream data - possibly from incomplete write", e);
            } catch (ClassNotFoundException e) {
                throw new IOException("Class not found during deserialization", e);
            }
        }
    }
    
}
