package com.cajunsystems.persistence.impl;

import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.MessageJournal;
import com.cajunsystems.persistence.SnapshotEntry;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.persistence.runtime.persistence.LmdbConfig;
import com.cajunsystems.persistence.runtime.persistence.LmdbEnvironmentManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for LmdbPersistenceProvider with new implementations.
 * 
 * These tests require LMDB native libraries to be installed.
 * They will be skipped if LMDB is not available.
 */
@EnabledIf("isLmdbAvailable")
class LmdbPersistenceProviderTest {
    
    @TempDir
    Path tempDir;
    
    private LmdbPersistenceProvider provider;
    
    @BeforeEach
    void setUp() throws Exception {
        provider = new LmdbPersistenceProvider(tempDir);
    }
    
    @AfterEach
    void tearDown() throws Exception {
        if (provider != null) {
            provider.close();
        }
    }
    
    @Test
    void testCreateMessageJournal() throws Exception {
        MessageJournal<TestMessage> journal = provider.createMessageJournal("test-actor");
        
        assertNotNull(journal);
        
        // Test basic operations
        long seq = journal.append("test-actor", new TestMessage("test", 1)).get();
        assertEquals(1L, seq);
    }
    
    @Test
    void testCreateBatchedMessageJournal() throws Exception {
        BatchedMessageJournal<TestMessage> journal = provider.createBatchedMessageJournal("test-actor", 100, 100);
        
        assertNotNull(journal);
        
        // Test batch operations
        List<TestMessage> messages = List.of(
            new TestMessage("msg1", 1),
            new TestMessage("msg2", 2),
            new TestMessage("msg3", 3)
        );
        
        List<Long> sequences = journal.appendBatch("test-actor", messages).get();
        assertEquals(3, sequences.size());
    }
    
    @Test
    void testCreateSnapshotStore() throws Exception {
        SnapshotStore<TestState> store = provider.createSnapshotStore("test-actor");
        
        assertNotNull(store);
        
        // Test basic operations
        TestState state = new TestState("test", 42);
        store.saveSnapshot("test-actor", state, 1).get();
        
        Optional<SnapshotEntry<TestState>> loaded = store.getLatestSnapshot("test-actor").get();
        assertTrue(loaded.isPresent());
        assertEquals("test", loaded.get().getState().value);
    }
    
    @Test
    void testSnapshotStoreUsesLmdbBackend() throws Exception {
        SnapshotStore<TestState> store = provider.createSnapshotStore("test-actor");
        
        // Save snapshot
        store.saveSnapshot("test-actor", new TestState("test", 1), 1).get();
        
        // Close provider
        provider.close();
        
        // Reopen provider
        provider = new LmdbPersistenceProvider(tempDir);
        
        // Create new store instance
        SnapshotStore<TestState> newStore = provider.createSnapshotStore("test-actor");
        
        // Should be able to load snapshot (proving LMDB persistence)
        Optional<SnapshotEntry<TestState>> loaded = newStore.getLatestSnapshot("test-actor").get();
        assertTrue(loaded.isPresent());
        assertEquals("test", loaded.get().getState().value);
    }
    
    @Test
    void testJournalUsesMetadataDatabase() throws Exception {
        MessageJournal<TestMessage> journal = provider.createMessageJournal("test-actor");
        
        // Append messages
        journal.append("test-actor", new TestMessage("msg1", 1)).get();
        journal.append("test-actor", new TestMessage("msg2", 2)).get();
        
        // Close and reopen
        provider.close();
        provider = new LmdbPersistenceProvider(tempDir);
        
        // Create new journal instance
        MessageJournal<TestMessage> newJournal = provider.createMessageJournal("test-actor");
        
        // Should recover sequence counter from metadata DB
        long seq = newJournal.append("test-actor", new TestMessage("msg3", 3)).get();
        assertEquals(3L, seq);
    }
    
    @Test
    void testProviderHealthCheck() {
        assertTrue(provider.isHealthy());
    }
    
    @Test
    void testProviderName() {
        assertEquals("LMDB", provider.getProviderName());
    }
    
    @Test
    void testProviderMetrics() {
        var metrics = provider.getMetrics();
        
        assertNotNull(metrics);
        assertNotNull(metrics.toString());
    }
    
    @Test
    void testMultipleJournals() throws Exception {
        MessageJournal<TestMessage> journal1 = provider.createMessageJournal("actor-1");
        MessageJournal<TestMessage> journal2 = provider.createMessageJournal("actor-2");
        
        // Append to different journals
        journal1.append("actor-1", new TestMessage("msg1", 1)).get();
        journal2.append("actor-2", new TestMessage("msg2", 2)).get();
        
        // Verify isolation
        var entries1 = journal1.readFrom("actor-1", 1).get();
        var entries2 = journal2.readFrom("actor-2", 1).get();
        
        assertEquals(1, entries1.size());
        assertEquals(1, entries2.size());
        assertEquals("msg1", entries1.get(0).getMessage().text);
        assertEquals("msg2", entries2.get(0).getMessage().text);
    }
    
