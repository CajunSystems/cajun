package com.cajunsystems.persistence.versioning;

import com.cajunsystems.persistence.SnapshotEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class VersionedSnapshotEntryTest {
    
    @Test
    void testCreateFromSnapshotEntry() {
        SnapshotEntry<Integer> entry = new SnapshotEntry<>("actor-1", 42, 10L);
        VersionedSnapshotEntry<Integer> versioned = new VersionedSnapshotEntry<>(2, entry);
        
        assertEquals(2, versioned.getVersion());
        assertEquals("actor-1", versioned.getActorId());
        assertEquals(42, versioned.getState());
        assertEquals(10L, versioned.getSequenceNumber());
        assertNotNull(versioned.getTimestamp());
    }
    
    @Test
    void testCreateWithAllParameters() {
        Instant now = Instant.now();
        VersionedSnapshotEntry<String> versioned = new VersionedSnapshotEntry<>(
            3, "actor-2", "state-data", 20L, now
        );
        
        assertEquals(3, versioned.getVersion());
        assertEquals("actor-2", versioned.getActorId());
        assertEquals("state-data", versioned.getState());
        assertEquals(20L, versioned.getSequenceNumber());
        assertEquals(now, versioned.getTimestamp());
    }
    
    @Test
    void testCreateWithCurrentTimestamp() {
        Instant before = Instant.now();
        VersionedSnapshotEntry<Double> versioned = new VersionedSnapshotEntry<>(
            1, "actor-3", 3.14, 5L
        );
        Instant after = Instant.now();
        
        assertEquals(1, versioned.getVersion());
        assertTrue(versioned.getTimestamp().isAfter(before) || versioned.getTimestamp().equals(before));
        assertTrue(versioned.getTimestamp().isBefore(after) || versioned.getTimestamp().equals(after));
    }
    
    @Test
    void testNegativeVersionThrowsException() {
        SnapshotEntry<String> entry = new SnapshotEntry<>("actor-1", "state", 1L);
        
        assertThrows(IllegalArgumentException.class, () -> {
            new VersionedSnapshotEntry<>(-1, entry);
        });
    }
    
    @Test
    void testZeroVersionIsValid() {
        SnapshotEntry<String> entry = new SnapshotEntry<>("actor-1", "state", 1L);
        VersionedSnapshotEntry<String> versioned = new VersionedSnapshotEntry<>(0, entry);
        
        assertEquals(0, versioned.getVersion());
    }
    
    @Test
    void testNeedsMigration() {
        SnapshotEntry<String> entry = new SnapshotEntry<>("actor-1", "state", 1L);
        VersionedSnapshotEntry<String> versioned = new VersionedSnapshotEntry<>(1, entry);
        
        assertFalse(versioned.needsMigration(1));
        assertTrue(versioned.needsMigration(2));
        assertTrue(versioned.needsMigration(3));
        assertFalse(versioned.needsMigration(0));
    }
    
    @Test
    void testWithMigratedState() {
        SnapshotEntry<String> entry = new SnapshotEntry<>("actor-1", "old-state", 5L);
        VersionedSnapshotEntry<String> versioned = new VersionedSnapshotEntry<>(1, entry);
        
        VersionedSnapshotEntry<String> migrated = versioned.withMigratedState("new-state", 2);
        
        assertEquals(2, migrated.getVersion());
        assertEquals("new-state", migrated.getState());
        assertEquals(5L, migrated.getSequenceNumber());
        assertEquals("actor-1", migrated.getActorId());
        assertEquals(versioned.getTimestamp(), migrated.getTimestamp());
        
        // Original should be unchanged
        assertEquals(1, versioned.getVersion());
        assertEquals("old-state", versioned.getState());
    }
    
    @Test
    void testToPlainEntry() {
        VersionedSnapshotEntry<Integer> versioned = new VersionedSnapshotEntry<>(
            2, "actor-1", 100, 15L
        );
        
        SnapshotEntry<Integer> plain = versioned.toPlainEntry();
        
        assertEquals("actor-1", plain.getActorId());
        assertEquals(100, plain.getState());
        assertEquals(15L, plain.getSequenceNumber());
        assertEquals(versioned.getTimestamp(), plain.getTimestamp());
        
        // Plain entry should not be a VersionedSnapshotEntry
        assertFalse(plain instanceof VersionedSnapshotEntry);
    }
    
    @Test
    void testToString() {
        VersionedSnapshotEntry<String> versioned = new VersionedSnapshotEntry<>(
            2, "actor-1", "test-state", 10L
        );
        
        String str = versioned.toString();
        
        assertTrue(str.contains("version=2"));
        assertTrue(str.contains("actorId='actor-1'"));
        assertTrue(str.contains("state=test-state"));
        assertTrue(str.contains("sequenceNumber=10"));
    }
    
    
    @Test
    void testSerializability() {
        // VersionedSnapshotEntry should be serializable since it extends SnapshotEntry
        SnapshotEntry<String> entry = new SnapshotEntry<>("actor-1", "state", 1L);
        VersionedSnapshotEntry<String> versioned = new VersionedSnapshotEntry<>(2, entry);
        
        // This test verifies the class structure is correct for serialization
        assertTrue(versioned instanceof java.io.Serializable);
    }
    
    @Test
    void testNullState() {
        // Test that null state is handled correctly
        VersionedSnapshotEntry<String> versioned = new VersionedSnapshotEntry<>(
            1, "actor-1", null, 0L
        );
        
        assertNull(versioned.getState());
        assertEquals(1, versioned.getVersion());
    }
}
