package com.cajunsystems.persistence.scavenger;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ScavengerConfig.
 */
class ScavengerConfigTest {
    
    @Test
    void testDefaultBuilder() {
        ScavengerConfig config = ScavengerConfig.builder().build();
        
        assertFalse(config.isEnabled(), "Should be disabled by default");
        assertEquals(60, config.getScanIntervalMinutes());
        assertEquals(3, config.getSnapshotsToKeep());
        assertEquals(7, config.getJournalRetentionDays());
        assertEquals(30, config.getSnapshotRetentionDays());
        assertEquals(-1, config.getMaxActorStorageMB());
        assertFalse(config.hasStorageLimit());
        assertEquals(10, config.getBatchSize());
        assertEquals(Duration.ofMinutes(30), config.getScanTimeout());
    }
    
    @Test
    void testCustomConfiguration() {
        ScavengerConfig config = ScavengerConfig.builder()
            .enabled(true)
            .scanIntervalMinutes(30)
            .snapshotsToKeep(5)
            .journalRetentionDays(14)
            .snapshotRetentionDays(60)
            .maxActorStorageMB(500)
            .batchSize(20)
            .scanTimeout(Duration.ofMinutes(60))
            .build();
        
        assertTrue(config.isEnabled());
        assertEquals(30, config.getScanIntervalMinutes());
        assertEquals(5, config.getSnapshotsToKeep());
        assertEquals(14, config.getJournalRetentionDays());
        assertEquals(60, config.getSnapshotRetentionDays());
        assertEquals(500, config.getMaxActorStorageMB());
        assertTrue(config.hasStorageLimit());
        assertEquals(20, config.getBatchSize());
        assertEquals(Duration.ofMinutes(60), config.getScanTimeout());
    }
    
    @Test
    void testConservativeConfiguration() {
        ScavengerConfig config = ScavengerConfig.builder()
            .enabled(true)
            .scanIntervalMinutes(120)
            .snapshotsToKeep(5)
            .journalRetentionDays(30)
            .snapshotRetentionDays(90)
            .build();
        
        assertTrue(config.isEnabled());
        assertEquals(120, config.getScanIntervalMinutes());
        assertEquals(5, config.getSnapshotsToKeep());
        assertEquals(30, config.getJournalRetentionDays());
        assertEquals(90, config.getSnapshotRetentionDays());
    }
    
    @Test
    void testAggressiveConfiguration() {
        ScavengerConfig config = ScavengerConfig.builder()
            .enabled(true)
            .scanIntervalMinutes(15)
            .snapshotsToKeep(2)
            .journalRetentionDays(1)
            .snapshotRetentionDays(7)
            .maxActorStorageMB(100)
            .build();
        
        assertTrue(config.isEnabled());
        assertEquals(15, config.getScanIntervalMinutes());
        assertEquals(2, config.getSnapshotsToKeep());
        assertEquals(1, config.getJournalRetentionDays());
        assertEquals(7, config.getSnapshotRetentionDays());
        assertEquals(100, config.getMaxActorStorageMB());
        assertTrue(config.hasStorageLimit());
    }
    
    @Test
    void testInvalidScanInterval() {
        assertThrows(IllegalArgumentException.class, () -> 
            ScavengerConfig.builder()
                .scanIntervalMinutes(0)
                .build()
        );
        
        assertThrows(IllegalArgumentException.class, () -> 
            ScavengerConfig.builder()
                .scanIntervalMinutes(-1)
                .build()
        );
    }
    
    @Test
    void testInvalidSnapshotsToKeep() {
        assertThrows(IllegalArgumentException.class, () -> 
            ScavengerConfig.builder()
                .snapshotsToKeep(0)
                .build()
        );
        
        assertThrows(IllegalArgumentException.class, () -> 
            ScavengerConfig.builder()
                .snapshotsToKeep(-1)
                .build()
        );
    }
    
    @Test
    void testInvalidJournalRetentionDays() {
        assertThrows(IllegalArgumentException.class, () -> 
            ScavengerConfig.builder()
                .journalRetentionDays(-1)
                .build()
        );
    }
    
    @Test
    void testInvalidSnapshotRetentionDays() {
        assertThrows(IllegalArgumentException.class, () -> 
            ScavengerConfig.builder()
                .snapshotRetentionDays(-1)
                .build()
        );
    }
    
    @Test
    void testInvalidBatchSize() {
        assertThrows(IllegalArgumentException.class, () -> 
            ScavengerConfig.builder()
                .batchSize(0)
                .build()
        );
        
        assertThrows(IllegalArgumentException.class, () -> 
            ScavengerConfig.builder()
                .batchSize(-1)
                .build()
        );
    }
    
    @Test
    void testZeroRetentionDaysAllowed() {
        // Zero retention days should be allowed (means no time-based cleanup)
        ScavengerConfig config = ScavengerConfig.builder()
            .journalRetentionDays(0)
            .snapshotRetentionDays(0)
            .build();
        
        assertEquals(0, config.getJournalRetentionDays());
        assertEquals(0, config.getSnapshotRetentionDays());
    }
    
    @Test
    void testNoStorageLimit() {
        ScavengerConfig config = ScavengerConfig.builder()
            .maxActorStorageMB(-1)
            .build();
        
        assertEquals(-1, config.getMaxActorStorageMB());
        assertFalse(config.hasStorageLimit());
    }
    
    @Test
    void testToString() {
        ScavengerConfig config = ScavengerConfig.builder()
            .enabled(true)
            .scanIntervalMinutes(60)
            .snapshotsToKeep(3)
            .build();
        
        String str = config.toString();
        assertTrue(str.contains("enabled=true"));
        assertTrue(str.contains("scanIntervalMinutes=60"));
        assertTrue(str.contains("snapshotsToKeep=3"));
    }
}
