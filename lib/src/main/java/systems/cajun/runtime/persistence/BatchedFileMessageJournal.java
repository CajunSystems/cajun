package systems.cajun.runtime.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.cajun.persistence.BatchedMessageJournal;
import systems.cajun.persistence.JournalEntry;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A file-based implementation of the BatchedMessageJournal interface.
 * Extends FileMessageJournal with batching capabilities for improved performance.
 *
 * @param <M> The type of the message
 */
public class BatchedFileMessageJournal<M> extends FileMessageJournal<M> implements BatchedMessageJournal<M> {
    private static final Logger logger = LoggerFactory.getLogger(BatchedFileMessageJournal.class);
    
    // Default batch settings
    private static final int DEFAULT_MAX_BATCH_SIZE = 100;
    private static final long DEFAULT_MAX_BATCH_DELAY_MS = 100; // 100ms
    
    // Batch configuration
    private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
    private long maxBatchDelayMs = DEFAULT_MAX_BATCH_DELAY_MS;
    
    // Batching state
    private final Map<String, List<MessageBatchEntry<M>>> pendingBatches = new ConcurrentHashMap<>();
    private final Map<String, ReadWriteLock> actorLocks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "journal-flush-scheduler");
        t.setDaemon(true); // Make thread daemon so it doesn't prevent JVM shutdown
        return t;
    });
    
    /**
     * Creates a new BatchedFileMessageJournal with the specified directory.
     *
     * @param journalDir The directory to store journal files in
     */
    public BatchedFileMessageJournal(Path journalDir) {
        super(journalDir);
        // Start the background flush task
        scheduler.scheduleAtFixedRate(this::flushAllBatches, 
                maxBatchDelayMs, maxBatchDelayMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Creates a new BatchedFileMessageJournal with the specified directory path.
     *
     * @param journalDirPath The path to the directory to store journal files in
     */
    public BatchedFileMessageJournal(String journalDirPath) {
        this(Paths.get(journalDirPath));
    }
    
    @Override
    public void setMaxBatchSize(int maxBatchSize) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("Max batch size must be positive");
        }
        this.maxBatchSize = maxBatchSize;
    }
    
    @Override
    public void setMaxBatchDelayMs(long maxBatchDelayMs) {
        if (maxBatchDelayMs <= 0) {
            throw new IllegalArgumentException("Max batch delay must be positive");
        }
        this.maxBatchDelayMs = maxBatchDelayMs;
        
        // Reschedule the background flush task
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        ScheduledExecutorService newScheduler = Executors.newScheduledThreadPool(1);
        newScheduler.scheduleAtFixedRate(this::flushAllBatches, 
                maxBatchDelayMs, maxBatchDelayMs, TimeUnit.MILLISECONDS);
        // Replace the old scheduler
        this.scheduler.shutdownNow();
    }
    
    @Override
    public CompletableFuture<Long> append(String actorId, M message) {
        CompletableFuture<Long> future = new CompletableFuture<>();
        
        // Get or create lock for this actor
        ReadWriteLock lock = actorLocks.computeIfAbsent(actorId, k -> new ReentrantReadWriteLock());
        
        // Acquire write lock to modify the batch
        lock.writeLock().lock();
        try {
            // Get or create batch for this actor
            List<MessageBatchEntry<M>> batch = pendingBatches.computeIfAbsent(actorId, k -> new ArrayList<>());
            
            // Create a batch entry with a future for the sequence number
            MessageBatchEntry<M> entry = new MessageBatchEntry<>(message, future);
            batch.add(entry);
            
            // If batch is full, flush it
            if (batch.size() >= maxBatchSize) {
                flushBatch(actorId, batch);
                pendingBatches.put(actorId, new ArrayList<>());
            }
        } finally {
            lock.writeLock().unlock();
        }
        
        return future;
    }
    
    @Override
    public CompletableFuture<List<Long>> appendBatch(String actorId, List<M> messages) {
        if (messages == null || messages.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        
        List<CompletableFuture<Long>> futures = new ArrayList<>(messages.size());
        
        // Get or create lock for this actor
        ReadWriteLock lock = actorLocks.computeIfAbsent(actorId, k -> new ReentrantReadWriteLock());
        
        // Acquire write lock to modify the batch
        lock.writeLock().lock();
        try {
            // Get or create batch for this actor
            List<MessageBatchEntry<M>> batch = pendingBatches.computeIfAbsent(actorId, k -> new ArrayList<>());
            
            // Add all messages to the batch
            for (M message : messages) {
                CompletableFuture<Long> future = new CompletableFuture<>();
                futures.add(future);
                
                MessageBatchEntry<M> entry = new MessageBatchEntry<>(message, future);
                batch.add(entry);
            }
            
            // If batch is full or exceeds max size, flush it
            if (batch.size() >= maxBatchSize) {
                flushBatch(actorId, batch);
                pendingBatches.put(actorId, new ArrayList<>());
            }
        } finally {
            lock.writeLock().unlock();
        }
        
        // Return a future that completes when all individual futures complete
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<Long> sequenceNumbers = new ArrayList<>(futures.size());
                    for (CompletableFuture<Long> future : futures) {
                        sequenceNumbers.add(future.join());
                    }
                    return sequenceNumbers;
                });
    }
    
    @Override
    public CompletableFuture<Void> flush() {
        List<CompletableFuture<Void>> flushFutures = new ArrayList<>();
        
        // Make a copy of the actor IDs to avoid concurrent modification
        List<String> actorIds = new ArrayList<>(pendingBatches.keySet());
        
        for (String actorId : actorIds) {
            ReadWriteLock lock = actorLocks.get(actorId);
            if (lock == null) {
                continue;
            }
            
            lock.writeLock().lock();
            try {
                List<MessageBatchEntry<M>> batch = pendingBatches.get(actorId);
                if (batch != null && !batch.isEmpty()) {
                    CompletableFuture<Void> future = flushBatch(actorId, batch);
                    flushFutures.add(future);
                    pendingBatches.put(actorId, new ArrayList<>());
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        return CompletableFuture.allOf(flushFutures.toArray(new CompletableFuture[0]));
    }
    
    /**
     * Flushes all pending batches for all actors.
     * This is called periodically by the scheduler.
     */
    private void flushAllBatches() {
        try {
            flush().join();
        } catch (Exception e) {
            logger.error("Error flushing batches", e);
        }
    }
    
    /**
     * Flushes a batch of messages for a specific actor.
     *
     * @param actorId The actor ID
     * @param batch The batch of messages to flush
     * @return A CompletableFuture that completes when the flush is done
     */
    private CompletableFuture<Void> flushBatch(String actorId, List<MessageBatchEntry<M>> batch) {
        if (batch == null || batch.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.runAsync(() -> {
            try {
                Path actorJournalDir = getActorJournalDir(actorId);
                Files.createDirectories(actorJournalDir);
                
                // Get or initialize sequence counter for this actor
                AtomicLong counter = getSequenceCounter(actorId);
                
                // Process each message in the batch
                for (MessageBatchEntry<M> entry : batch) {
                    try {
                        // Generate next sequence number
                        long seqNum = counter.getAndIncrement();
                        
                        // Create journal entry
                        JournalEntry<M> journalEntry = new JournalEntry<>(seqNum, actorId, entry.getMessage(), Instant.now());
                        
                        // Write to file
                        Path entryFile = actorJournalDir.resolve(String.format("%020d.journal", seqNum));
                        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(entryFile.toFile()))) {
                            oos.writeObject(journalEntry);
                        }
                        
                        // Complete the future with the sequence number
                        entry.getFuture().complete(seqNum);
                        
                        logger.debug("Appended message for actor {} with sequence number {}", actorId, seqNum);
                    } catch (Exception e) {
                        logger.error("Failed to append message for actor {}", actorId, e);
                        entry.getFuture().completeExceptionally(
                                new RuntimeException("Failed to append message for actor " + actorId, e));
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to flush batch for actor {}", actorId, e);
                // Complete all futures with exception
                for (MessageBatchEntry<M> entry : batch) {
                    if (!entry.getFuture().isDone()) {
                        entry.getFuture().completeExceptionally(
                                new RuntimeException("Failed to flush batch for actor " + actorId, e));
                    }
                }
                throw new RuntimeException("Failed to flush batch for actor " + actorId, e);
            }
        });
    }
    
    /**
     * Gets the sequence counter for an actor, initializing it if necessary.
     *
     * @param actorId The actor ID
     * @return The sequence counter
     */
    private AtomicLong getSequenceCounter(String actorId) {
        return getSequenceCounters().computeIfAbsent(actorId, k -> {
            try {
                long highestSeq = getHighestSequenceNumberSync(actorId);
                return new AtomicLong(highestSeq + 1);
            } catch (Exception e) {
                logger.error("Error initializing sequence counter for actor {}", actorId, e);
                return new AtomicLong(0);
            }
        });
    }
    
    @Override
    public void close() {
        // Flush all pending batches
        try {
            flush().join();
        } catch (Exception e) {
            logger.error("Error flushing batches during close", e);
        }
        
        // Shutdown the scheduler
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        super.close();
    }
    
    /**
     * Helper class to store a message and its associated future in the batch.
     *
     * @param <M> The type of the message
     */
    private static class MessageBatchEntry<M> {
        private final M message;
        private final CompletableFuture<Long> future;
        
        public MessageBatchEntry(M message, CompletableFuture<Long> future) {
            this.message = message;
            this.future = future;
        }
        
        public M getMessage() {
            return message;
        }
        
        public CompletableFuture<Long> getFuture() {
            return future;
        }
    }
}
