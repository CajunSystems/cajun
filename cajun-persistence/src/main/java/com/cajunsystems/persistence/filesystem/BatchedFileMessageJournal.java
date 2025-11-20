package com.cajunsystems.persistence.filesystem;

import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.TruncationCapableJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A file-based implementation of the BatchedMessageJournal interface.
 * Extends FileMessageJournal with batching capabilities for improved performance.
 *
 * @param <M> The type of the message
 */
public class BatchedFileMessageJournal<M> extends FileMessageJournal<M> implements BatchedMessageJournal<M>, TruncationCapableJournal {
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
    private ScheduledExecutorService scheduler;

    /**
     * Creates a new BatchedFileMessageJournal with the specified directory.
     *
     * @param journalDir The directory to store journal files in
     */
    public BatchedFileMessageJournal(Path journalDir) {
        super(journalDir);
        // Start the background flush task
        scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "journal-flush-scheduler");
            t.setDaemon(true); // Make thread daemon so it doesn't prevent JVM shutdown
            return t;
        });
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

        // Shutdown the old scheduler and create a new one
        if (this.scheduler != null && !this.scheduler.isShutdown()) {
            this.scheduler.shutdown();
            try {
                if (!this.scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    this.scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.warn("Interrupted while awaiting scheduler termination for journal flush.", e);
                this.scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Create and start the new scheduler
        ScheduledExecutorService newScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "journal-flush-scheduler-" + System.nanoTime()); // Ensure unique name if multiple journals exist
            t.setDaemon(true);
            return t;
        });
        newScheduler.scheduleAtFixedRate(this::flushAllBatches,
                this.maxBatchDelayMs, this.maxBatchDelayMs, TimeUnit.MILLISECONDS);
        this.scheduler = newScheduler; // Assign the new scheduler
        logger.info("Rescheduled journal flush task with delay: {} ms", this.maxBatchDelayMs);
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
     * @param batch   The batch of messages to flush
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
    public CompletableFuture<List<JournalEntry<M>>> readFrom(String actorId, long fromSequenceNumber) {
        ReadWriteLock lock = actorLocks.computeIfAbsent(actorId, k -> new ReentrantReadWriteLock());
        lock.readLock().lock();
        try {
            // Ensure any pending writes for this actor are flushed before reading to get the most up-to-date view.
            // This requires careful consideration: flushing here might be unexpected for a read operation.
            // A simpler approach is to rely on the read lock to ensure consistency with ongoing writes.
            // If strict "read-your-writes-after-batch-submission" is needed, flushing might be an option,
            // but it changes the read operation's side effects.
            // For now, let's proceed without an explicit flush within readFrom, relying on the lock.
            return super.readFrom(actorId, fromSequenceNumber);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public CompletableFuture<Void> truncateBefore(String actorId, long upToSequenceNumber) {
        ReadWriteLock lock = actorLocks.computeIfAbsent(actorId, k -> new ReentrantReadWriteLock());
        lock.writeLock().lock(); // Use write lock for truncation as it modifies the journal state
        try {
            // It's crucial that truncation is coordinated with flushing.
            // Flushing any pending batches for this actor before truncation ensures we don't truncate what's about to be written.
            List<MessageBatchEntry<M>> batch = pendingBatches.get(actorId);
            if (batch != null && !batch.isEmpty()) {
                // This flushBatch is called under the actor's write lock already held.
                flushBatch(actorId, new ArrayList<>(batch)).join(); // Make a copy to avoid CME if flushBatch modifies it
                pendingBatches.put(actorId, new ArrayList<>()); // Clear the flushed batch
            }
            return super.truncateBefore(actorId, upToSequenceNumber);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public CompletableFuture<Long> getHighestSequenceNumber(String actorId) {
        ReadWriteLock lock = actorLocks.computeIfAbsent(actorId, k -> new ReentrantReadWriteLock());
        lock.readLock().lock();
        try {
            // Similar to readFrom, consider if flushing is needed. For now, rely on lock.
            return super.getHighestSequenceNumber(actorId);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        logger.info("Closing BatchedFileMessageJournal for directory: {}. Flushing pending batches.", getJournalDir());
        // Ensure all pending batches are flushed before closing
        flushAllBatches(); // Consider doing this with actor locks held appropriately or make flushAllBatches robust

        // Shutdown the scheduler
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.error("Scheduler did not terminate for journal: {}", getJournalDir());
                    }
                }
            } catch (InterruptedException e) {
                logger.warn("Interrupted while awaiting scheduler termination for journal: {}. Forcing shutdown.", getJournalDir(), e);
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info("Flush scheduler for journal {} has been shut down.", getJournalDir());

        super.close(); // Call superclass close to close file resources
        logger.info("BatchedFileMessageJournal for directory: {} closed.", getJournalDir());
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
