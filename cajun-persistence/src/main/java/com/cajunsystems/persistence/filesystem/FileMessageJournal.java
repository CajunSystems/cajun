package com.cajunsystems.persistence.filesystem;

import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.MessageJournal;
import com.cajunsystems.persistence.TruncationCapableJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * A file-based implementation of the MessageJournal interface.
 * Stores messages in append-only files, one file per actor.
 *
 * @param <M> The type of the message
 */
public class FileMessageJournal<M> implements MessageJournal<M>, TruncationCapableJournal {
    private static final Logger logger = LoggerFactory.getLogger(FileMessageJournal.class);
    
    private final Path journalDir;
    private final Map<String, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();
    
    /**
     * Creates a new FileMessageJournal with the specified directory.
     *
     * @param journalDir The directory to store journal files in
     */
    public FileMessageJournal(Path journalDir) {
        this.journalDir = journalDir;
        try {
            Files.createDirectories(journalDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create journal directory: " + journalDir, e);
        }
    }
    
    /**
     * Creates a new FileMessageJournal with the specified directory path.
     *
     * @param journalDirPath The path to the directory to store journal files in
     */
    public FileMessageJournal(String journalDirPath) {
        this(Paths.get(journalDirPath));
    }

    /**
     * Returns the base directory where journal files are stored.
     *
     * @return The path to the journal directory.
     */
    protected Path getJournalDir() {
        return journalDir;
    }

    @Override
    public CompletableFuture<Long> append(String actorId, M message) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path actorJournalDir = getActorJournalDir(actorId);
                Files.createDirectories(actorJournalDir);
                
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
                
                // Write to file
                Path entryFile = actorJournalDir.resolve(String.format("%020d.journal", seqNum));
                try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(entryFile.toFile()))) {
                    oos.writeObject(entry);
                }
                
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
                Path actorJournalDir = getActorJournalDir(actorId);
                if (!Files.exists(actorJournalDir)) {
                    return Collections.emptyList();
                }
                
                // Find all journal files for this actor with sequence number >= fromSequenceNumber
                List<Path> entryFiles = Files.list(actorJournalDir)
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".journal"))
                        .filter(p -> {
                            String fileName = p.getFileName().toString();
                            try {
                                long seqNum = Long.parseLong(fileName.substring(0, fileName.indexOf(".journal")));
                                return seqNum >= fromSequenceNumber;
                            } catch (NumberFormatException e) {
                                return false;
                            }
                        })
                        .sorted()
                        .collect(Collectors.toList());
                
                // Read entries
                List<JournalEntry<M>> entries = new ArrayList<>();
                for (Path entryFile : entryFiles) {
                    try (ObjectInputStream is = new ObjectInputStream(new FileInputStream(entryFile.toFile()))) {
                        @SuppressWarnings("unchecked")
                        JournalEntry<M> entry = (JournalEntry<M>) is.readObject();
                        entries.add(entry);
                    } catch (ClassNotFoundException e) {
                        logger.error("Failed to deserialize journal entry from file {}", entryFile, e);
                        throw new RuntimeException("Failed to deserialize journal entry", e);
                    }
                }
                
                logger.debug("Read {} messages for actor {} from sequence number {}", 
                        entries.size(), actorId, fromSequenceNumber);
                return entries;
            } catch (IOException e) {
                logger.error("Failed to read messages for actor {}", actorId, e);
                throw new RuntimeException("Failed to read messages for actor " + actorId, e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> truncateBefore(String actorId, long upToSequenceNumber) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path actorJournalDir = getActorJournalDir(actorId);
                if (!Files.exists(actorJournalDir)) {
                    return;
                }
                
                // Find all journal files for this actor with sequence number < upToSequenceNumber
                List<Path> filesToDelete = Files.list(actorJournalDir)
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".journal"))
                        .filter(p -> {
                            String fileName = p.getFileName().toString();
                            try {
                                long seqNum = Long.parseLong(fileName.substring(0, fileName.indexOf(".journal")));
                                return seqNum < upToSequenceNumber;
                            } catch (NumberFormatException e) {
                                return false;
                            }
                        })
                        .collect(Collectors.toList());
                
                // Delete files
                for (Path file : filesToDelete) {
                    Files.delete(file);
                }
                
                logger.debug("Truncated {} messages for actor {} before sequence number {}", 
                        filesToDelete.size(), actorId, upToSequenceNumber);
            } catch (IOException e) {
                logger.error("Failed to truncate messages for actor {}", actorId, e);
                throw new RuntimeException("Failed to truncate messages for actor " + actorId, e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Long> getHighestSequenceNumber(String actorId) {
        return CompletableFuture.supplyAsync(() -> getHighestSequenceNumberSync(actorId));
    }
    
    protected long getHighestSequenceNumberSync(String actorId) {
        try {
            Path actorJournalDir = getActorJournalDir(actorId);
            if (!Files.exists(actorJournalDir)) {
                return -1;
            }
            
            // Find the highest sequence number
            Optional<Long> highestSeq = Files.list(actorJournalDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".journal"))
                    .map(p -> {
                        String fileName = p.getFileName().toString();
                        try {
                            return Long.parseLong(fileName.substring(0, fileName.indexOf(".journal")));
                        } catch (NumberFormatException e) {
                            return -1L;
                        }
                    })
                    .filter(seq -> seq >= 0)
                    .max(Long::compare);
            
            return highestSeq.orElse(-1L);
        } catch (IOException e) {
            logger.error("Failed to get highest sequence number for actor {}", actorId, e);
            return -1;
        }
    }
    
    protected Path getActorJournalDir(String actorId) {
        // Sanitize actor ID to be a valid directory name
        String sanitizedId = actorId.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return journalDir.resolve(sanitizedId);
    }
    
    /**
     * Gets the sequence counters map.
     * This is used by subclasses to access the sequence counters.
     *
     * @return The sequence counters map
     */
    protected Map<String, AtomicLong> getSequenceCounters() {
        return sequenceCounters;
    }
    
    @Override
    public void close() {
        // Nothing to close for file-based journal
    }
}
