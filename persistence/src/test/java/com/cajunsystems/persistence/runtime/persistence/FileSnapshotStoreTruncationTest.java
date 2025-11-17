package com.cajunsystems.persistence.runtime.persistence;

import com.cajunsystems.persistence.SnapshotMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FileSnapshotStore truncation functionality.
 */
class FileSnapshotStoreTruncationTest {
    
    @TempDir
    Path tempDir;
    
    private FileSnapshotStore<TestState> snapshotStore;
    
    @BeforeEach
    void setUp() {
        snapshotStore = new FileSnapshotStore<>(tempDir);
    }
    
    @AfterEach
    void tearDown() {
        snapshotStore.close();
    }
    
    @Test
    void testListSnapshotsWhenEmpty() throws Exception {
        List<SnapshotMetadata> snapshots = snapshotStore.listSnapshots("actor-1").get();
        
        assertTrue(snapshots.isEmpty(), "Should return empty list when no snapshots exist");
    }
    
    @Test
    void testListSnapshotsWhenActorDirectoryDoesNotExist() throws Exception {
        List<SnapshotMetadata> snapshots = snapshotStore.listSnapshots("non-existent-actor").get();
        
        assertTrue(snapshots.isEmpty(), "Should return empty list when actor directory doesn't exist");
    }
    
    @Test
    void testListSnapshotsReturnsSortedBySequence() throws Exception {
        String actorId = "actor-1";
        
        // Create 5 snapshots
        for (int i = 1; i <= 5; i++) {
            snapshotStore.saveSnapshot(actorId, new TestState("state-" + i), i).get();
            Thread.sleep(10); // Ensure different timestamps
        }
        
        List<SnapshotMetadata> snapshots = snapshotStore.listSnapshots(actorId).get();
        
        assertEquals(5, snapshots.size());
        
        // Verify sorted in descending order (most recent first)
        assertEquals(5L, snapshots.get(0).getSequence());
        assertEquals(4L, snapshots.get(1).getSequence());
        assertEquals(3L, snapshots.get(2).getSequence());
        assertEquals(2L, snapshots.get(3).getSequence());
        assertEquals(1L, snapshots.get(4).getSequence());
    }
    
    @Test
    void testListSnapshotsIncludesFileSize() throws Exception {
        String actorId = "actor-1";
        
        snapshotStore.saveSnapshot(actorId, new TestState("test"), 1).get();
        
        List<SnapshotMetadata> snapshots = snapshotStore.listSnapshots(actorId).get();
        
        assertEquals(1, snapshots.size());
        assertTrue(snapshots.get(0).hasFileSize(), "Should include file size");
        assertTrue(snapshots.get(0).getFileSize() > 0, "File size should be positive");
    }
    
    @Test
    void testPruneOldSnapshotsWhenNoSnapshotsExist() throws Exception {
        int deleted = snapshotStore.pruneOldSnapshots("actor-1", 2).get();
        
        assertEquals(0, deleted, "Should delete 0 snapshots when none exist");
    }
    
    @Test
    void testPruneOldSnapshotsWhenFewerThanKeepCount() throws Exception {
        String actorId = "actor-1";
        
        // Create 2 snapshots
        snapshotStore.saveSnapshot(actorId, new TestState("state-1"), 1).get();
        snapshotStore.saveSnapshot(actorId, new TestState("state-2"), 2).get();
        
        int deleted = snapshotStore.pruneOldSnapshots(actorId, 3).get();
        
        assertEquals(0, deleted, "Should delete 0 snapshots when count is below threshold");
        
        List<SnapshotMetadata> remaining = snapshotStore.listSnapshots(actorId).get();
        assertEquals(2, remaining.size(), "All snapshots should remain");
    }
    
    @Test
    void testPruneOldSnapshotsKeepsMostRecent() throws Exception {
        String actorId = "actor-1";
        
        // Create 5 snapshots
        for (int i = 1; i <= 5; i++) {
            snapshotStore.saveSnapshot(actorId, new TestState("state-" + i), i).get();
            Thread.sleep(10); // Ensure different timestamps
        }
        
        int deleted = snapshotStore.pruneOldSnapshots(actorId, 2).get();
        
        assertEquals(3, deleted, "Should delete 3 old snapshots");
        
        List<SnapshotMetadata> remaining = snapshotStore.listSnapshots(actorId).get();
        assertEquals(2, remaining.size(), "Should keep 2 most recent snapshots");
        
        // Verify the most recent ones are kept
        assertEquals(5L, remaining.get(0).getSequence());
        assertEquals(4L, remaining.get(1).getSequence());
    }
    
