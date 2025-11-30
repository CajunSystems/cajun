package com.cajunsystems.persistence;

/**
 * Interface for persistence providers that create persistence components.
 * This allows for different persistence implementations to be plugged into the actor system.
 */
public interface PersistenceProvider {
    
    /**
     * Creates a message journal for persisting actor messages.
     *
     * @param <M> The type of messages
     * @return A new MessageJournal instance
     */
    <M> MessageJournal<M> createMessageJournal();
    
    /**
     * Creates a message journal for persisting actor messages with the specified actor ID.
     *
     * @param <M> The type of messages
     * @param actorId The ID of the actor
     * @return A new MessageJournal instance
     */
    <M> MessageJournal<M> createMessageJournal(String actorId);
    
    /**
     * Creates a batched message journal for persisting actor messages.
     *
     * @param <M> The type of messages
     * @return A new BatchedMessageJournal instance
     */
    <M> BatchedMessageJournal<M> createBatchedMessageJournal();
    
    /**
     * Creates a batched message journal for persisting actor messages with the specified actor ID.
     *
     * @param <M> The type of messages
     * @param actorId The ID of the actor
     * @return A new BatchedMessageJournal instance
     */
    <M> BatchedMessageJournal<M> createBatchedMessageJournal(String actorId);
    
    /**
     * Creates a batched message journal with custom batch settings.
     *
     * @param <M> The type of messages
     * @param actorId The ID of the actor
     * @param maxBatchSize The maximum number of messages to batch before flushing
     * @param maxBatchDelayMs The maximum delay in milliseconds before flushing a batch
     * @return A new BatchedMessageJournal instance
     */
    <M> BatchedMessageJournal<M> createBatchedMessageJournal(
            String actorId, int maxBatchSize, long maxBatchDelayMs);
    
    /**
     * Creates a snapshot store for persisting actor state.
     *
     * @param <S> The type of state
     * @return A new SnapshotStore instance
     */
    <S> SnapshotStore<S> createSnapshotStore();
    
    /**
     * Creates a snapshot store for persisting actor state with the specified actor ID.
     *
     * @param <S> The type of state
     * @param actorId The ID of the actor
     * @return A new SnapshotStore instance
     */
    <S> SnapshotStore<S> createSnapshotStore(String actorId);
    
    /**
     * Gets the name of this persistence provider.
     *
     * @return The provider name
     */
    String getProviderName();
    
    /**
     * Checks if the persistence provider is healthy and operational.
     *
     * @return true if the provider is healthy, false otherwise
     */
    boolean isHealthy();

    /**
     * List all actor IDs that have persisted state (snapshots or journals).
     * This method scans the persistence storage and returns actor IDs based on
     * file/directory names, WITHOUT loading the actual state data.
     * <p>
     * This is used for ID counter initialization to prevent collisions with
     * existing persisted actors.
     * <p>
     * Default implementation returns an empty list (for providers that don't support
     * this operation or don't have persistence).
     *
     * @return List of actor IDs that have persisted data
     */
    default java.util.List<String> listPersistedActors() {
        return java.util.Collections.emptyList();
    }
}
