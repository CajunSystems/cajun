package com.cajunsystems.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SnapshotMetadata.
 */
class SnapshotMetadataTest {
    
    @Test
    void testConstructorWithoutFileSize() {
        SnapshotMetadata metadata = new SnapshotMetadata(100L, 1731684000000L);
        
        assertEquals(100L, metadata.getSequence());
        assertEquals(1731684000000L, metadata.getTimestamp());
        assertEquals(-1, metadata.getFileSize());
        assertFalse(metadata.hasFileSize());
    }
    
    @Test
    void testConstructorWithFileSize() {
        SnapshotMetadata metadata = new SnapshotMetadata(100L, 1731684000000L, 1024L);
        
        assertEquals(100L, metadata.getSequence());
        assertEquals(1731684000000L, metadata.getTimestamp());
        assertEquals(1024L, metadata.getFileSize());
        assertTrue(metadata.hasFileSize());
    }
    
    @Test
    void testHasFileSizeWithZeroSize() {
        SnapshotMetadata metadata = new SnapshotMetadata(100L, 1731684000000L, 0L);
        
        assertTrue(metadata.hasFileSize(), "Zero is a valid file size");
        assertEquals(0L, metadata.getFileSize());
    }
    
    @Test
    void testEquality() {
        SnapshotMetadata metadata1 = new SnapshotMetadata(100L, 1731684000000L, 1024L);
        SnapshotMetadata metadata2 = new SnapshotMetadata(100L, 1731684000000L, 1024L);
        SnapshotMetadata metadata3 = new SnapshotMetadata(101L, 1731684000000L, 1024L);
        
        assertEquals(metadata1, metadata2);
        assertNotEquals(metadata1, metadata3);
    }
    
    @Test
    void testHashCode() {
        SnapshotMetadata metadata1 = new SnapshotMetadata(100L, 1731684000000L, 1024L);
        SnapshotMetadata metadata2 = new SnapshotMetadata(100L, 1731684000000L, 1024L);
        
        assertEquals(metadata1.hashCode(), metadata2.hashCode());
    }
    
    @Test
    void testToString() {
        SnapshotMetadata metadata = new SnapshotMetadata(100L, 1731684000000L, 1024L);
        
        String str = metadata.toString();
        assertTrue(str.contains("sequence=100"));
        assertTrue(str.contains("timestamp=1731684000000"));
        assertTrue(str.contains("1024 bytes"));
    }
    
    @Test
    void testToStringWithoutFileSize() {
        SnapshotMetadata metadata = new SnapshotMetadata(100L, 1731684000000L);
        
        String str = metadata.toString();
        assertTrue(str.contains("sequence=100"));
        assertTrue(str.contains("timestamp=1731684000000"));
        assertTrue(str.contains("unknown"));
    }
}
