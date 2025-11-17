package com.cajunsystems.persistence.runtime.persistence;

import com.cajunsystems.persistence.SnapshotEntry;
import com.cajunsystems.persistence.SnapshotMetadata;
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
 * Tests for LmdbSnapshotStore implementation.
 * 
 * These tests run in a container with LMDB native libraries.
 * They will be skipped if LMDB is not available.
 */
@EnabledIf("isLmdbAvailable")
class LmdbSnapshotStoreTest {
    
    @TempDir
    Path tempDir;
    
    private LmdbEnvironmentManager envManager;
    private LmdbSnapshotStore<TestState> snapshotStore;
    
    @BeforeEach
    void setUp() throws Exception {
        LmdbConfig config = LmdbConfig.builder()
            .dbPath(tempDir)
            .mapSize(10 * 1024 * 1024) // 10MB
            .build();
        
        envManager = new LmdbEnvironmentManager(config);
        snapshotStore = new LmdbSnapshotStore<>("test-actor", envManager);
    }
    
    @AfterEach
    void tearDown() {
        if (snapshotStore != null) {
            snapshotStore.close();
        }
        if (envManager != null) {
            envManager.close();
        }
    }
    
    /**
     * Check if LMDB native libraries are available.
     */
    static boolean isLmdbAvailable() {
        try {
            // Try to load LMDB native library
            Class.forName("org.lmdbjava.Env");
            // Try to create a minimal environment to verify native libs work
            Path testPath = Path.of(System.getProperty("java.io.tmpdir"), "lmdb-test-" + System.nanoTime());
            LmdbConfig config = LmdbConfig.builder()
                .dbPath(testPath)
                .mapSize(1024 * 1024) // 1MB
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
    
    @Test
    void testSaveAndLoadSnapshot() throws Exception {
        String actorId = "actor-1";
        TestState state = new TestState("test-value", 42);
        long sequenceNumber = 100L;
        
        // Save snapshot
        snapshotStore.saveSnapshot(actorId, state, sequenceNumber).get();
        
        // Load snapshot
        Optional<SnapshotEntry<TestState>> loaded = snapshotStore.getLatestSnapshot(actorId).get();
        
        assertTrue(loaded.isPresent());
        assertEquals(actorId, loaded.get().getActorId());
        assertEquals(sequenceNumber, loaded.get().getSequenceNumber());
        assertEquals("test-value", loaded.get().getState().value);
        assertEquals(42, loaded.get().getState().count);
    }
    
    @Test
    void testGetLatestSnapshotWhenEmpty() throws Exception {
        Optional<SnapshotEntry<TestState>> loaded = snapshotStore.getLatestSnapshot("non-existent").get();
        
        assertFalse(loaded.isPresent());
    }
    
    @Test
    void testGetLatestSnapshotReturnsNewest() throws Exception {
        String actorId = "actor-1";
        
        // Save multiple snapshots
        snapshotStore.saveSnapshot(actorId, new TestState("state-1", 1), 1).get();
        snapshotStore.saveSnapshot(actorId, new TestState("state-2", 2), 2).get();
        snapshotStore.saveSnapshot(actorId, new TestState("state-3", 3), 3).get();
        
        // Get latest
        Optional<SnapshotEntry<TestState>> loaded = snapshotStore.getLatestSnapshot(actorId).get();
        
        assertTrue(loaded.isPresent());
        assertEquals(3L, loaded.get().getSequenceNumber());
        assertEquals("state-3", loaded.get().getState().value);
    }
    
    @Test
    void testChecksumValidation() throws Exception {
        String actorId = "actor-1";
        TestState state = new TestState("test", 123);
        
        // Save snapshot
        snapshotStore.saveSnapshot(actorId, state, 1).get();
        
        // Load should succeed with valid checksum
        Optional<SnapshotEntry<TestState>> loaded = snapshotStore.getLatestSnapshot(actorId).get();
        assertTrue(loaded.isPresent());
        assertEquals("test", loaded.get().getState().value);
    }
    
    @Test
    void testDeleteSnapshots() throws Exception {
        String actorId = "actor-1";
        
        // Save snapshots
        snapshotStore.saveSnapshot(actorId, new TestState("state-1", 1), 1).get();
        snapshotStore.saveSnapshot(actorId, new TestState("state-2", 2), 2).get();
        
        // Verify they exist
        Optional<SnapshotEntry<TestState>> loaded = snapshotStore.getLatestSnapshot(actorId).get();
        assertTrue(loaded.isPresent());
        
        // Delete all snapshots
        snapshotStore.deleteSnapshots(actorId).get();
        
        // Verify they're gone
        loaded = snapshotStore.getLatestSnapshot(actorId).get();
        assertFalse(loaded.isPresent());
    }
    
    @Test
    void testListSnapshots() throws Exception {
        String actorId = "actor-1";
        
        // Save multiple snapshots
        for (int i = 1; i <= 5; i++) {
            snapshotStore.saveSnapshot(actorId, new TestState("state-" + i, i), i).get();
        }
        
        // List snapshots
        List<SnapshotMetadata> snapshots = snapshotStore.listSnapshots(actorId).get();
        
        assertEquals(5, snapshots.size());
        
        // Should be sorted in descending order
        assertEquals(5L, snapshots.get(0).getSequence());
        assertEquals(1L, snapshots.get(4).getSequence());
    }
    
    @Test
    void testPruneOldSnapshots() throws Exception {
        String actorId = "actor-1";
        
        // Save 10 snapshots
        for (int i = 1; i <= 10; i++) {
            snapshotStore.saveSnapshot(actorId, new TestState("state-" + i, i), i).get();
        }
        
        // Prune to keep only 3
        int deleted = snapshotStore.pruneOldSnapshots(actorId, 3).get();
        
        assertEquals(7, deleted);
        
        // Verify only 3 remain
        List<SnapshotMetadata> remaining = snapshotStore.listSnapshots(actorId).get();
        assertEquals(3, remaining.size());
        
        // Verify the most recent ones are kept
        assertEquals(10L, remaining.get(0).getSequence());
        assertEquals(9L, remaining.get(1).getSequence());
        assertEquals(8L, remaining.get(2).getSequence());
    }
    
    @Test
    void testMultipleActors() throws Exception {
        // Save snapshots for different actors
        snapshotStore.saveSnapshot("actor-1", new TestState("state-1", 1), 1).get();
        snapshotStore.saveSnapshot("actor-2", new TestState("state-2", 2), 2).get();
        
        // Load each actor's snapshot
        Optional<SnapshotEntry<TestState>> actor1 = snapshotStore.getLatestSnapshot("actor-1").get();
        Optional<SnapshotEntry<TestState>> actor2 = snapshotStore.getLatestSnapshot("actor-2").get();
        
        assertTrue(actor1.isPresent());
        assertTrue(actor2.isPresent());
        assertEquals("state-1", actor1.get().getState().value);
        assertEquals("state-2", actor2.get().getState().value);
    }
    
    @Test
    void testOverwriteSnapshot() throws Exception {
        String actorId = "actor-1";
        
        // Save initial snapshot
        snapshotStore.saveSnapshot(actorId, new TestState("old", 1), 1).get();
        
        // Overwrite with new snapshot at same sequence
        snapshotStore.saveSnapshot(actorId, new TestState("new", 2), 1).get();
        
        // Load should return the new one
        Optional<SnapshotEntry<TestState>> loaded = snapshotStore.getLatestSnapshot(actorId).get();
        assertTrue(loaded.isPresent());
        assertEquals("new", loaded.get().getState().value);
    }
    
    @Test
    void testConcurrentWrites() throws Exception {
        String actorId = "actor-1";
        int numThreads = 10;
        
        // Concurrent writes
        Thread[] threads = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            final int seq = i;
            threads[i] = new Thread(() -> {
                try {
                    snapshotStore.saveSnapshot(actorId, new TestState("state-" + seq, seq), seq).get();
                } catch (Exception e) {
                    fail("Concurrent write failed: " + e.getMessage());
                }
            });
            threads[i].start();
        }
        
        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Verify all snapshots were saved
        List<SnapshotMetadata> snapshots = snapshotStore.listSnapshots(actorId).get();
        assertEquals(numThreads, snapshots.size());
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
