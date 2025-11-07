package com.cajunsystems.persistence.versioning;

import com.cajunsystems.persistence.JournalEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class VersionedJournalEntryTest {
    
    @Test
    void testCreateFromJournalEntry() {
        JournalEntry<String> entry = new JournalEntry<>(1L, "actor-1", "test-message");
        VersionedJournalEntry<String> versioned = new VersionedJournalEntry<>(2, entry);
        
        assertEquals(2, versioned.getVersion());
        assertEquals(1L, versioned.getSequenceNumber());
        assertEquals("actor-1", versioned.getActorId());
        assertEquals("test-message", versioned.getMessage());
        assertNotNull(versioned.getTimestamp());
    }
    
    @Test
    void testCreateWithAllParameters() {
        Instant now = Instant.now();
        VersionedJournalEntry<String> versioned = new VersionedJournalEntry<>(
            3, 10L, "actor-2", "message", now
        );
        
        assertEquals(3, versioned.getVersion());
        assertEquals(10L, versioned.getSequenceNumber());
        assertEquals("actor-2", versioned.getActorId());
        assertEquals("message", versioned.getMessage());
        assertEquals(now, versioned.getTimestamp());
    }
    
    @Test
    void testCreateWithCurrentTimestamp() {
        Instant before = Instant.now();
        VersionedJournalEntry<String> versioned = new VersionedJournalEntry<>(
            1, 5L, "actor-3", "msg"
        );
        Instant after = Instant.now();
        
        assertEquals(1, versioned.getVersion());
        assertTrue(versioned.getTimestamp().isAfter(before) || versioned.getTimestamp().equals(before));
        assertTrue(versioned.getTimestamp().isBefore(after) || versioned.getTimestamp().equals(after));
    }
    
    @Test
    void testNegativeVersionThrowsException() {
        JournalEntry<String> entry = new JournalEntry<>(1L, "actor-1", "message");
        
        assertThrows(IllegalArgumentException.class, () -> {
            new VersionedJournalEntry<>(-1, entry);
        });
    }
    
    @Test
    void testZeroVersionIsValid() {
        JournalEntry<String> entry = new JournalEntry<>(1L, "actor-1", "message");
        VersionedJournalEntry<String> versioned = new VersionedJournalEntry<>(0, entry);
        
        assertEquals(0, versioned.getVersion());
    }
    
    @Test
    void testNeedsMigration() {
        JournalEntry<String> entry = new JournalEntry<>(1L, "actor-1", "message");
        VersionedJournalEntry<String> versioned = new VersionedJournalEntry<>(1, entry);
        
        assertFalse(versioned.needsMigration(1));
        assertTrue(versioned.needsMigration(2));
        assertTrue(versioned.needsMigration(3));
        assertFalse(versioned.needsMigration(0));
    }
    
    @Test
    void testWithMigratedMessage() {
        JournalEntry<String> entry = new JournalEntry<>(1L, "actor-1", "old-message");
        VersionedJournalEntry<String> versioned = new VersionedJournalEntry<>(1, entry);
        
        VersionedJournalEntry<String> migrated = versioned.withMigratedMessage("new-message", 2);
        
        assertEquals(2, migrated.getVersion());
        assertEquals("new-message", migrated.getMessage());
        assertEquals(1L, migrated.getSequenceNumber());
        assertEquals("actor-1", migrated.getActorId());
        assertEquals(versioned.getTimestamp(), migrated.getTimestamp());
        
        // Original should be unchanged
        assertEquals(1, versioned.getVersion());
        assertEquals("old-message", versioned.getMessage());
    }
    
    @Test
    void testToPlainEntry() {
        VersionedJournalEntry<String> versioned = new VersionedJournalEntry<>(
            2, 5L, "actor-1", "message"
        );
        
        JournalEntry<String> plain = versioned.toPlainEntry();
        
        assertEquals(5L, plain.getSequenceNumber());
        assertEquals("actor-1", plain.getActorId());
        assertEquals("message", plain.getMessage());
        assertEquals(versioned.getTimestamp(), plain.getTimestamp());
        
        // Plain entry should not be a VersionedJournalEntry
        assertFalse(plain instanceof VersionedJournalEntry);
    }
    
    @Test
    void testToString() {
        VersionedJournalEntry<String> versioned = new VersionedJournalEntry<>(
            2, 5L, "actor-1", "test"
        );
        
        String str = versioned.toString();
        
        assertTrue(str.contains("version=2"));
        assertTrue(str.contains("sequenceNumber=5"));
        assertTrue(str.contains("actorId='actor-1'"));
        assertTrue(str.contains("message=test"));
    }
    
    
    @Test
    void testSerializability() {
        // VersionedJournalEntry should be serializable since it extends JournalEntry
        JournalEntry<String> entry = new JournalEntry<>(1L, "actor-1", "message");
        VersionedJournalEntry<String> versioned = new VersionedJournalEntry<>(2, entry);
        
        // This test verifies the class structure is correct for serialization
        assertTrue(versioned instanceof java.io.Serializable);
    }
}
