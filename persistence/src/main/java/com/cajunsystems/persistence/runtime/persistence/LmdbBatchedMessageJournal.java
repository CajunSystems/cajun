package com.cajunsystems.persistence.runtime.persistence;

import com.cajunsystems.persistence.BatchedMessageJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * LMDB-backed batched message journal implementation.
 * 
 * Features:
 * - Batches multiple messages into a single LMDB transaction
 * - Configurable batch size and delay
 * - Automatic flushing on batch size or time threshold
 * - Thread-safe batching with minimal contention
 * 
 * @param <M> The type of messages
 */
public class LmdbBatchedMessageJournal<M> extends LmdbMessageJournal<M> implements BatchedMessageJournal<M> {
    
    private static final Logger logger = LoggerFactory.getLogger(LmdbBatchedMessageJournal.class);
    
    private static final int DEFAULT_MAX_BATCH_SIZE = 100;
    private static final long DEFAULT_MAX_BATCH_DELAY_MS = 100;
    
    private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
    private long maxBatchDelayMs = DEFAULT_MAX_BATCH_DELAY_MS;
    
    private final List<BatchEntry<M>> pendingBatch = new ArrayList<>();
    private final ReentrantLock batchLock = new ReentrantLock();
    private final AtomicLong lastFlushTime = new AtomicLong(System.currentTimeMillis());
    private ScheduledExecutorService scheduler;
    
    /**
     * Creates a new LMDB batched message journal.
     *
     * @param actorId The ID of the actor this journal is for
     * @param envManager The environment manager for LMDB operations
     */
    public LmdbBatchedMessageJournal(String actorId, LmdbEnvironmentManager envManager) {
        super(actorId, envManager);
        
        // Start background flush scheduler
        scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "lmdb-batch-flush-" + actorId);
            t.setDaemon(true);
            return t;
        });
        
        scheduler.scheduleAtFixedRate(
            this::flushIfNeeded,
            maxBatchDelayMs,
            maxBatchDelayMs,
            TimeUnit.MILLISECONDS
        );
        
        logger.info("LMDB Batched Message Journal initialized for actor: {}", actorId);
    }
    
    @Override
    public CompletableFuture<Long> append(String actorId, M message) {
        CompletableFuture<Long> future = new CompletableFuture<>();
        
        batchLock.lock();
        try {
            // Add to pending batch
            BatchEntry<M> entry = new BatchEntry<>(actorId, message, future);
            pendingBatch.add(entry);
            
            // Flush if batch is full
            if (pendingBatch.size() >= maxBatchSize) {
                flushBatch();
            }
        } finally {
            batchLock.unlock();
        }
        
        return future;
    }
    
    @Override
    public CompletableFuture<List<Long>> appendBatch(String actorId, List<M> messages) {
        return CompletableFuture.supplyAsync(() -> {
            List<Long> sequences = new ArrayList<>();

            try {
                // Write all messages in a single LMDB transaction using appendInTransaction
                getEnvManager().writeTransaction(txn -> {
                    for (M message : messages) {
                        try {
                            long sequence = appendInTransaction(txn, actorId, message);
                            sequences.add(sequence);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to append message in batch", e);
                        }
                    }
                    return null;
                });

                logger.debug("Appended batch of {} messages for actor {}", messages.size(), actorId);
                return sequences;

            } catch (Exception e) {
                logger.error("Failed to append batch for actor: {}", actorId, e);
                throw new RuntimeException("Failed to append batch", e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> flush() {
        return CompletableFuture.runAsync(() -> {
            batchLock.lock();
            try {
                if (!pendingBatch.isEmpty()) {
                    flushBatch();
                }
            } finally {
                batchLock.unlock();
            }
        });
    }
    
    @Override
    public void setMaxBatchSize(int maxBatchSize) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("Max batch size must be positive");
        }
        this.maxBatchSize = maxBatchSize;
        logger.debug("Set max batch size to {}", maxBatchSize);
    }
    
    @Override
    public void setMaxBatchDelayMs(long maxBatchDelayMs) {
        if (maxBatchDelayMs <= 0) {
            throw new IllegalArgumentException("Max batch delay must be positive");
        }
        
        this.maxBatchDelayMs = maxBatchDelayMs;
        
        // Restart scheduler with new delay
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
        }
        
        scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "lmdb-batch-flush-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        
        scheduler.scheduleAtFixedRate(
            this::flushIfNeeded,
            maxBatchDelayMs,
            maxBatchDelayMs,
            TimeUnit.MILLISECONDS
        );
        
        logger.debug("Set max batch delay to {} ms", maxBatchDelayMs);
    }
    
    /**
     * Gets the current number of messages in the pending batch.
     * 
     * @return The number of pending messages
     */
    public int getPendingBatchSize() {
        batchLock.lock();
        try {
            return pendingBatch.size();
        } finally {
            batchLock.unlock();
        }
    }
    
    @Override
    public void close() {
        // Flush any pending messages
        batchLock.lock();
        try {
            if (!pendingBatch.isEmpty()) {
                flushBatch();
            }
        } finally {
            batchLock.unlock();
        }
        
        // Shutdown scheduler
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
        }
        
        super.close();
    }
    
    /**
     * Flushes the batch if the time threshold has been exceeded.
     */
    private void flushIfNeeded() {
        long now = System.currentTimeMillis();
        long timeSinceLastFlush = now - lastFlushTime.get();
        
        if (timeSinceLastFlush >= maxBatchDelayMs) {
            batchLock.lock();
            try {
                if (!pendingBatch.isEmpty()) {
                    flushBatch();
                }
            } finally {
                batchLock.unlock();
            }
        }
    }
    
    /**
     * Flushes the pending batch to LMDB.
     * Must be called with batchLock held.
     */
    private void flushBatch() {
        if (pendingBatch.isEmpty()) {
            return;
        }
        
        List<BatchEntry<M>> toFlush = new ArrayList<>(pendingBatch);
        pendingBatch.clear();
        lastFlushTime.set(System.currentTimeMillis());
        
        try {
            // Write all entries in a single transaction using appendInTransaction
            getEnvManager().writeTransaction(txn -> {
                for (BatchEntry<M> entry : toFlush) {
                    try {
                        long sequence = appendInTransaction(txn, entry.actorId, entry.message);
                        entry.future.complete(sequence);
                    } catch (Exception e) {
                        entry.future.completeExceptionally(e);
                    }
                }
                return null;
            });
            
            logger.debug("Flushed batch of {} messages", toFlush.size());
            
        } catch (Exception e) {
            logger.error("Failed to flush batch", e);
            // Complete all futures exceptionally
            for (BatchEntry<M> entry : toFlush) {
                entry.future.completeExceptionally(e);
            }
        }
    }
    
    /**
     * Gets the environment manager for subclass access.
     */
    private LmdbEnvironmentManager getEnvManager() {
        // Access through reflection or make envManager protected in parent
        try {
            java.lang.reflect.Field field = LmdbMessageJournal.class.getDeclaredField("envManager");
            field.setAccessible(true);
            return (LmdbEnvironmentManager) field.get(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to access envManager", e);
        }
    }
    
    /**
     * Internal class to hold batch entry data.
     */
    private static class BatchEntry<M> {
        final String actorId;
        final M message;
        final CompletableFuture<Long> future;
        
        BatchEntry(String actorId, M message, CompletableFuture<Long> future) {
            this.actorId = actorId;
            this.message = message;
            this.future = future;
        }
    }
}
