package com.cajunsystems.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TruncationConfig.
 */
class TruncationConfigTest {
    
    @Test
    void testDefaultConfiguration() {
        TruncationConfig config = TruncationConfig.builder().build();
        
        assertFalse(config.isSnapshotBasedTruncationEnabled(), 
            "Snapshot-based truncation should be disabled by default");
        assertFalse(config.isTruncateJournalOnSnapshot(), 
            "Journal truncation should be disabled by default");
        assertEquals(2, config.getSnapshotsToKeep(), 
            "Should keep 2 snapshots by default");
    }
    
    @Test
    void testCustomConfiguration() {
        TruncationConfig config = TruncationConfig.builder()
            .enableSnapshotBasedTruncation(true)
            .snapshotsToKeep(3)
            .truncateJournalOnSnapshot(true)
            .build();
        
        assertTrue(config.isSnapshotBasedTruncationEnabled());
        assertTrue(config.isTruncateJournalOnSnapshot());
        assertEquals(3, config.getSnapshotsToKeep());
    }
    
    @Test
    void testMinimalSnapshotRetention() {
        TruncationConfig config = TruncationConfig.builder()
            .snapshotsToKeep(1)
            .build();
        
        assertEquals(1, config.getSnapshotsToKeep());
    }
    
    @Test
    void testInvalidSnapshotRetentionInBuilder() {
        assertThrows(IllegalArgumentException.class, () -> {
            TruncationConfig.builder()
                .snapshotsToKeep(0)
                .build();
        }, "Should reject snapshotsToKeep = 0");
    }
    
    @Test
    void testNegativeSnapshotRetention() {
        assertThrows(IllegalArgumentException.class, () -> {
            TruncationConfig.builder()
                .snapshotsToKeep(-1)
                .build();
        }, "Should reject negative snapshotsToKeep");
    }
    
    @Test
    void testConservativeConfiguration() {
        // Production-recommended configuration
        TruncationConfig config = TruncationConfig.builder()
            .enableSnapshotBasedTruncation(true)
            .snapshotsToKeep(2)
            .truncateJournalOnSnapshot(true)
            .build();
        
        assertTrue(config.isSnapshotBasedTruncationEnabled());
        assertTrue(config.isTruncateJournalOnSnapshot());
        assertEquals(2, config.getSnapshotsToKeep());
    }
    
    @Test
    void testAggressiveConfiguration() {
        // Space-constrained configuration
        TruncationConfig config = TruncationConfig.builder()
            .enableSnapshotBasedTruncation(true)
            .snapshotsToKeep(1)
            .truncateJournalOnSnapshot(true)
            .build();
        
        assertTrue(config.isSnapshotBasedTruncationEnabled());
        assertTrue(config.isTruncateJournalOnSnapshot());
        assertEquals(1, config.getSnapshotsToKeep());
    }
    
    @Test
    void testToString() {
        TruncationConfig config = TruncationConfig.builder()
            .enableSnapshotBasedTruncation(true)
            .snapshotsToKeep(2)
            .truncateJournalOnSnapshot(true)
            .build();
        
        String str = config.toString();
        assertTrue(str.contains("snapshotBasedTruncationEnabled=true"));
        assertTrue(str.contains("snapshotsToKeep=2"));
        assertTrue(str.contains("truncateJournalOnSnapshot=true"));
    }
    
    @Test
    void testDefaultConstant() {
        TruncationConfig config = TruncationConfig.DEFAULT;
        
        assertNotNull(config, "DEFAULT should not be null");
        assertTrue(config.isSnapshotBasedTruncationEnabled(), 
            "DEFAULT should have snapshot truncation enabled");
        assertEquals(3, config.getSnapshotsToKeep(), 
            "DEFAULT should keep 3 snapshots");
        assertTrue(config.isTruncateJournalOnSnapshot(), 
            "DEFAULT should have journal truncation enabled");
    }
    
    @Test
    void testDisabledConstant() {
        TruncationConfig config = TruncationConfig.DISABLED;
        
        assertNotNull(config, "DISABLED should not be null");
        assertFalse(config.isSnapshotBasedTruncationEnabled(), 
            "DISABLED should have snapshot truncation disabled");
        assertFalse(config.isTruncateJournalOnSnapshot(), 
            "DISABLED should have journal truncation disabled");
    }
}
