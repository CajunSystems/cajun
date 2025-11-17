package com.cajunsystems.persistence.runtime.persistence;

import com.cajunsystems.persistence.SnapshotEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FileSnapshotStore checksum and atomic write features.
 */
class FileSnapshotStoreChecksumTest {
    
    @TempDir
    Path tempDir;
    
    private FileSnapshotStore<TestState> snapshotStore;
    
    @BeforeEach
    void setUp() {
        snapshotStore = new FileSnapshotStore<>(tempDir);
    }
    
    @AfterEach
    void tearDown() {
        if (snapshotStore != null) {
            snapshotStore.close();
        }
    }
    
    @Test
    void testAtomicWrite() throws Exception {
        String actorId = "actor-1";
        TestState state = new TestState("test-value", 42);
        
        // Save snapshot
        snapshotStore.saveSnapshot(actorId, state, 1).get();
        
        // Verify no .tmp files remain
        Path actorDir = tempDir.resolve(actorId);
        long tmpFileCount = Files.list(actorDir)
            .filter(p -> p.getFileName().toString().endsWith(".tmp"))
            .count();
        
        assertEquals(0, tmpFileCount, "No temporary files should remain after atomic write");
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
        assertEquals(123, loaded.get().getState().count);
    }
    
    @Test
    void testCorruptedSnapshotDetection() throws Exception {
        String actorId = "actor-1";
        TestState state = new TestState("original", 100);
        
        // Save snapshot
        snapshotStore.saveSnapshot(actorId, state, 1).get();
        
        // Find the snapshot file
        Path actorDir = tempDir.resolve(actorId);
        Path snapshotFile = Files.list(actorDir)
            .filter(p -> p.getFileName().toString().endsWith(".snap"))
            .findFirst()
            .orElseThrow();
        
        // Corrupt the file by modifying bytes
        byte[] data = Files.readAllBytes(snapshotFile);
        if (data.length > 20) {
            data[20] = (byte) (data[20] ^ 0xFF); // Flip bits
            Files.write(snapshotFile, data);
        }
        
        // Loading should fail due to checksum mismatch
        assertThrows(Exception.class, () -> {
            snapshotStore.getLatestSnapshot(actorId).get();
        });
    }
    
    @Test
    void testOverwriteWithAtomicWrite() throws Exception {
        String actorId = "actor-1";
        
        // Save initial snapshot
        snapshotStore.saveSnapshot(actorId, new TestState("old", 1), 1).get();
        
        // Overwrite with new snapshot
        snapshotStore.saveSnapshot(actorId, new TestState("new", 2), 1).get();
        
        // Load should return the new one
        Optional<SnapshotEntry<TestState>> loaded = snapshotStore.getLatestSnapshot(actorId).get();
        
        assertTrue(loaded.isPresent());
        assertEquals("new", loaded.get().getState().value);
        assertEquals(2, loaded.get().getState().count);
    }
    
    @Test
    void testConcurrentWrites() throws Exception {
        String actorId = "actor-1";
        int numThreads = 10;
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        // Concurrent writes
        Thread[] threads = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            final int seq = i;
            threads[i] = new Thread(() -> {
                try {
                    snapshotStore.saveSnapshot(actorId, new TestState("state-" + seq, seq), seq).get();
                } catch (Exception e) {
                    fail("Concurrent write failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
            threads[i].start();
        }
        
        // Wait for all threads
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        
        // Latest snapshot should be valid
        Optional<SnapshotEntry<TestState>> loaded = snapshotStore.getLatestSnapshot(actorId).get();
        assertTrue(loaded.isPresent());
        
        // Verify checksum is valid (no corruption from concurrent writes)
        assertNotNull(loaded.get().getState());
    }
    
    @Test
    void testDedicatedIOExecutor() throws Exception {
        String actorId = "actor-1";
        
        // Save many snapshots to test executor
        for (int i = 0; i < 50; i++) {
            snapshotStore.saveSnapshot(actorId, new TestState("state-" + i, i), i);
        }
        
        // Wait for completion
        Thread.sleep(500);
        
        // Latest should be valid
        Optional<SnapshotEntry<TestState>> loaded = snapshotStore.getLatestSnapshot(actorId).get();
        assertTrue(loaded.isPresent());
    }
    
    @Test
    void testCloseReleasesResources() throws Exception {
        String actorId = "actor-1";
        
        // Save snapshot
        snapshotStore.saveSnapshot(actorId, new TestState("test", 1), 1).get();
        
        // Close store
        snapshotStore.close();
        
        // Further operations should fail
        assertThrows(Exception.class, () -> {
            snapshotStore.saveSnapshot(actorId, new TestState("test2", 2), 2).get();
        });
    }
    
    @Test
    void testRecoveryAfterRestart() throws Exception {
        String actorId = "actor-1";
        
        // Save snapshots
        snapshotStore.saveSnapshot(actorId, new TestState("state-1", 1), 1).get();
        snapshotStore.saveSnapshot(actorId, new TestState("state-2", 2), 2).get();
        
        // Close store
        snapshotStore.close();
        
        // Create new store instance (simulating restart)
        FileSnapshotStore<TestState> newStore = new FileSnapshotStore<>(tempDir);
        
        try {
            // Should be able to read old snapshots
            Optional<SnapshotEntry<TestState>> loaded = newStore.getLatestSnapshot(actorId).get();
            
            assertTrue(loaded.isPresent());
            assertEquals(2L, loaded.get().getSequenceNumber());
            assertEquals("state-2", loaded.get().getState().value);
        } finally {
            newStore.close();
        }
    }
    
    @Test
    void testLargeStateWithChecksum() throws Exception {
        String actorId = "actor-1";
        
        // Create large state
        StringBuilder largeString = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            largeString.append("Large state data ").append(i).append(" ");
        }
        
        TestState largeState = new TestState(largeString.toString(), 999);
        
        // Save and load
        snapshotStore.saveSnapshot(actorId, largeState, 1).get();
        Optional<SnapshotEntry<TestState>> loaded = snapshotStore.getLatestSnapshot(actorId).get();
        
        assertTrue(loaded.isPresent());
        assertEquals(largeState.value, loaded.get().getState().value);
        assertEquals(largeState.count, loaded.get().getState().count);
    }
    
    @Test
    void testMultipleActorsIsolation() throws Exception {
        // Save snapshots for different actors
        snapshotStore.saveSnapshot("actor-1", new TestState("state-1", 1), 1).get();
        snapshotStore.saveSnapshot("actor-2", new TestState("state-2", 2), 2).get();
        
        // Corrupt actor-1's snapshot
        Path actor1Dir = tempDir.resolve("actor-1");
        Path snapshotFile = Files.list(actor1Dir)
            .filter(p -> p.getFileName().toString().endsWith(".snap"))
            .findFirst()
            .orElseThrow();
        
        byte[] data = Files.readAllBytes(snapshotFile);
        if (data.length > 20) {
            data[20] = (byte) (data[20] ^ 0xFF);
            Files.write(snapshotFile, data);
        }
        
        // Actor-1 should fail
        assertThrows(Exception.class, () -> {
            snapshotStore.getLatestSnapshot("actor-1").get();
        });
        
        // Actor-2 should still work
        Optional<SnapshotEntry<TestState>> actor2 = snapshotStore.getLatestSnapshot("actor-2").get();
        assertTrue(actor2.isPresent());
        assertEquals("state-2", actor2.get().getState().value);
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
