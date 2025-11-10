package com.cajunsystems.persistence.impl;

import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.MessageJournal;
import com.cajunsystems.persistence.SnapshotEntry;
import com.cajunsystems.persistence.SnapshotStore;
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
    void setUp() {
        String baseDir = tempDir.resolve("test_persistence").toString();
        provider = new LmdbPersistenceProvider(baseDir, 1_073_741_824L, 10); // 1GB map size, 10 databases
    }
    
    @AfterEach
    void tearDown() {
        if (provider != null) {
            // No explicit close method on provider, but ensure resources are cleaned up
        }
    }
    
    @Test
    void testCreateMessageJournal() {
        MessageJournal<String> journal = provider.createMessageJournal();
        
        assertNotNull(journal);
        assertTrue(journal instanceof com.cajunsystems.runtime.persistence.LmdbMessageJournal);
        assertEquals("lmdb", provider.getProviderName());
    }
    
    @Test
    void testCreateMessageJournalWithActorId() {
        String actorId = "test-actor";
        MessageJournal<String> journal = provider.createMessageJournal(actorId);
        
        assertNotNull(journal);
        assertTrue(journal instanceof com.cajunsystems.runtime.persistence.LmdbMessageJournal);
    }
    
    @Test
    void testCreateBatchedMessageJournal() {
        BatchedMessageJournal<String> journal = provider.createBatchedMessageJournal();
        
        assertNotNull(journal);
        assertTrue(journal instanceof com.cajunsystems.runtime.persistence.LmdbBatchedMessageJournal);
    }
    
    @Test
    void testCreateBatchedMessageJournalWithActorId() {
        String actorId = "test-actor";
        BatchedMessageJournal<String> journal = provider.createBatchedMessageJournal(actorId);
        
        assertNotNull(journal);
        assertTrue(journal instanceof com.cajunsystems.runtime.persistence.LmdbBatchedMessageJournal);
    }
    
    @Test
    void testCreateBatchedMessageJournalWithCustomSettings() {
        String actorId = "test-actor";
        int maxBatchSize = 50;
        long maxBatchDelayMs = 200;
        
        BatchedMessageJournal<String> journal = provider.createBatchedMessageJournal(
            actorId, maxBatchSize, maxBatchDelayMs);
        
        assertNotNull(journal);
        assertTrue(journal instanceof com.cajunsystems.runtime.persistence.LmdbBatchedMessageJournal);
    }
    
    @Test
    void testCreateSnapshotStore() {
        SnapshotStore<String> snapshotStore = provider.createSnapshotStore();
        
        assertNotNull(snapshotStore);
        assertTrue(snapshotStore instanceof com.cajunsystems.runtime.persistence.LmdbSnapshotStore);
    }
    
    @Test
    void testCreateSnapshotStoreWithActorId() {
        String actorId = "test-actor";
        SnapshotStore<String> snapshotStore = provider.createSnapshotStore(actorId);
        
        assertNotNull(snapshotStore);
        assertTrue(snapshotStore instanceof com.cajunsystems.runtime.persistence.LmdbSnapshotStore);
    }
    
    @Test
    void testMessageJournalAppendAndRead() throws Exception {
        MessageJournal<String> journal = provider.createMessageJournal();
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
        BatchedMessageJournal<String> journal = provider.createBatchedMessageJournal();
        String actorId = "test-actor";
        
        // Append multiple messages
        CompletableFuture<Long> future1 = journal.append(actorId, "message1");
        CompletableFuture<Long> future2 = journal.append(actorId, "message2");
        CompletableFuture<Long> future3 = journal.append(actorId, "message3");
        
        // Flush to ensure all messages are written
        CompletableFuture<Void> flushFuture = journal.flush();
        flushFuture.get(5, TimeUnit.SECONDS);
        
        // Verify sequence numbers
        Long seq1 = future1.get(5, TimeUnit.SECONDS);
        Long seq2 = future2.get(5, TimeUnit.SECONDS);
        Long seq3 = future3.get(5, TimeUnit.SECONDS);
        
        assertNotNull(seq1);
        assertNotNull(seq2);
        assertNotNull(seq3);
        
        assertTrue(seq1 < seq2);
        assertTrue(seq2 < seq3);
        
        journal.close();
    }
    
    @Test
    void testSnapshotStoreSaveAndLoad() throws Exception {
        SnapshotStore<String> snapshotStore = provider.createSnapshotStore();
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
        SnapshotStore<String> snapshotStore = provider.createSnapshotStore();
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
        assertEquals("lmdb", provider.getProviderName());
        assertNotNull(provider.getBaseDir());
        assertTrue(provider.getMapSize() > 0);
        assertTrue(provider.getMaxDbs() > 0);
    }
}
