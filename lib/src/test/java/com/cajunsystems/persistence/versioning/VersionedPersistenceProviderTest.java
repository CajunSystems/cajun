package com.cajunsystems.persistence.versioning;

import com.cajunsystems.persistence.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class VersionedPersistenceProviderTest {
    
    // Test message types
    record MessageV1(String id, int value) {}
    record MessageV2(String id, int value, String extra) {}
    
    // Test state types
    record StateV1(String name, int count) {}
    record StateV2(String name, int count, boolean active) {}
    
    private MessageMigrator migrator;
    private MockPersistenceProvider mockProvider;
    private VersionedPersistenceProvider versionedProvider;
    
    @BeforeEach
    void setUp() {
        migrator = new MessageMigrator();
        mockProvider = new MockPersistenceProvider();
        versionedProvider = new VersionedPersistenceProvider(mockProvider, migrator);
        
        // Register migrations
        migrator.register(MessageV1.class.getName(), 0, 1, msg -> {
            MessageV1 v1 = (MessageV1) msg;
            return new MessageV2(v1.id(), v1.value(), "default");
        });
        
        migrator.register(StateV1.class.getName(), 0, 1, state -> {
            StateV1 v1 = (StateV1) state;
            return new StateV2(v1.name(), v1.count(), true);
        });
    }
    
    @Test
    void testProviderName() {
        assertEquals("Versioned(MockProvider)", versionedProvider.getProviderName());
    }
    
    @Test
    void testIsHealthy() {
        assertTrue(versionedProvider.isHealthy());
        mockProvider.setHealthy(false);
        assertFalse(versionedProvider.isHealthy());
    }
    
    @Test
    void testGetDelegate() {
        assertSame(mockProvider, versionedProvider.getDelegate());
    }
    
    @Test
    void testGetMigrator() {
        assertSame(migrator, versionedProvider.getMigrator());
    }
    
    @Test
    void testGetCurrentVersion() {
        assertEquals(PersistenceVersion.CURRENT_VERSION, versionedProvider.getCurrentVersion());
    }
    
    @Test
    void testIsAutoMigrate() {
        assertTrue(versionedProvider.isAutoMigrate());
        
        VersionedPersistenceProvider noAutoMigrate = 
            new VersionedPersistenceProvider(mockProvider, migrator, 1, false);
        assertFalse(noAutoMigrate.isAutoMigrate());
    }
    
    @Test
    void testCreateMessageJournal() {
        MessageJournal<MessageV1> journal = versionedProvider.createMessageJournal();
        assertNotNull(journal);
        assertTrue(journal instanceof VersionedMessageJournal);
    }
    
    @Test
    void testCreateMessageJournalWithActorId() {
        MessageJournal<MessageV1> journal = versionedProvider.createMessageJournal("actor-1");
        assertNotNull(journal);
        assertTrue(journal instanceof VersionedMessageJournal);
    }
    
    @Test
    void testCreateBatchedMessageJournal() {
        BatchedMessageJournal<MessageV1> journal = versionedProvider.createBatchedMessageJournal();
        assertNotNull(journal);
        assertTrue(journal instanceof VersionedBatchedMessageJournal);
    }
    
    @Test
    void testCreateBatchedMessageJournalWithActorId() {
        BatchedMessageJournal<MessageV1> journal = versionedProvider.createBatchedMessageJournal("actor-1");
        assertNotNull(journal);
        assertTrue(journal instanceof VersionedBatchedMessageJournal);
    }
    
    @Test
    void testCreateBatchedMessageJournalWithSettings() {
        BatchedMessageJournal<MessageV1> journal = 
            versionedProvider.createBatchedMessageJournal("actor-1", 100, 1000);
        assertNotNull(journal);
        assertTrue(journal instanceof VersionedBatchedMessageJournal);
    }
    
    @Test
    void testCreateSnapshotStore() {
        SnapshotStore<StateV1> store = versionedProvider.createSnapshotStore();
        assertNotNull(store);
        assertTrue(store instanceof VersionedSnapshotStore);
    }
    
    @Test
    void testCreateSnapshotStoreWithActorId() {
        SnapshotStore<StateV1> store = versionedProvider.createSnapshotStore("actor-1");
        assertNotNull(store);
        assertTrue(store instanceof VersionedSnapshotStore);
    }
    
    @Test
    void testGetMigrationStats() {
        MigrationMetrics.MigrationStats stats = versionedProvider.getMigrationStats();
        assertNotNull(stats);
        assertEquals(0, stats.totalMigrations());
    }
    
    @Test
    void testToString() {
        String str = versionedProvider.toString();
        assertTrue(str.contains("VersionedPersistenceProvider"));
        assertTrue(str.contains("MockProvider"));
    }
    
    @Test
    void testNullDelegateThrows() {
        assertThrows(NullPointerException.class, () ->
            new VersionedPersistenceProvider(null, migrator)
        );
    }
    
    @Test
    void testNullMigratorThrows() {
        assertThrows(NullPointerException.class, () ->
            new VersionedPersistenceProvider(mockProvider, null)
        );
    }
    
    @Test
    void testInvalidVersionThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new VersionedPersistenceProvider(mockProvider, migrator, -1, true)
        );
    }
    
    // Mock implementation for testing
    static class MockPersistenceProvider implements PersistenceProvider {
        private boolean healthy = true;
        
        public void setHealthy(boolean healthy) {
            this.healthy = healthy;
        }
        
        @Override
        public <M> MessageJournal<M> createMessageJournal() {
            return new MockMessageJournal<>();
        }
        
        @Override
        public <M> MessageJournal<M> createMessageJournal(String actorId) {
            return new MockMessageJournal<>();
        }
        
        @Override
        public <M> BatchedMessageJournal<M> createBatchedMessageJournal() {
            return new MockBatchedMessageJournal<>();
        }
        
        @Override
        public <M> BatchedMessageJournal<M> createBatchedMessageJournal(String actorId) {
            return new MockBatchedMessageJournal<>();
        }
        
        @Override
        public <M> BatchedMessageJournal<M> createBatchedMessageJournal(String actorId, int maxBatchSize, long maxBatchDelayMs) {
            return new MockBatchedMessageJournal<>();
        }
        
        @Override
        public <S> SnapshotStore<S> createSnapshotStore() {
            return new MockSnapshotStore<>();
        }
        
        @Override
        public <S> SnapshotStore<S> createSnapshotStore(String actorId) {
            return new MockSnapshotStore<>();
        }
        
        @Override
        public String getProviderName() {
            return "MockProvider";
        }
        
        @Override
        public boolean isHealthy() {
            return healthy;
        }
    }
    
    static class MockMessageJournal<M> implements MessageJournal<M> {
        private final Map<String, List<JournalEntry<M>>> storage = new ConcurrentHashMap<>();
        private final Map<String, Long> sequences = new ConcurrentHashMap<>();
        
        @Override
        public CompletableFuture<Long> append(String actorId, M message) {
            long seq = sequences.compute(actorId, (k, v) -> v == null ? 1L : v + 1);
            JournalEntry<M> entry = new JournalEntry<>(seq, actorId, message, Instant.now());
            storage.computeIfAbsent(actorId, k -> new ArrayList<>()).add(entry);
            return CompletableFuture.completedFuture(seq);
        }
        
        @Override
        public CompletableFuture<List<JournalEntry<M>>> readFrom(String actorId, long fromSequenceNumber) {
            List<JournalEntry<M>> entries = storage.getOrDefault(actorId, List.of()).stream()
                .filter(e -> e.getSequenceNumber() >= fromSequenceNumber)
                .toList();
            return CompletableFuture.completedFuture(entries);
        }
        
        @Override
        public CompletableFuture<Void> truncateBefore(String actorId, long upToSequenceNumber) {
            List<JournalEntry<M>> entries = storage.get(actorId);
            if (entries != null) {
                entries.removeIf(e -> e.getSequenceNumber() < upToSequenceNumber);
            }
            return CompletableFuture.completedFuture(null);
        }
        
        @Override
        public CompletableFuture<Long> getHighestSequenceNumber(String actorId) {
            return CompletableFuture.completedFuture(sequences.getOrDefault(actorId, -1L));
        }
        
        @Override
        public void close() {}
    }
    
    static class MockBatchedMessageJournal<M> extends MockMessageJournal<M> implements BatchedMessageJournal<M> {
        @Override
        public CompletableFuture<List<Long>> appendBatch(String actorId, List<M> messages) {
            List<Long> seqs = new ArrayList<>();
            for (M msg : messages) {
                seqs.add(append(actorId, msg).join());
            }
            return CompletableFuture.completedFuture(seqs);
        }
        
        @Override
        public void setMaxBatchSize(int maxBatchSize) {}
        
        @Override
        public void setMaxBatchDelayMs(long maxBatchDelayMs) {}
        
        @Override
        public CompletableFuture<Void> flush() {
            return CompletableFuture.completedFuture(null);
        }
    }
    
    static class MockSnapshotStore<S> implements SnapshotStore<S> {
        private final Map<String, SnapshotEntry<S>> storage = new ConcurrentHashMap<>();
        
        @Override
        public CompletableFuture<Void> saveSnapshot(String actorId, S state, long sequenceNumber) {
            SnapshotEntry<S> entry = new SnapshotEntry<>(actorId, state, sequenceNumber, Instant.now());
            storage.put(actorId, entry);
            return CompletableFuture.completedFuture(null);
        }
        
        @Override
        public CompletableFuture<Optional<SnapshotEntry<S>>> getLatestSnapshot(String actorId) {
            return CompletableFuture.completedFuture(Optional.ofNullable(storage.get(actorId)));
        }
        
        @Override
        public CompletableFuture<Void> deleteSnapshots(String actorId) {
            storage.remove(actorId);
            return CompletableFuture.completedFuture(null);
        }
        
        @Override
        public void close() {}
    }
}
