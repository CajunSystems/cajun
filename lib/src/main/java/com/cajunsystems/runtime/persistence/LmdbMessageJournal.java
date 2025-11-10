package com.cajunsystems.runtime.persistence;

import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.MessageJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LMDB-based implementation of the MessageJournal interface.
 * Provides high-performance persistence using memory-mapped I/O.
 *
 * @param <M> The type of the message
 */
public class LmdbMessageJournal<M> implements MessageJournal<M> {
    private static final Logger logger = LoggerFactory.getLogger(LmdbMessageJournal.class);
    
    private final Path journalDir;
    private final LmdbEnvironmentManager envManager;
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    
    // Database names in LMDB
    private static final String MESSAGE_DB = "messages";
    private static final String METADATA_DB = "metadata";
    
    /**
     * Creates a new LmdbMessageJournal with the specified directory.
     *
     * @param journalDir The directory to store LMDB database files
     * @param mapSize The memory map size for LMDB
     * @param maxDbs The maximum number of databases
     */
    public LmdbMessageJournal(Path journalDir, long mapSize, int maxDbs) {
        this.journalDir = journalDir;
        this.envManager = new LmdbEnvironmentManager(journalDir, mapSize, maxDbs);
        
        try {
            Files.createDirectories(journalDir);
            envManager.initialize();
            
            // Initialize sequence counter from highest existing sequence
            long highestSeq = getHighestSequenceNumberSync();
            sequenceCounter.set(highestSeq + 1);
            
            logger.debug("LmdbMessageJournal initialized for directory: {}, highest sequence: {}", 
                        journalDir, highestSeq);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize LMDB message journal: " + journalDir, e);
        }
    }
    
    @Override
    public CompletableFuture<Long> append(String actorId, M message) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                long seqNum = sequenceCounter.getAndIncrement();
                
                JournalEntry<M> entry = new JournalEntry<>(seqNum, actorId, message, Instant.now());
                
                // Store in LMDB
                envManager.writeEntry(MESSAGE_DB, actorId + ":" + seqNum, entry);
                
                // Update metadata
                envManager.writeEntry(METADATA_DB, actorId + ":highest_seq", seqNum);
                
                logger.debug("Appended message for actor {} with sequence number {}", actorId, seqNum);
                return seqNum;
            } catch (Exception e) {
                logger.error("Failed to append message for actor {}", actorId, e);
                throw new RuntimeException("Failed to append message for actor " + actorId, e);
            }
        });
    }
    
    @Override
    public CompletableFuture<List<JournalEntry<M>>> readFrom(String actorId, long fromSequenceNumber) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<JournalEntry<M>> entries = new ArrayList<>();
                
                // Read entries from LMDB
                String startKey = actorId + ":" + fromSequenceNumber;
                String endKey = actorId + ":\uFFFF"; // High Unicode value for range scan
                
                List<JournalEntry<M>> rawEntries = envManager.readRange(MESSAGE_DB, startKey, endKey);
                
                for (JournalEntry<M> entry : rawEntries) {
                    if (entry.getSequenceNumber() >= fromSequenceNumber) {
                        entries.add(entry);
                    }
                }
                
                // Sort by sequence number (LMDB maintains order, but ensure consistency)
                entries.sort((a, b) -> Long.compare(a.getSequenceNumber(), b.getSequenceNumber()));
                
                logger.debug("Read {} messages for actor {} from sequence number {}", 
                            entries.size(), actorId, fromSequenceNumber);
                return entries;
            } catch (Exception e) {
                logger.error("Failed to read messages for actor {}", actorId, e);
                throw new RuntimeException("Failed to read messages for actor " + actorId, e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> truncateBefore(String actorId, long upToSequenceNumber) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Delete entries before the specified sequence number
                String startKey = actorId + ":0";
                String endKey = actorId + ":" + upToSequenceNumber;
                
                envManager.deleteRange(MESSAGE_DB, startKey, endKey);
                
                logger.debug("Truncated messages for actor {} before sequence number {}", 
                            actorId, upToSequenceNumber);
            } catch (Exception e) {
                logger.error("Failed to truncate messages for actor {}", actorId, e);
                throw new RuntimeException("Failed to truncate messages for actor " + actorId, e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Long> getHighestSequenceNumber(String actorId) {
        return CompletableFuture.supplyAsync(() -> getHighestSequenceNumberSync(actorId));
    }
    
    /**
     * Gets the highest sequence number for an actor synchronously.
     *
     * @param actorId The actor ID
     * @return The highest sequence number, or -1 if no messages exist
     */
    protected long getHighestSequenceNumberSync(String actorId) {
        try {
            Long highestSeq = envManager.readValue(METADATA_DB, actorId + ":highest_seq", Long.class);
            return highestSeq != null ? highestSeq : -1;
        } catch (Exception e) {
            logger.error("Failed to get highest sequence number for actor {}", actorId, e);
            return -1;
        }
    }
    
    /**
     * Gets the highest sequence number across all actors synchronously.
     *
     * @return The highest sequence number, or -1 if no messages exist
     */
    protected long getHighestSequenceNumberSync() {
        try {
            Long highestSeq = envManager.readValue(METADATA_DB, "global:highest_seq", Long.class);
            return highestSeq != null ? highestSeq : -1;
        } catch (Exception e) {
            logger.error("Failed to get global highest sequence number", e);
            return -1;
        }
    }
    
    @Override
    public void close() {
        try {
            if (envManager != null) {
                envManager.close();
            }
            logger.debug("LmdbMessageJournal closed for directory: {}", journalDir);
        } catch (Exception e) {
            logger.error("Error closing LMDB message journal", e);
        }
    }
    
    /**
     * Gets the journal directory.
     *
     * @return The journal directory path
     */
    protected Path getJournalDir() {
        return journalDir;
    }
}
