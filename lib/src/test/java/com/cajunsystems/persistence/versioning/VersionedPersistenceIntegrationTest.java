package com.cajunsystems.persistence.versioning;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.builder.StatefulActorBuilder;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.persistence.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating versioned persistence with stateful actors.
 * 
 * <p>This test shows a real-world scenario where an order processing system
 * evolves through multiple schema versions:
 * <ul>
 *   <li>V0 (unversioned): orderId, amount</li>
 *   <li>V1: orderId, amount, currency</li>
 * </ul>
 */
class VersionedPersistenceIntegrationTest {
    
    // Message types for different versions
    record OrderMessageV0(String orderId, double amount) {}
    record OrderMessageV1(String orderId, BigDecimal amount, String currency) {}
    
    // State types for different versions
    record OrderStateV0(int totalOrders, double totalAmount) {}
    record OrderStateV1(int totalOrders, BigDecimal totalAmount, Map<String, BigDecimal> amountByCurrency) {}
    
    private ActorSystem system;
    private MessageMigrator migrator;
    private MockPersistenceProvider mockProvider;
    
    @BeforeEach
    void setUp() {
        system = new ActorSystem();
        migrator = new MessageMigrator();
        mockProvider = new MockPersistenceProvider();
        
        // Register message migrations
        migrator.register(OrderMessageV0.class.getName(), 0, 1, msg -> {
            OrderMessageV0 v0 = (OrderMessageV0) msg;
            return new OrderMessageV1(
                v0.orderId(),
                BigDecimal.valueOf(v0.amount()),
                "USD" // Default currency
            );
        });
        
        // Register state migrations
        migrator.register(OrderStateV0.class.getName(), 0, 1, state -> {
            OrderStateV0 v0 = (OrderStateV0) state;
            Map<String, BigDecimal> byCurrency = new HashMap<>();
            byCurrency.put("USD", BigDecimal.valueOf(v0.totalAmount()));
            return new OrderStateV1(
                v0.totalOrders(),
                BigDecimal.valueOf(v0.totalAmount()),
                byCurrency
            );
        });
    }
    
    @AfterEach
    void tearDown() {
        if (system != null) {
            system.shutdown();
        }
    }
    
    @Test
    void testVersionedPersistenceWithStatefulActor() throws Exception {
        // Create a handler that processes OrderMessageV1
        StatefulHandler<OrderStateV1, OrderMessageV1> handler = (message, state, context) -> {
            BigDecimal newTotal = state.totalAmount().add(message.amount());
            Map<String, BigDecimal> newByCurrency = new HashMap<>(state.amountByCurrency());
            newByCurrency.merge(message.currency(), message.amount(), BigDecimal::add);
            
            return new OrderStateV1(
                state.totalOrders() + 1,
                newTotal,
                newByCurrency
            );
        };
        
        // Initial state
        OrderStateV1 initialState = new OrderStateV1(
            0,
            BigDecimal.ZERO,
            new HashMap<>()
        );
        
        // Create actor with versioned persistence
        Pid actorPid = new StatefulActorBuilder<>(system, handler, initialState)
            .withId("order-processor")
            .withVersionedPersistence(mockProvider, migrator)
            .spawn();
        
        // Verify versioned persistence was configured
        assertNotNull(actorPid);
        assertTrue(mockProvider.hasPersistedData("order-processor"));
    }
    
