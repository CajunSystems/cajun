package com.cajunsystems.persistence.runtime.persistence;

import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.MessageJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A file-based implementation of the MessageJournal interface.
 * Stores messages in append-only files, one file per actor.
 *
 * @param <M> The type of the message
 */
public class FileMessageJournal<M> implements MessageJournal<M> {
    private static final Logger logger = LoggerFactory.getLogger(FileMessageJournal.class);
    
    private final Path journalDir;
    private final Map<String, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();
    private final Map<String, FileLock> actorLocks = new ConcurrentHashMap<>();
    private final ExecutorService ioExecutor;
    private final boolean enableFsync;
    private volatile boolean closed = false;
    
    /**
     * Creates a new FileMessageJournal with the specified directory.
     *
     * @param journalDir The directory to store journal files in
     */
    public FileMessageJournal(Path journalDir) {
        this(journalDir, true);
    }
    
    /**
     * Creates a new FileMessageJournal with the specified directory and fsync option.
     *
     * @param journalDir The directory to store journal files in
     * @param enableFsync Whether to fsync after each write for durability
     */
    public FileMessageJournal(Path journalDir, boolean enableFsync) {
        this.journalDir = journalDir;
        this.enableFsync = enableFsync;
        
        // Create dedicated IO executor to avoid blocking ForkJoin pool
        this.ioExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "file-journal-io");
                t.setDaemon(true);
                return t;
            }
        );
        
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
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Journal is closed"));
        }
        
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
                
                // Write to file with optional fsync
                Path entryFile = actorJournalDir.resolve(String.format("%020d.journal", seqNum));
                try (FileOutputStream fos = new FileOutputStream(entryFile.toFile());
                     ObjectOutputStream oos = new ObjectOutputStream(fos)) {
                    oos.writeObject(entry);
                    oos.flush();
                    
                    // Fsync for durability if enabled
                    if (enableFsync) {
                        fos.getFD().sync();
                    }
                }
                
                logger.debug("Appended message for actor {} with sequence number {}", actorId, seqNum);
                return seqNum;
            } catch (Exception e) {
                logger.error("Failed to append message for actor {}", actorId, e);
                throw new RuntimeException("Failed to append message for actor " + actorId, e);
            }
        }, ioExecutor);
    }
    
    @Override
    public CompletableFuture<List<JournalEntry<M>>> readFrom(String actorId, long fromSequenceNumber) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Journal is closed"));
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path actorJournalDir = getActorJournalDir(actorId);
                if (!Files.exists(actorJournalDir)) {
                    return Collections.emptyList();
                }
                
                // Find all journal files for this actor with sequence number >= fromSequenceNumber
                List<Path> entryFiles;
                try (Stream<Path> paths = Files.list(actorJournalDir)) {
                    entryFiles = paths
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
                } catch (java.nio.file.NoSuchFileException e) {
                    // Directory was deleted between existence check and listing
                    logger.debug("Journal directory no longer exists for actor: {}", actorId);
                    return Collections.emptyList();
                }
                
                // Read entries
                List<JournalEntry<M>> entries = new ArrayList<>();
                for (Path entryFile : entryFiles) {
                    // Skip empty or corrupted files
                    try {
                        long fileSize = Files.size(entryFile);
                        if (fileSize == 0) {
                            logger.warn("Skipping empty journal file: {}", entryFile);
                            continue;
                        }
                    } catch (IOException e) {
                        logger.warn("Failed to check size of journal file {}, skipping", entryFile, e);
                        continue;
                    }
                    
                    try (ObjectInputStream is = new ObjectInputStream(new FileInputStream(entryFile.toFile()))) {
                        @SuppressWarnings("unchecked")
                        JournalEntry<M> entry = (JournalEntry<M>) is.readObject();
                        entries.add(entry);
                    } catch (EOFException e) {
                        logger.warn("Skipping corrupted/incomplete journal file: {}", entryFile);
                        // Optionally delete the corrupted file to prevent repeated warnings
                        try {
                            Files.deleteIfExists(entryFile);
                            logger.debug("Deleted corrupted journal file: {}", entryFile);
                        } catch (IOException deleteEx) {
                            logger.debug("Failed to delete corrupted journal file: {}", entryFile, deleteEx);
                        }
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
        }, ioExecutor);
    }
    
    @Override
    public CompletableFuture<Void> truncateBefore(String actorId, long upToSequenceNumber) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Journal is closed"));
        }
        
        return CompletableFuture.runAsync(() -> {
            try {
                Path actorJournalDir = getActorJournalDir(actorId);
                if (!Files.exists(actorJournalDir)) {
                    return;
                }
                
                // Find all journal files for this actor with sequence number < upToSequenceNumber
                List<Path> filesToDelete;
                try (Stream<Path> paths = Files.list(actorJournalDir)) {
                    filesToDelete = paths
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
                } catch (java.nio.file.NoSuchFileException e) {
                    // Directory was deleted between existence check and listing
                    logger.debug("Journal directory no longer exists for actor: {}", actorId);
                    return;
                }
                
                // Delete files (best-effort; ignore already-missing files)
                for (Path file : filesToDelete) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        logger.warn("Failed to delete journal file {} for actor {} during truncation", file, actorId, e);
                    }
                }
                
                logger.debug("Truncated {} messages for actor {} before sequence number {}", 
                        filesToDelete.size(), actorId, upToSequenceNumber);
            } catch (IOException e) {
                logger.error("Failed to enumerate journal files for actor {} during truncation", actorId, e);
                throw new RuntimeException("Failed to truncate messages for actor " + actorId, e);
            }
        }, ioExecutor);
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
            Optional<Long> highestSeq;
            try (Stream<Path> paths = Files.list(actorJournalDir)) {
                highestSeq = paths
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
            } catch (java.nio.file.NoSuchFileException e) {
                // Directory was deleted between existence check and listing
                logger.debug("Journal directory no longer exists for actor: {}", actorId);
                return -1;
            }
            
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
    
    /**
     * Acquires a file lock for the actor to prevent concurrent writes.
     * 
     * @param actorId The actor ID
     * @return The file lock
     * @throws IOException If locking fails
     */
    protected FileLock acquireActorLock(String actorId) throws IOException {
        return actorLocks.computeIfAbsent(actorId, id -> {
            try {
                Path lockFile = getActorJournalDir(id).resolve(".lock");
                Files.createDirectories(lockFile.getParent());
                
                FileChannel channel = FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
                );
                
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    throw new RuntimeException("Failed to acquire lock for actor: " + id);
                }
                
                logger.debug("Acquired file lock for actor: {}", id);
                return lock;
            } catch (IOException e) {
                throw new RuntimeException("Failed to acquire lock for actor: " + id, e);
            }
        });
    }
    
    /**
     * Releases the file lock for the actor.
     * 
     * @param actorId The actor ID
     */
    protected void releaseActorLock(String actorId) {
        FileLock lock = actorLocks.remove(actorId);
        if (lock != null) {
            try {
                lock.channel().close();
                lock.release();
                logger.debug("Released file lock for actor: {}", actorId);
            } catch (IOException e) {
                logger.warn("Failed to release lock for actor: {}", actorId, e);
            }
        }
    }
    
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        
        // Release all locks
        for (String actorId : new ArrayList<>(actorLocks.keySet())) {
            releaseActorLock(actorId);
        }
        
        // Shutdown IO executor
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
        
        logger.info("File message journal closed");
    }
}
