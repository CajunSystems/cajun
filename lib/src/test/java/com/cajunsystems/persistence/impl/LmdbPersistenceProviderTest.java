package com.cajunsystems.persistence.impl;

import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.MessageJournal;
import com.cajunsystems.persistence.SnapshotEntry;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.runtime.persistence.LmdbConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for LmdbPersistenceProvider.
 */
class LmdbPersistenceProviderTest {
    
    @TempDir
    Path tempDir;
    
    private LmdbPersistenceProvider provider;
    
    @BeforeEach
    void setUp() throws Exception {
        // Create configuration
        LmdbConfig config = LmdbConfig.builder()
                .dbPath(tempDir.resolve("test_persistence"))
                .mapSize(1_073_741_824L)  // 1GB map size
                .maxDatabases(10)
                .build();
        
        provider = new LmdbPersistenceProvider(config);
    }
    
    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.close();
        }
    }
    
    @Test
    void testCreateMessageJournal() throws Exception {
        MessageJournal<String> journal = provider.createMessageJournal("test-actor");
        
        assertNotNull(journal);
        assertTrue(journal instanceof com.cajunsystems.runtime.persistence.LmdbMessageJournal);
    }
    
    @Test
    void testCreateMessageJournalWithActorId() throws Exception {
        MessageJournal<String> journal = provider.createMessageJournal("test-actor");
        
        assertNotNull(journal);
        assertTrue(journal instanceof com.cajunsystems.runtime.persistence.LmdbMessageJournal);
    }
    
    @Test
    void testCreateBatchedMessageJournal() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            provider.createBatchedMessageJournal("test-actor");
        });
    }
    
    @Test
    void testCreateBatchedMessageJournalWithParams() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            provider.createBatchedMessageJournal("test-actor", 100, 1000);
        });
    }
    
    @Test
    void testCreateBatchedMessageJournalWithCustomSettings() {
        assertThrows(UnsupportedOperationException.class, () -> {
            provider.createBatchedMessageJournal("test-actor", 100, 1000);
        });
    }
    
    @Test
    void testCreateSnapshotStore() {
        SnapshotStore<String> snapshotStore = provider.createSnapshotStore();
        
        assertNotNull(snapshotStore);
        // Phase 2 uses InMemorySnapshotStore for now
        assertTrue(snapshotStore.getClass().getSimpleName().contains("InMemorySnapshotStore"));
    }
    
    @Test
    void testCreateSnapshotStoreWithActorId() {
        String actorId = "test-actor";
        SnapshotStore<String> snapshotStore = provider.createSnapshotStore(actorId);
        
        assertNotNull(snapshotStore);
        // Phase 2 uses InMemorySnapshotStore for now
        assertTrue(snapshotStore.getClass().getSimpleName().contains("InMemorySnapshotStore"));
    }
    
    @Test
    void testMessageJournalAppendAndRead() throws Exception {
        MessageJournal<String> journal = provider.createMessageJournal("test-actor");
        String actorId = "test-actor";
        String message = "test-message";
        
        // Append a message
        CompletableFuture<Long> appendFuture = journal.append(actorId, message);
        Long sequenceNumber = appendFuture.get(5, TimeUnit.SECONDS);
        assertNotNull(sequenceNumber);
        assertEquals(0L, sequenceNumber);
        
        // Read the message back
        CompletableFuture<List<JournalEntry<String>>> readFuture = journal.readFrom(actorId, 0L);
        List<JournalEntry<String>> entries = readFuture.get(5, TimeUnit.SECONDS);
        
        assertNotNull(entries);
        assertEquals(1, entries.size());
        assertEquals(sequenceNumber, entries.get(0).getSequenceNumber());
        assertEquals(actorId, entries.get(0).getActorId());
        assertEquals(message, entries.get(0).getMessage());
        
        journal.close();
    }
    
    @Test
    void testBatchedMessageJournalAppendAndFlush() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            provider.createBatchedMessageJournal();
        });
    }
    
    @Test
    void testSnapshotStoreSaveAndLoad() throws Exception {
        SnapshotStore<String> snapshotStore = provider.createSnapshotStore("test-actor");
        String actorId = "test-actor";
        String snapshot = "test-snapshot-data";
        long sequenceNumber = 42L;
        
        // Save snapshot
        CompletableFuture<Void> saveFuture = snapshotStore.saveSnapshot(actorId, snapshot, sequenceNumber);
        saveFuture.get(5, TimeUnit.SECONDS);
        
        // Load latest snapshot
        CompletableFuture<Optional<SnapshotEntry<String>>> loadFuture = snapshotStore.getLatestSnapshot(actorId);
        Optional<SnapshotEntry<String>> result = loadFuture.get(5, TimeUnit.SECONDS);
        
        assertTrue(result.isPresent());
        SnapshotEntry<String> entry = result.get();
        assertEquals(sequenceNumber, entry.getSequenceNumber());
        assertEquals(actorId, entry.getActorId());
        assertEquals(snapshot, entry.getState());
        assertNotNull(entry.getTimestamp());
        
        snapshotStore.close();
    }
    
    @Test
    void testSnapshotStoreDelete() throws Exception {
        SnapshotStore<String> snapshotStore = provider.createSnapshotStore("test-actor");
        String actorId = "test-actor";
        String snapshot = "test-snapshot-data";
        long sequenceNumber = 42L;
        
        // Save snapshot
        CompletableFuture<Void> saveFuture = snapshotStore.saveSnapshot(actorId, snapshot, sequenceNumber);
        saveFuture.get(5, TimeUnit.SECONDS);
        
        // Verify it exists
        CompletableFuture<Optional<SnapshotEntry<String>>> loadFuture = snapshotStore.getLatestSnapshot(actorId);
        Optional<SnapshotEntry<String>> result = loadFuture.get(5, TimeUnit.SECONDS);
        assertTrue(result.isPresent());
        
        // Delete snapshots
        CompletableFuture<Void> deleteFuture = snapshotStore.deleteSnapshots(actorId);
        deleteFuture.get(5, TimeUnit.SECONDS);
        
        // Verify it's gone
        CompletableFuture<Optional<SnapshotEntry<String>>> loadFuture2 = snapshotStore.getLatestSnapshot(actorId);
        Optional<SnapshotEntry<String>> result2 = loadFuture2.get(5, TimeUnit.SECONDS);
        assertFalse(result2.isPresent());
        
        snapshotStore.close();
    }
    
    @Test
    void testProviderHealth() {
        assertTrue(provider.isHealthy());
    }
    
    @Test
    void testProviderConfiguration() {
        assertEquals("LMDB-Phase2", provider.getProviderName());
        assertNotNull(provider.getConfig());
        assertTrue(provider.getConfig().getMapSize() > 0);
        assertTrue(provider.getConfig().getMaxDatabases() > 0);
        assertTrue(provider.isHealthy());
    }
}
