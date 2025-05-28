package com.cajunsystems.runtime.persistence;

import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.MessageJournal;
import com.cajunsystems.persistence.PersistenceProvider;
import com.cajunsystems.persistence.PersistenceProviderRegistry;
import com.cajunsystems.persistence.SnapshotStore;

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
        // For backward compatibility, we'll stick with the default provider
        // but in the future this could be enhanced to look up a provider by baseDir
        return getDefaultProvider().createMessageJournal();
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
        // For backward compatibility, we'll stick with the default provider
        return getDefaultProvider().createSnapshotStore();
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
        // For backward compatibility, we'll stick with the default provider
        return getDefaultProvider().createBatchedMessageJournal();
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
        // For backward compatibility, we'll stick with the default provider
        BatchedMessageJournal<M> journal = getDefaultProvider().createBatchedMessageJournal();
        journal.setMaxBatchSize(maxBatchSize);
        journal.setMaxBatchDelayMs(maxBatchDelayMs);
        return journal;
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
