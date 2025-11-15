package com.cajunsystems.runtime.persistence;

import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.MessageJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Optimized File-based Message Journal that batches multiple messages per file
 * to reduce filesystem overhead during benchmarks.
 * 
 * Instead of creating one file per message, this implementation creates
 * one file per batch of messages (configurable batch size).
 */
public class BatchedFileMessageJournalOptimized<M extends Serializable> implements MessageJournal<M> {
    
    private static final Logger logger = LoggerFactory.getLogger(BatchedFileMessageJournalOptimized.class);
    
    private final Path journalDir;
    private final int messagesPerFile;
    private final Map<String, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();
    private final Map<String, List<JournalEntry<M>>> pendingBatches = new ConcurrentHashMap<>();
    private final ScheduledExecutorService batchExecutor = Executors.newSingleThreadScheduledExecutor();
    
    public BatchedFileMessageJournalOptimized(Path journalDir) {
        this(journalDir, 100); // Default 100 messages per file
    }
    
    public BatchedFileMessageJournalOptimized(Path journalDir, int messagesPerFile) {
        this.journalDir = journalDir;
        this.messagesPerFile = messagesPerFile;
        
        try {
            Files.createDirectories(journalDir);
            // Schedule batch flushing every 5 seconds
            batchExecutor.scheduleAtFixedRate(this::flushAllBatches, 5, 5, TimeUnit.SECONDS);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create journal directory: " + journalDir, e);
        }
    }
    
    @Override
    public CompletableFuture<Long> append(String actorId, M message) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Get or initialize sequence counter for this actor
                AtomicLong counter = sequenceCounters.computeIfAbsent(actorId, k -> {
                    try {
                        long highestSeq = getHighestSequenceNumberSync(actorId);
                        return new AtomicLong(highestSeq + 1);
                    } catch (Exception e) {
                        logger.error("Error initializing sequence counter for actor {}", actorId, e);
                        return new AtomicLong(0);
                    }
                });
                
                // Generate next sequence number
                long seqNum = counter.getAndIncrement();
                
                // Create journal entry
                JournalEntry<M> entry = new JournalEntry<>(seqNum, actorId, message, Instant.now());
                
                // Add to pending batch
                synchronized (this) {
                    pendingBatches.computeIfAbsent(actorId, k -> new ArrayList<>()).add(entry);
                    
                    // Flush if batch is full
                    List<JournalEntry<M>> batch = pendingBatches.get(actorId);
                    if (batch.size() >= messagesPerFile) {
                        flushBatch(actorId, batch);
                        batch.clear();
                    }
                }
                