    @Test
    void testMultipleSnapshotStores() throws Exception {
        SnapshotStore<TestState> store1 = provider.createSnapshotStore("actor-1");
        SnapshotStore<TestState> store2 = provider.createSnapshotStore("actor-2");
        
        // Save to different stores
        store1.saveSnapshot("actor-1", new TestState("state1", 1), 1).get();
        store2.saveSnapshot("actor-2", new TestState("state2", 2), 2).get();
        
        // Verify isolation
        var snapshot1 = store1.getLatestSnapshot("actor-1").get();
        var snapshot2 = store2.getLatestSnapshot("actor-2").get();
        
        assertTrue(snapshot1.isPresent());
        assertTrue(snapshot2.isPresent());
        assertEquals("state1", snapshot1.get().getState().value);
        assertEquals("state2", snapshot2.get().getState().value);
    }
    
    @Test
    void testBatchedJournalConfiguration() throws Exception {
        BatchedMessageJournal<TestMessage> journal = provider.createBatchedMessageJournal("test-actor", 50, 200);
        
        // Verify configuration is applied
        journal.setMaxBatchSize(100);
        journal.setMaxBatchDelayMs(500);
        
        // Should still work
        long seq = journal.append("test-actor", new TestMessage("test", 1)).get();
        assertTrue(seq > 0);
    }
    
    @Test
    void testProviderCloseCleanup() throws Exception {
        // Create resources
        MessageJournal<TestMessage> journal = provider.createMessageJournal("test-actor");
        SnapshotStore<TestState> store = provider.createSnapshotStore("test-actor");
        
        // Use them
        journal.append("test-actor", new TestMessage("test", 1)).get();
        store.saveSnapshot("test-actor", new TestState("test", 1), 1).get();
        
        // Close provider
        provider.close();
        
        // Provider should be closed
        assertFalse(provider.isHealthy());
    }
    
    @Test
    void testRecoveryAfterCrash() throws Exception {
        // Create and use journal
        MessageJournal<TestMessage> journal = provider.createMessageJournal("test-actor");
        journal.append("test-actor", new TestMessage("msg1", 1)).get();
        journal.append("test-actor", new TestMessage("msg2", 2)).get();
        
        // Create and use snapshot store
        SnapshotStore<TestState> store = provider.createSnapshotStore("test-actor");
        store.saveSnapshot("test-actor", new TestState("state", 2), 2).get();
        
        // Simulate crash (close without cleanup)
        provider.close();
        
        // Reopen
        provider = new LmdbPersistenceProvider(tempDir);
        
        // Verify recovery
        MessageJournal<TestMessage> newJournal = provider.createMessageJournal("test-actor");
        var entries = newJournal.readFrom("test-actor", 1).get();
        assertEquals(2, entries.size());
        
        SnapshotStore<TestState> newStore = provider.createSnapshotStore("test-actor");
        var snapshot = newStore.getLatestSnapshot("test-actor").get();
        assertTrue(snapshot.isPresent());
        assertEquals("state", snapshot.get().getState().value);
    }
    
    /**
     * Check if LMDB native libraries are available.
     */
    static boolean isLmdbAvailable() {
        try {
            Class.forName("org.lmdbjava.Env");
            Path testPath = Path.of(System.getProperty("java.io.tmpdir"), "lmdb-test-" + System.nanoTime());
            LmdbConfig config = LmdbConfig.builder()
                .dbPath(testPath)
                .mapSize(1024 * 1024)
                .build();
            try (LmdbEnvironmentManager testEnv = new LmdbEnvironmentManager(config)) {
                return true;
            } catch (UnsatisfiedLinkError e) {
                System.err.println("LMDB native libraries not available: " + e.getMessage());
                return false;
            }
        } catch (Throwable e) {
            System.err.println("LMDB not available: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Test message class for serialization.
     */
    static class TestMessage implements Serializable {
        private static final long serialVersionUID = 1L;
        final String text;
        final int value;
        
        TestMessage(String text, int value) {
            this.text = text;
            this.value = value;
        }
    }
    
    /**
     * Test state class for serialization.
     */
    static class TestState implements Serializable {
        private static final long serialVersionUID = 1L;
        final String value;
        final int count;
        
        TestState(String value, int count) {
            this.value = value;
            this.count = count;
        }
    }
}
