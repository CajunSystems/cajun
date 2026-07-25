package com.cajunsystems.runtime.persistence;

import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.MessageJournal;
import com.cajunsystems.persistence.PersistenceProvider;
import com.cajunsystems.persistence.PersistenceProviderRegistry;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.persistence.impl.FileSystemPersistenceProvider;

/**
 * Factory class for creating persistence component implementations.
 * 
 * This factory delegates to the configured PersistenceProvider.
 * It provides backward compatibility with the existing API while
 * allowing different persistence implementations to be plugged in.
 */
public class PersistenceFactory {
    
    /**
     * Creates a message journal using the default persistence provider.
     *
     * @param <M> The type of messages
     * @return A new MessageJournal instance
     */
    public static <M> MessageJournal<M> createFileMessageJournal() {
        return getDefaultProvider().createMessageJournal();
    }
    
    /**
     * Creates a message journal using the default persistence provider.
     *
     * @param <M> The type of messages
     * @param baseDir The base directory for persistence (implementation dependent)
     * @return A new MessageJournal instance
     */
    public static <M> MessageJournal<M> createFileMessageJournal(String baseDir) {
        return fileProviderFor(baseDir).createMessageJournal();
    }
    
    /**
     * Creates a snapshot store using the default persistence provider.
     *
     * @param <S> The type of state
     * @return A new SnapshotStore instance
     */
    public static <S> SnapshotStore<S> createFileSnapshotStore() {
        return getDefaultProvider().createSnapshotStore();
    }
    
    /**
     * Creates a snapshot store using the default persistence provider.
     *
     * @param <S> The type of state
     * @param baseDir The base directory for persistence (implementation dependent)
     * @return A new SnapshotStore instance
     */
    public static <S> SnapshotStore<S> createFileSnapshotStore(String baseDir) {
        return fileProviderFor(baseDir).createSnapshotStore();
    }
    
    /**
     * Creates a batched message journal using the default persistence provider.
     *
     * @param <M> The type of messages
     * @return A new BatchedMessageJournal instance
     */
    public static <M> BatchedMessageJournal<M> createBatchedFileMessageJournal() {
        return getDefaultProvider().createBatchedMessageJournal();
    }
    
    /**
     * Creates a batched message journal using the default persistence provider.
     *
     * @param <M> The type of messages
     * @param baseDir The base directory for persistence (implementation dependent)
     * @return A new BatchedMessageJournal instance
     */
    public static <M> BatchedMessageJournal<M> createBatchedFileMessageJournal(String baseDir) {
        return fileProviderFor(baseDir).createBatchedMessageJournal();
    }
    
    /**
     * Creates a batched message journal with custom batch settings using the default persistence provider.
     *
     * @param <M> The type of messages
     * @param baseDir The base directory for persistence (implementation dependent)
     * @param maxBatchSize The maximum number of messages to batch before flushing
     * @param maxBatchDelayMs The maximum delay in milliseconds before flushing a batch
     * @return A new BatchedMessageJournal instance
     */
    public static <M> BatchedMessageJournal<M> createBatchedFileMessageJournal(
            String baseDir, int maxBatchSize, long maxBatchDelayMs) {
        BatchedMessageJournal<M> journal = fileProviderFor(baseDir).createBatchedMessageJournal();
        journal.setMaxBatchSize(maxBatchSize);
        journal.setMaxBatchDelayMs(maxBatchDelayMs);
        return journal;
    }
    
    /**
     * System property that supplies a default persistence root directory for the
     * {@code createFile*}/{@code createBatchedFile*} factory methods when no explicit
     * {@code baseDir} argument is given.
     */
    public static final String PERSISTENCE_DIR_PROPERTY = "cajun.persistence.dir";

    /**
     * Resolves the file-system persistence provider to use for the {@code createFile*} /
     * {@code createBatchedFile*} factory methods, honoring the supplied base directory.
     * <p>
     * These factory methods are explicitly file-based (as their names indicate), so a
     * non-null/non-blank {@code baseDir} always roots a {@link FileSystemPersistenceProvider}
     * at that directory. This is what makes per-run/per-test isolation work: two journals
     * created with different {@code baseDir} values now write to different directories instead
     * of silently sharing one global {@code cajun_persistence} store keyed only by actor id.
     * <p>
     * When {@code baseDir} is null or blank, the {@value #PERSISTENCE_DIR_PROPERTY} system
     * property is consulted; if that is also unset, the registry's default provider is used
     * (preserving the historical behavior).
     *
     * @param baseDir The requested persistence root directory, or null to use the default
     * @return A persistence provider rooted at the requested directory
     */
    private static PersistenceProvider fileProviderFor(String baseDir) {
        if (baseDir != null && !baseDir.isBlank()) {
            return new FileSystemPersistenceProvider(baseDir);
        }
        String propDir = System.getProperty(PERSISTENCE_DIR_PROPERTY);
        if (propDir != null && !propDir.isBlank()) {
            return new FileSystemPersistenceProvider(propDir);
        }
        return getDefaultProvider();
    }

    /**
     * Gets the default persistence provider from the registry.
     *
     * @return The default persistence provider
     */
    private static PersistenceProvider getDefaultProvider() {
        return PersistenceProviderRegistry.getInstance().getDefaultProvider();
    }
    
    /**
     * Gets a named persistence provider from the registry.
     *
     * @param providerName The name of the provider
     * @return The persistence provider
     */
    public static PersistenceProvider getProvider(String providerName) {
        return PersistenceProviderRegistry.getInstance().getProvider(providerName);
    }
}
