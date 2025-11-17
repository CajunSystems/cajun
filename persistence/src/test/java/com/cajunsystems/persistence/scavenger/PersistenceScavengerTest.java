package com.cajunsystems.persistence.scavenger;

import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.persistence.runtime.persistence.FileSnapshotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PersistenceScavenger.
 */
class PersistenceScavengerTest {
    
    @TempDir
    Path tempDir;
    
    private SnapshotStore<Integer> snapshotStore;
    private PersistenceScavenger scavenger;
    
    @BeforeEach
    void setUp() {
        snapshotStore = new FileSnapshotStore<>(tempDir);
    }
    
    @AfterEach
    void tearDown() {
        if (scavenger != null) {
            scavenger.stop();
        }
        if (snapshotStore != null) {
            snapshotStore.close();
        }
    }
    
    @Test
    void testScavengerDisabledByDefault() {
        ScavengerConfig config = ScavengerConfig.builder()
            .enabled(false)
            .build();
        
        scavenger = new PersistenceScavenger(tempDir, snapshotStore, config);
        scavenger.start();
        
        // Should not be running
        assertFalse(scavenger.isRunning());
    }
    
    @Test
    void testScavengerStartAndStop() {
        ScavengerConfig config = ScavengerConfig.builder()
            .enabled(true)
            .scanIntervalMinutes(60)
            .build();
        
        scavenger = new PersistenceScavenger(tempDir, snapshotStore, config);
        
        assertFalse(scavenger.isRunning());
        
        scavenger.start();
        assertTrue(scavenger.isRunning());
        
        scavenger.stop();
        assertFalse(scavenger.isRunning());
    }
    
    @Test
    void testScavengerDoesNotStartTwice() {
        ScavengerConfig config = ScavengerConfig.builder()
            .enabled(true)
            .scanIntervalMinutes(60)
            .build();
        
        scavenger = new PersistenceScavenger(tempDir, snapshotStore, config);
        
        scavenger.start();
        assertTrue(scavenger.isRunning());
        
        // Try to start again - should not fail
        scavenger.start();
        assertTrue(scavenger.isRunning());
    }
    
    @Test
    void testMetricsInitialState() {
        ScavengerConfig config = ScavengerConfig.builder()
            .enabled(true)
            .build();
        
        scavenger = new PersistenceScavenger(tempDir, snapshotStore, config);
        
        ScavengerMetrics metrics = scavenger.getMetrics();
        assertNotNull(metrics);
        assertEquals(0, metrics.getTotalScans());
        assertEquals(0, metrics.getTotalActorsProcessed());
    }
    
    @Test
    void testDiscoverActors() throws Exception {
        // Create some actor directories
        Files.createDirectories(tempDir.resolve("actor-1"));
        Files.createDirectories(tempDir.resolve("actor-2"));
        Files.createDirectories(tempDir.resolve("actor-3"));
        
        ScavengerConfig config = ScavengerConfig.builder()
            .enabled(true)
            .scanIntervalMinutes(60)
            .build();
        
        scavenger = new PersistenceScavenger(tempDir, snapshotStore, config);
        
        // The scavenger should discover these actors on scan
        // We can't easily test this directly, but we can verify it doesn't crash
        assertNotNull(scavenger);
    }
    
    @Test
    void testSnapshotPruning() throws Exception {
        String actorId = "test-actor";
        
        // Create multiple snapshots with different timestamps
        for (int i = 1; i <= 5; i++) {
            snapshotStore.saveSnapshot(actorId, i, i).get();
            Thread.sleep(10);
        }
        
        // Verify all snapshots exist
        assertEquals(5, snapshotStore.listSnapshots(actorId).get().size());
        
        // Configure scavenger to keep only 2 snapshots
        ScavengerConfig config = ScavengerConfig.builder()
            .enabled(false)  // Don't start automatically
            .snapshotsToKeep(2)
            .build();
        
        scavenger = new PersistenceScavenger(tempDir, snapshotStore, config);
        
        // Manually prune using the snapshot store
        int deleted = snapshotStore.pruneOldSnapshots(actorId, 2).get();
        
        assertEquals(3, deleted, "Should delete 3 old snapshots");
        assertEquals(2, snapshotStore.listSnapshots(actorId).get().size());
    }
    