                return seqNum;
            } catch (Exception e) {
                logger.error("Error appending message for actor {}", actorId, e);
                throw new RuntimeException("Failed to append message", e);
            }
        });
    }
    
    @Override
    public CompletableFuture<List<JournalEntry<M>>> readFrom(String actorId, long fromSequenceNumber) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<JournalEntry<M>> entries = new ArrayList<>();
                
                // Read from batch files
                Path actorJournalDir = getActorJournalDir(actorId);
                if (Files.exists(actorJournalDir)) {
                    try (Stream<Path> files = Files.list(actorJournalDir)
                            .filter(path -> path.toString().endsWith(".batch"))
                            .sorted()) {
                        
                        for (Path batchFile : files.toList()) {
                            List<JournalEntry<M>> batchEntries = readBatchFile(batchFile);
                            for (JournalEntry<M> entry : batchEntries) {
                                if (entry.getSequenceNumber() >= fromSequenceNumber) {
                                    entries.add(entry);
                                }
                            }
                        }
                    }
                }
                
                // Also check pending batch
                synchronized (this) {
                    List<JournalEntry<M>> pending = pendingBatches.get(actorId);
                    if (pending != null) {
                        for (JournalEntry<M> entry : pending) {
                            if (entry.getSequenceNumber() >= fromSequenceNumber) {
                                entries.add(entry);
                            }
                        }
                    }
                }
                
                // Sort by sequence number
                entries.sort(Comparator.comparingLong(JournalEntry::getSequenceNumber));
                return entries;
                
            } catch (Exception e) {
                logger.error("Error reading messages for actor {} from sequence {}", actorId, fromSequenceNumber, e);
                return Collections.emptyList();
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> truncateBefore(String actorId, long upToSequenceNumber) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path actorJournalDir = getActorJournalDir(actorId);
                if (Files.exists(actorJournalDir)) {
                    try (Stream<Path> files = Files.list(actorJournalDir)
                            .filter(path -> path.toString().endsWith(".batch"))) {
                        
                        for (Path batchFile : files.toList()) {
                            List<JournalEntry<M>> entries = readBatchFile(batchFile);
                            boolean shouldKeep = entries.stream()
                                .anyMatch(entry -> entry.getSequenceNumber() >= upToSequenceNumber);
                            
                            if (!shouldKeep) {
                                Files.deleteIfExists(batchFile);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                logger.error("Error truncating messages for actor {} up to sequence {}", actorId, upToSequenceNumber, e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Long> getHighestSequenceNumber(String actorId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return getHighestSequenceNumberSync(actorId);
            } catch (IOException e) {
                logger.error("Error getting highest sequence number for actor {}", actorId, e);
                return -1L;
            }
        });
    }
    
    private void flushBatch(String actorId, List<JournalEntry<M>> batch) {
        if (batch.isEmpty()) return;
        
        try {
            Path actorJournalDir = getActorJournalDir(actorId);
            Files.createDirectories(actorJournalDir);
            
            // Create batch file with timestamp
            String batchFileName = String.format("%020d_%d.batch", 
                batch.get(0).getSequenceNumber(), 
                System.currentTimeMillis());
            Path batchFile = actorJournalDir.resolve(batchFileName);
            
            // Write batch to file
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(batchFile.toFile()))) {
                oos.writeInt(batch.size());
                for (JournalEntry<M> entry : batch) {
                    oos.writeObject(entry);
                }
            }
            
            logger.debug("Flushed batch of {} messages for actor {} to file {}", 
                batch.size(), actorId, batchFile.getFileName());
                
        } catch (IOException e) {
            logger.error("Error flushing batch for actor {}", actorId, e);
        }
    }
    
    private void flushAllBatches() {
        synchronized (this) {
            for (Map.Entry<String, List<JournalEntry<M>>> entry : pendingBatches.entrySet()) {
                String actorId = entry.getKey();
                List<JournalEntry<M>> batch = entry.getValue();
                
                if (!batch.isEmpty()) {
                    flushBatch(actorId, new ArrayList<>(batch));
                    batch.clear();
                }
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    private List<JournalEntry<M>> readBatchFile(Path batchFile) {
        List<JournalEntry<M>> entries = new ArrayList<>();
        
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(batchFile.toFile()))) {
            int count = ois.readInt();
            for (int i = 0; i < count; i++) {
                JournalEntry<M> entry = (JournalEntry<M>) ois.readObject();
                entries.add(entry);
            }
        } catch (IOException | ClassNotFoundException e) {
            logger.error("Error reading batch file: {}", batchFile, e);
        }
        
        return entries;
    }
    
    private Path getActorJournalDir(String actorId) {
        return journalDir.resolve(actorId);
    }
    
    private long getHighestSequenceNumberSync(String actorId) throws IOException {
        long highestSeq = -1;
        
        Path actorJournalDir = getActorJournalDir(actorId);
        if (Files.exists(actorJournalDir)) {
            try (Stream<Path> files = Files.list(actorJournalDir)
                    .filter(path -> path.toString().endsWith(".batch"))) {
                
                for (Path batchFile : files.toList()) {
                    List<JournalEntry<M>> entries = readBatchFile(batchFile);
                    for (JournalEntry<M> entry : entries) {
                        if (entry.getSequenceNumber() > highestSeq) {
                            highestSeq = entry.getSequenceNumber();
                        }
                    }
                }
            }
        }
        
        return highestSeq;
    }
    
    @Override
    public void close() {
        try {
            // Flush any pending batches
            flushAllBatches();
            batchExecutor.shutdown();
            batchExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while closing journal", e);
        }
    }
}
