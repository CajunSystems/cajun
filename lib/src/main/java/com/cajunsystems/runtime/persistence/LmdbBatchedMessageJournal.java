package com.cajunsystems.runtime.persistence;

import com.cajunsystems.persistence.BatchedMessageJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * LMDB-based implementation of the BatchedMessageJournal interface.
 * Extends LmdbMessageJournal with batching capabilities for improved performance.
 *
 * @param <M> The type of the message
 */
public class LmdbBatchedMessageJournal<M> extends LmdbMessageJournal<M> implements BatchedMessageJournal<M> {
    private static final Logger logger = LoggerFactory.getLogger(LmdbBatchedMessageJournal.class);

    // Default batch settings
    private static final int DEFAULT_MAX_BATCH_SIZE = 100;
    private static final long DEFAULT_MAX_BATCH_DELAY_MS = 100; // 100ms

    // Batch configuration
    private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
    private long maxBatchDelayMs = DEFAULT_MAX_BATCH_DELAY_MS;

    // Batching state
    private final Map<String, List<MessageBatchEntry<M>>> pendingBatches = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    /**
     * Creates a new LmdbBatchedMessageJournal with the specified directory.
     *
     * @param journalDir The directory to store LMDB database files
     * @param mapSize The memory map size for LMDB
     * @param maxDbs The maximum number of databases
     */
    public LmdbBatchedMessageJournal(java.nio.file.Path journalDir, long mapSize, int maxDbs) {
        super(journalDir, mapSize, maxDbs);
        // Start the background flush task
        scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "lmdb-journal-flush-scheduler");
            t.setDaemon(true); // Make thread daemon so it doesn't prevent JVM shutdown
            return t;
        });
        scheduler.scheduleAtFixedRate(this::flushAllBatches,
                maxBatchDelayMs, maxBatchDelayMs, TimeUnit.MILLISECONDS);
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
                logger.warn("Interrupted while awaiting scheduler termination for LMDB journal flush.", e);
                this.scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Create and start the new scheduler
        ScheduledExecutorService newScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "lmdb-journal-flush-scheduler-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        newScheduler.scheduleAtFixedRate(this::flushAllBatches,
                this.maxBatchDelayMs, this.maxBatchDelayMs, TimeUnit.MILLISECONDS);
        this.scheduler = newScheduler;
        logger.info("Rescheduled LMDB journal flush task with delay: {} ms", this.maxBatchDelayMs);
    }

    @Override
    public CompletableFuture<Long> append(String actorId, M message) {
        CompletableFuture<Long> future = new CompletableFuture<>();

        // Get or create batch for this actor
        List<MessageBatchEntry<M>> batch = pendingBatches.computeIfAbsent(actorId, k -> new ArrayList<>());

        // Create a batch entry with a future for the sequence number
        MessageBatchEntry<M> entry = new MessageBatchEntry<>(message, future);
        synchronized (batch) {
            batch.add(entry);

            // If batch is full, flush it
            if (batch.size() >= maxBatchSize) {
                flushBatch(actorId, new ArrayList<>(batch));
                batch.clear();
            }
        }

        return future;
    }

    @Override
    public CompletableFuture<List<Long>> appendBatch(String actorId, List<M> messages) {
        if (messages == null || messages.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        List<CompletableFuture<Long>> futures = new ArrayList<>(messages.size());

        // Get or create batch for this actor
        List<MessageBatchEntry<M>> batch = pendingBatches.computeIfAbsent(actorId, k -> new ArrayList<>());

        synchronized (batch) {
            // Add all messages to the batch
            for (M message : messages) {
                CompletableFuture<Long> future = new CompletableFuture<>();
                futures.add(future);

                MessageBatchEntry<M> entry = new MessageBatchEntry<>(message, future);
                batch.add(entry);
            }

            // If batch is full or exceeds max size, flush it
            if (batch.size() >= maxBatchSize) {
                flushBatch(actorId, new ArrayList<>(batch));
                batch.clear();
            }
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
            List<MessageBatchEntry<M>> batch = pendingBatches.get(actorId);
            if (batch != null) {
                List<MessageBatchEntry<M>> batchCopy;
                synchronized (batch) {
                    batchCopy = new ArrayList<>(batch);
                    batch.clear();
                }
                
                if (!batchCopy.isEmpty()) {
                    CompletableFuture<Void> future = flushBatch(actorId, batchCopy);
                    flushFutures.add(future);
                }
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
            logger.error("Error flushing LMDB batches", e);
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
                // Process each message in the batch
                for (MessageBatchEntry<M> entry : batch) {
                    try {
                        // Use the parent append method to get sequence numbers
                        CompletableFuture<Long> appendFuture = super.append(actorId, entry.getMessage());
                        Long seqNum = appendFuture.join();

                        // Complete the future with the sequence number
                        entry.getFuture().complete(seqNum);

                        logger.debug("Appended batched message for actor {} with sequence number {}", actorId, seqNum);
                    } catch (Exception e) {
                        logger.error("Failed to append batched message for actor {}", actorId, e);
                        entry.getFuture().completeExceptionally(
                                new RuntimeException("Failed to append batched message for actor " + actorId, e));
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

    @Override
    public void close() {
        logger.info("Closing LmdbBatchedMessageJournal for directory: {}. Flushing pending batches.", getJournalDir());
        // Ensure all pending batches are flushed before closing
        flushAllBatches();

        // Shutdown the scheduler
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.error("Scheduler did not terminate for LMDB journal: {}", getJournalDir());
                    }
                }
            } catch (InterruptedException e) {
                logger.warn("Interrupted while awaiting scheduler termination for LMDB journal: {}. Forcing shutdown.", getJournalDir(), e);
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info("LMDB flush scheduler for journal {} has been shut down.", getJournalDir());

        super.close(); // Call superclass close to close LMDB resources
        logger.info("LmdbBatchedMessageJournal for directory: {} closed.", getJournalDir());
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
