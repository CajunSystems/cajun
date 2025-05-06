package systems.cajun.mocks;

import systems.cajun.persistence.*;

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
}
