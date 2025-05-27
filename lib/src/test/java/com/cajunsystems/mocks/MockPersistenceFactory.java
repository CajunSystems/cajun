package com.cajunsystems.mocks;


import com.cajunsystems.persistence.MockBatchedMessageJournal;
import com.cajunsystems.persistence.MockMessageJournal;
import com.cajunsystems.persistence.MockSnapshotStore;

/**
 * A mock implementation of the PersistenceFactory for testing.
 * This class provides static methods to create mock persistence components.
 */
public class MockPersistenceFactory {
    
    /**
     * Creates a mock MessageJournal for testing.
     * 
     * @param <M> The type of messages
     * @return A new MockMessageJournal instance
     */
    public static <M> MockMessageJournal<M> createMockMessageJournal() {
        return new MockMessageJournal<>();
    }
    
    /**
     * Creates a mock BatchedMessageJournal for testing.
     * 
     * @param <M> The type of messages
     * @return A new MockBatchedMessageJournal instance
     */
    public static <M> MockBatchedMessageJournal<M> createMockBatchedMessageJournal() {
        return new MockBatchedMessageJournal<>();
    }
    
    /**
     * Creates a mock BatchedMessageJournal with custom batch settings for testing.
     * 
     * @param <M> The type of messages
     * @param maxBatchSize The maximum batch size
     * @param maxBatchDelayMs The maximum batch delay in milliseconds
     * @return A new MockBatchedMessageJournal instance
     */
    public static <M> MockBatchedMessageJournal<M> createMockBatchedMessageJournal(int maxBatchSize, long maxBatchDelayMs) {
        MockBatchedMessageJournal<M> journal = new MockBatchedMessageJournal<>();
        journal.setMaxBatchSize(maxBatchSize);
        journal.setMaxBatchDelayMs(maxBatchDelayMs);
        return journal;
    }
    
    /**
     * Creates a mock SnapshotStore for testing.
     * 
     * @param <T> The type of state
     * @return A new MockSnapshotStore instance
     */
    public static <T> MockSnapshotStore<T> createMockSnapshotStore() {
        return new MockSnapshotStore<>();
    }
    
    /**
     * Creates a test configuration with both mock MessageJournal and SnapshotStore.
     * 
     * @param <M> The type of messages
     * @param <T> The type of state
     * @return A PersistenceTestConfig containing both mocks
     */
    public static <M, T> PersistenceTestConfig<M, T> createMockPersistence() {
        return new PersistenceTestConfig<>(createMockMessageJournal(), createMockSnapshotStore());
    }
    
    /**
     * Creates a test configuration with both mock BatchedMessageJournal and SnapshotStore.
     * 
     * @param <M> The type of messages
     * @param <T> The type of state
     * @return A BatchedPersistenceTestConfig containing both mocks
     */
    public static <M, T> BatchedPersistenceTestConfig<M, T> createMockBatchedPersistence() {
        return new BatchedPersistenceTestConfig<>(createMockBatchedMessageJournal(), createMockSnapshotStore());
    }
    
    /**
     * Creates a test configuration with both mock BatchedMessageJournal and SnapshotStore with custom batch settings.
     * 
     * @param <M> The type of messages
     * @param <T> The type of state
     * @param maxBatchSize The maximum batch size
     * @param maxBatchDelayMs The maximum batch delay in milliseconds
     * @return A BatchedPersistenceTestConfig containing both mocks
     */
    public static <M, T> BatchedPersistenceTestConfig<M, T> createMockBatchedPersistence(int maxBatchSize, long maxBatchDelayMs) {
        return new BatchedPersistenceTestConfig<>(createMockBatchedMessageJournal(maxBatchSize, maxBatchDelayMs), createMockSnapshotStore());
    }
    
    /**
     * A container class for persistence test configuration.
     * 
     * @param <M> The type of messages
     * @param <T> The type of state
     */
    public static class PersistenceTestConfig<M, T> {
        private final MockMessageJournal<M> messageJournal;
        private final MockSnapshotStore<T> snapshotStore;
        
        public PersistenceTestConfig(MockMessageJournal<M> messageJournal, MockSnapshotStore<T> snapshotStore) {
            this.messageJournal = messageJournal;
            this.snapshotStore = snapshotStore;
        }
        
        public MockMessageJournal<M> getMessageJournal() {
            return messageJournal;
        }
        
        public MockSnapshotStore<T> getSnapshotStore() {
            return snapshotStore;
        }
    }
    
    /**
     * A container class for batched persistence test configuration.
     * 
     * @param <M> The type of messages
     * @param <T> The type of state
     */
    public static class BatchedPersistenceTestConfig<M, T> {
        private final MockBatchedMessageJournal<M> messageJournal;
        private final MockSnapshotStore<T> snapshotStore;
        
        public BatchedPersistenceTestConfig(MockBatchedMessageJournal<M> messageJournal, MockSnapshotStore<T> snapshotStore) {
            this.messageJournal = messageJournal;
            this.snapshotStore = snapshotStore;
        }
        
        public MockBatchedMessageJournal<M> getMessageJournal() {
            return messageJournal;
        }
        
        public MockSnapshotStore<T> getSnapshotStore() {
            return snapshotStore;
        }
    }
}
