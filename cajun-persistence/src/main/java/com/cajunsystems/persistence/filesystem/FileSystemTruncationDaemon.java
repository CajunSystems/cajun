package com.cajunsystems.persistence.filesystem;

import com.cajunsystems.persistence.MessageJournal;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Wrapper daemon that exposes a truncation-focused API for filesystem journals.
 * <p>
 * This class delegates to the existing {@link FileSystemCleanupDaemon} singleton,
 * but uses terminology aligned with truncation rather than generic cleanup.
 */
public final class FileSystemTruncationDaemon implements AutoCloseable {

    private static final FileSystemTruncationDaemon INSTANCE = new FileSystemTruncationDaemon();

    private final FileSystemCleanupDaemon delegate = FileSystemCleanupDaemon.getInstance();

    private FileSystemTruncationDaemon() {
    }

    public static FileSystemTruncationDaemon getInstance() {
        return INSTANCE;
    }

    /**
     * Register a journal for background truncation using the global retention
     * configuration.
     */
    public void registerJournal(String actorId, MessageJournal<?> journal) {
        delegate.registerJournal(actorId, journal);
    }

    /**
     * Register a journal for background truncation with a per-actor retention
     * policy.
     */
    public void registerJournal(String actorId, MessageJournal<?> journal, long retainLastMessagesPerActor) {
        delegate.registerJournal(actorId, journal, retainLastMessagesPerActor);
    }

    /**
     * Unregister a journal from background truncation.
     */
    public void unregisterJournal(String actorId) {
        delegate.unregisterJournal(actorId);
    }

    /**
     * Configure the default number of messages to retain per actor when truncating.
     */
    public void setRetainLastMessagesPerActor(long retainLastMessagesPerActor) {
        delegate.setRetainLastMessagesPerActor(retainLastMessagesPerActor);
    }

    /**
     * Configure how often the truncation daemon runs.
     */
    public void setInterval(Duration interval) {
        delegate.setInterval(interval);
    }

    /**
     * Start the background truncation daemon.
     */
    public void start() {
        delegate.start();
    }

    /**
     * Perform a single truncation pass over all registered journals.
     */
    public CompletableFuture<Void> runCleanupOnce() {
        return delegate.runCleanupOnce();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