    @Test
    void testMigrationDuringRecovery() throws Exception {
        String actorId = "order-processor-migration";
        
        // Step 1: Persist old version (V0) messages directly
        MockMessageJournal<Object> journal = (MockMessageJournal<Object>) mockProvider.createMessageJournal(actorId);
        MockSnapshotStore<Object> snapshotStore = (MockSnapshotStore<Object>) mockProvider.createSnapshotStore(actorId);
        
        // Persist V0 messages (simulating old data)
        OrderMessageV0 oldMsg1 = new OrderMessageV0("ORD-001", 100.00);
        OrderMessageV0 oldMsg2 = new OrderMessageV0("ORD-002", 50.00);
        
        VersionedJournalEntry<OrderMessageV0> entry1 = new VersionedJournalEntry<>(0, 1L, actorId, oldMsg1);
        VersionedJournalEntry<OrderMessageV0> entry2 = new VersionedJournalEntry<>(0, 2L, actorId, oldMsg2);
        
        journal.append(actorId, entry1).join();
        journal.append(actorId, entry2).join();
        
        // Persist V0 snapshot
        OrderStateV0 oldState = new OrderStateV0(2, 150.00);
        VersionedSnapshotEntry<OrderStateV0> snapshotEntry = new VersionedSnapshotEntry<>(0, actorId, oldState, 2L);
        snapshotStore.saveSnapshot(actorId, snapshotEntry, 2L).join();
        
        // Step 2: Create versioned wrappers that will migrate on read
        @SuppressWarnings("unchecked")
        MessageJournal<OrderMessageV1> castJournal = (MessageJournal<OrderMessageV1>) (MessageJournal<?>) journal;
        VersionedMessageJournal<OrderMessageV1> versionedJournal = 
            new VersionedMessageJournal<>(castJournal, migrator, 1, true);
        
        @SuppressWarnings("unchecked")
        SnapshotStore<OrderStateV1> castSnapshotStore = (SnapshotStore<OrderStateV1>) (SnapshotStore<?>) snapshotStore;
        VersionedSnapshotStore<OrderStateV1> versionedSnapshotStore = 
            new VersionedSnapshotStore<>(castSnapshotStore, migrator, 1, true);
        
        // Read messages - should be migrated to V1
        List<JournalEntry<OrderMessageV1>> messages = versionedJournal.readFrom(actorId, 1L).get(1, TimeUnit.SECONDS);
        
        assertEquals(2, messages.size());
        
        // Verify first message was migrated
        OrderMessageV1 migratedMsg1 = messages.get(0).getMessage();
        assertEquals("ORD-001", migratedMsg1.orderId());
        assertEquals(BigDecimal.valueOf(100.00), migratedMsg1.amount());
        assertEquals("USD", migratedMsg1.currency()); // Default currency added by migration
        
        // Verify second message was migrated
        OrderMessageV1 migratedMsg2 = messages.get(1).getMessage();
        assertEquals("ORD-002", migratedMsg2.orderId());
        assertEquals(BigDecimal.valueOf(50.00), migratedMsg2.amount());
        assertEquals("USD", migratedMsg2.currency());
        
        // Read snapshot - should be migrated to V1
        Optional<SnapshotEntry<OrderStateV1>> snapshotOpt = versionedSnapshotStore.getLatestSnapshot(actorId)
            .get(1, TimeUnit.SECONDS);
        
        assertTrue(snapshotOpt.isPresent());
        OrderStateV1 migratedState = snapshotOpt.get().getState();
        assertEquals(2, migratedState.totalOrders());
        assertEquals(BigDecimal.valueOf(150.00), migratedState.totalAmount());
        assertTrue(migratedState.amountByCurrency().containsKey("USD"));
        assertEquals(BigDecimal.valueOf(150.00), migratedState.amountByCurrency().get("USD"));
        
        // Verify migration metrics
        MigrationMetrics.MigrationStats stats = migrator.getMetrics().getStats();
        assertEquals(3, stats.totalMigrations()); // 2 messages + 1 snapshot
        assertEquals(3, stats.successfulMigrations());
        assertEquals(0, stats.failedMigrations());
        assertEquals(100.0, stats.successRate());
    }
    
    @Test
    void testNoMigrationForCurrentVersion() throws Exception {
        String actorId = "order-processor-current";
        
        // Persist current version (V1) messages
        MockMessageJournal<Object> journal = (MockMessageJournal<Object>) mockProvider.createMessageJournal(actorId);
        
        OrderMessageV1 msg1 = new OrderMessageV1("ORD-001", BigDecimal.valueOf(100.00), "USD");
        VersionedJournalEntry<OrderMessageV1> entry1 = new VersionedJournalEntry<>(1, 1L, actorId, msg1);
        
        journal.append(actorId, entry1).join();
        
        // Create versioned wrapper
        @SuppressWarnings("unchecked")
        MessageJournal<OrderMessageV1> castJournal = (MessageJournal<OrderMessageV1>) (MessageJournal<?>) journal;
        VersionedMessageJournal<OrderMessageV1> versionedJournal = 
            new VersionedMessageJournal<>(castJournal, migrator, 1, true);
        
        // Read through versioned journal
        List<JournalEntry<OrderMessageV1>> messages = versionedJournal.readFrom(actorId, 1L).get(1, TimeUnit.SECONDS);
        
        assertEquals(1, messages.size());
        
        // Verify no migration occurred (same version)
        MigrationMetrics.MigrationStats stats = migrator.getMetrics().getStats();
        assertEquals(0, stats.totalMigrations()); // No migration needed for current version
    }
    
    // Mock implementations (reuse from VersionedPersistenceProviderTest)
    static class MockPersistenceProvider implements PersistenceProvider {
        private final Map<String, Boolean> actorData = new ConcurrentHashMap<>();
        
        public boolean hasPersistedData(String actorId) {
            return actorData.getOrDefault(actorId, false);
        }
        
        @Override
        public <M> MessageJournal<M> createMessageJournal() {
            return new MockMessageJournal<>();
        }
        
        @Override
        public <M> MessageJournal<M> createMessageJournal(String actorId) {
            actorData.put(actorId, true);
            return new MockMessageJournal<>();
        }
        
        @Override
        public <M> BatchedMessageJournal<M> createBatchedMessageJournal() {
            return new MockBatchedMessageJournal<>();
        }
        
        @Override
        public <M> BatchedMessageJournal<M> createBatchedMessageJournal(String actorId) {
            actorData.put(actorId, true);
            return new MockBatchedMessageJournal<>();
        }
        
        @Override
        public <M> BatchedMessageJournal<M> createBatchedMessageJournal(String actorId, int maxBatchSize, long maxBatchDelayMs) {
            actorData.put(actorId, true);
            return new MockBatchedMessageJournal<>();
        }
        
        @Override
        public <S> SnapshotStore<S> createSnapshotStore() {
            return new MockSnapshotStore<>();
        }
        
        @Override
        public <S> SnapshotStore<S> createSnapshotStore(String actorId) {
            actorData.put(actorId, true);
            return new MockSnapshotStore<>();
        }
        
        @Override
        public String getProviderName() {
            return "MockProvider";
        }
        
        @Override
        public boolean isHealthy() {
            return true;
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