    @Test
    void testPruneOldSnapshotsKeepOne() throws Exception {
        String actorId = "actor-1";
        
        // Create 10 snapshots
        for (int i = 1; i <= 10; i++) {
            snapshotStore.saveSnapshot(actorId, new TestState("state-" + i), i).get();
            Thread.sleep(10);
        }
        
        int deleted = snapshotStore.pruneOldSnapshots(actorId, 1).get();
        
        assertEquals(9, deleted, "Should delete 9 old snapshots");
        
        List<SnapshotMetadata> remaining = snapshotStore.listSnapshots(actorId).get();
        assertEquals(1, remaining.size(), "Should keep only 1 snapshot");
        assertEquals(10L, remaining.get(0).getSequence(), "Should keep the most recent");
    }
    
    @Test
    void testPruneOldSnapshotsWithInvalidKeepCount() {
        CompletableFuture<Integer> future = snapshotStore.pruneOldSnapshots("actor-1", 0);
        
        assertThrows(ExecutionException.class, future::get, 
            "Should fail with keepCount = 0");
    }
    
    @Test
    void testPruneOldSnapshotsWithNegativeKeepCount() {
        CompletableFuture<Integer> future = snapshotStore.pruneOldSnapshots("actor-1", -1);
        
        assertThrows(ExecutionException.class, future::get, 
            "Should fail with negative keepCount");
    }
    
    @Test
    void testPruneOldSnapshotsMultipleActors() throws Exception {
        // Create snapshots for multiple actors
        for (int i = 1; i <= 5; i++) {
            snapshotStore.saveSnapshot("actor-1", new TestState("state-" + i), i).get();
            snapshotStore.saveSnapshot("actor-2", new TestState("state-" + i), i).get();
            Thread.sleep(10);
        }
        
        // Prune actor-1
        int deleted1 = snapshotStore.pruneOldSnapshots("actor-1", 2).get();
        assertEquals(3, deleted1);
        
        // Verify actor-2 is unaffected
        List<SnapshotMetadata> actor2Snapshots = snapshotStore.listSnapshots("actor-2").get();
        assertEquals(5, actor2Snapshots.size(), "Actor-2 snapshots should be unaffected");
    }
    
    @Test
    void testPruneOldSnapshotsVerifyFilesDeleted() throws Exception {
        String actorId = "actor-1";
        
        // Create 5 snapshots
        for (int i = 1; i <= 5; i++) {
            snapshotStore.saveSnapshot(actorId, new TestState("state-" + i), i).get();
            Thread.sleep(10);
        }
        
        // Count files before pruning
        Path actorDir = tempDir.resolve(actorId);
        long fileCountBefore = Files.list(actorDir)
            .filter(p -> p.getFileName().toString().endsWith(".snap"))
            .count();
        assertEquals(5, fileCountBefore);
        
        // Prune
        snapshotStore.pruneOldSnapshots(actorId, 2).get();
        
        // Count files after pruning
        long fileCountAfter = Files.list(actorDir)
            .filter(p -> p.getFileName().toString().endsWith(".snap"))
            .count();
        assertEquals(2, fileCountAfter, "Should have 2 snapshot files remaining");
    }
    
    @Test
    void testRecoveryStillWorksAfterPruning() throws Exception {
        String actorId = "actor-1";
        
        // Create 5 snapshots
        for (int i = 1; i <= 5; i++) {
            snapshotStore.saveSnapshot(actorId, new TestState("state-" + i), i).get();
            Thread.sleep(10);
        }
        
        // Prune to keep only 1
        snapshotStore.pruneOldSnapshots(actorId, 1).get();
        
        // Verify recovery still works
        var latestSnapshot = snapshotStore.getLatestSnapshot(actorId).get();
        assertTrue(latestSnapshot.isPresent(), "Should be able to recover latest snapshot");
        assertEquals(5L, latestSnapshot.get().getSequenceNumber(), 
            "Should recover the most recent snapshot");
        assertEquals("state-5", latestSnapshot.get().getState().value);
    }
    
    /**
     * Test state class for serialization.
     */
    static class TestState implements Serializable {
        private static final long serialVersionUID = 1L;
        final String value;
        
        TestState(String value) {
            this.value = value;
        }
    }
}