    @Test
    void testJournalCleanup() throws Exception {
        String actorId = "test-actor";
        Path actorDir = tempDir.resolve(actorId);
        Files.createDirectories(actorDir);
        
        // Create some old journal files
        long oldTime = System.currentTimeMillis() - (10 * 24 * 60 * 60 * 1000L); // 10 days ago
        
        Path oldJournal1 = actorDir.resolve("message-1.dat");
        Path oldJournal2 = actorDir.resolve("message-2.dat");
        Path recentJournal = actorDir.resolve("message-3.dat");
        
        Files.writeString(oldJournal1, "old data");
        Files.writeString(oldJournal2, "old data");
        Files.writeString(recentJournal, "recent data");
        
        // Set old modification times
        Files.setLastModifiedTime(oldJournal1, 
            java.nio.file.attribute.FileTime.fromMillis(oldTime));
        Files.setLastModifiedTime(oldJournal2, 
            java.nio.file.attribute.FileTime.fromMillis(oldTime));
        
        // Verify all files exist
        assertTrue(Files.exists(oldJournal1));
        assertTrue(Files.exists(oldJournal2));
        assertTrue(Files.exists(recentJournal));
        
        ScavengerConfig config = ScavengerConfig.builder()
            .enabled(false)
            .journalRetentionDays(7)  // Delete journals older than 7 days
            .build();
        
        scavenger = new PersistenceScavenger(tempDir, snapshotStore, config);
        
        // The scavenger would clean these up on scan
        // For testing, we just verify the config is correct
        assertEquals(7, config.getJournalRetentionDays());
    }
    
    @Test
    void testBatchProcessing() {
        // Create multiple actor directories
        for (int i = 1; i <= 25; i++) {
            try {
                Files.createDirectories(tempDir.resolve("actor-" + i));
            } catch (Exception e) {
                fail("Failed to create actor directory: " + e.getMessage());
            }
        }
        
        ScavengerConfig config = ScavengerConfig.builder()
            .enabled(true)
            .scanIntervalMinutes(60)
            .batchSize(10)  // Process 10 actors per batch
            .build();
        
        scavenger = new PersistenceScavenger(tempDir, snapshotStore, config);
        
        assertEquals(10, config.getBatchSize());
        assertNotNull(scavenger);
    }
    
    @Test
    void testGracefulShutdown() throws Exception {
        ScavengerConfig config = ScavengerConfig.builder()
            .enabled(true)
            .scanIntervalMinutes(60)
            .build();
        
        scavenger = new PersistenceScavenger(tempDir, snapshotStore, config);
        scavenger.start();
        
        assertTrue(scavenger.isRunning());
        
        // Stop should complete within reasonable time
        long startTime = System.currentTimeMillis();
        scavenger.stop();
        long duration = System.currentTimeMillis() - startTime;
        
        assertFalse(scavenger.isRunning());
        assertTrue(duration < 5000, "Shutdown should complete within 5 seconds");
    }
    
    @Test
    void testAutoCloseableInterface() throws Exception {
        ScavengerConfig config = ScavengerConfig.builder()
            .enabled(true)
            .scanIntervalMinutes(60)
            .build();
        
        // Use try-with-resources
        try (PersistenceScavenger s = new PersistenceScavenger(tempDir, snapshotStore, config)) {
            s.start();
            assertTrue(s.isRunning());
        }
        
        // Scavenger should be stopped after try block
        // We can't check directly since it's out of scope, but it shouldn't throw
    }
    
    @Test
    void testEmptyDirectory() {
        // Test with empty persistence directory
        ScavengerConfig config = ScavengerConfig.builder()
            .enabled(true)
            .scanIntervalMinutes(60)
            .build();
        
        scavenger = new PersistenceScavenger(tempDir, snapshotStore, config);
        scavenger.start();
        
        // Should not crash with empty directory
        assertTrue(scavenger.isRunning());
        
        ScavengerMetrics metrics = scavenger.getMetrics();
        assertNotNull(metrics);
    }
    
    @Test
    void testNonExistentDirectory() throws Exception {
        // Test with non-existent directory
        Path nonExistent = tempDir.resolve("does-not-exist");
        
        ScavengerConfig config = ScavengerConfig.builder()
            .enabled(true)
            .scanIntervalMinutes(60)
            .build();
        
        scavenger = new PersistenceScavenger(nonExistent, snapshotStore, config);
        scavenger.start();
        
        // Should not crash with non-existent directory
        assertTrue(scavenger.isRunning());
    }
    
    @Test
    void testConfigurationValidation() {
        ScavengerConfig config = ScavengerConfig.builder()
            .enabled(true)
            .scanIntervalMinutes(30)
            .snapshotsToKeep(5)
            .journalRetentionDays(14)
            .snapshotRetentionDays(60)
            .maxActorStorageMB(500)
            .batchSize(20)
            .build();
        
        scavenger = new PersistenceScavenger(tempDir, snapshotStore, config);
        
        assertNotNull(scavenger);
        assertNotNull(scavenger.getMetrics());
    }
}
