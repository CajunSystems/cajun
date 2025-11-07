package com.cajunsystems.persistence.versioning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MigrationKeyTest {
    
    @Test
    void testCreateMigrationKey() {
        MigrationKey key = new MigrationKey("com.example.Message", 1, 2);
        
        assertEquals("com.example.Message", key.messageType());
        assertEquals(1, key.fromVersion());
        assertEquals(2, key.toVersion());
    }
    
    @Test
    void testCreateFromClass() {
        MigrationKey key = MigrationKey.of(String.class, 1, 2);
        
        assertEquals("java.lang.String", key.messageType());
        assertEquals(1, key.fromVersion());
        assertEquals(2, key.toVersion());
    }
    
    @Test
    void testNullMessageTypeThrows() {
        assertThrows(IllegalArgumentException.class, () -> 
            new MigrationKey(null, 1, 2)
        );
    }
    
    @Test
    void testEmptyMessageTypeThrows() {
        assertThrows(IllegalArgumentException.class, () -> 
            new MigrationKey("", 1, 2)
        );
        
        assertThrows(IllegalArgumentException.class, () -> 
            new MigrationKey("   ", 1, 2)
        );
    }
    
    @Test
    void testNegativeVersionThrows() {
        assertThrows(IllegalArgumentException.class, () -> 
            new MigrationKey("Message", -1, 2)
        );
        
        assertThrows(IllegalArgumentException.class, () -> 
            new MigrationKey("Message", 1, -1)
        );
    }
    
    @Test
    void testSameVersionThrows() {
        assertThrows(IllegalArgumentException.class, () -> 
            new MigrationKey("Message", 1, 1)
        );
    }
    
    @Test
    void testIsForwardMigration() {
        MigrationKey forward = new MigrationKey("Message", 1, 2);
        MigrationKey backward = new MigrationKey("Message", 2, 1);
        
        assertTrue(forward.isForwardMigration());
        assertFalse(backward.isForwardMigration());
    }
    
    @Test
    void testIsBackwardMigration() {
        MigrationKey forward = new MigrationKey("Message", 1, 2);
        MigrationKey backward = new MigrationKey("Message", 2, 1);
        
        assertFalse(forward.isBackwardMigration());
        assertTrue(backward.isBackwardMigration());
    }
    
    @Test
    void testGetSteps() {
        MigrationKey single = new MigrationKey("Message", 1, 2);
        MigrationKey multi = new MigrationKey("Message", 1, 4);
        MigrationKey backward = new MigrationKey("Message", 3, 1);
        
        assertEquals(1, single.getSteps());
        assertEquals(3, multi.getSteps());
        assertEquals(2, backward.getSteps());
    }
    
    @Test
    void testReverse() {
        MigrationKey original = new MigrationKey("Message", 1, 2);
        MigrationKey reversed = original.reverse();
        
        assertEquals("Message", reversed.messageType());
        assertEquals(2, reversed.fromVersion());
        assertEquals(1, reversed.toVersion());
        
        // Reversing twice should give original
        MigrationKey doubleReversed = reversed.reverse();
        assertEquals(original, doubleReversed);
    }
    
    @Test
    void testToString() {
        MigrationKey forward = new MigrationKey("com.example.OrderMessage", 1, 2);
        MigrationKey backward = new MigrationKey("com.example.OrderMessage", 2, 1);
        
        String forwardStr = forward.toString();
        String backwardStr = backward.toString();
        
        assertTrue(forwardStr.contains("OrderMessage"));
        assertTrue(forwardStr.contains("v1"));
        assertTrue(forwardStr.contains("v2"));
        assertTrue(forwardStr.contains("↑"));
        
        assertTrue(backwardStr.contains("↓"));
    }
    
    @Test
    void testEquality() {
        MigrationKey key1 = new MigrationKey("Message", 1, 2);
        MigrationKey key2 = new MigrationKey("Message", 1, 2);
        MigrationKey key3 = new MigrationKey("Message", 1, 3);
        MigrationKey key4 = new MigrationKey("OtherMessage", 1, 2);
        
        assertEquals(key1, key2);
        assertNotEquals(key1, key3);
        assertNotEquals(key1, key4);
        assertNotEquals(key1, null);
    }
    
    @Test
    void testHashCode() {
        MigrationKey key1 = new MigrationKey("Message", 1, 2);
        MigrationKey key2 = new MigrationKey("Message", 1, 2);
        
        assertEquals(key1.hashCode(), key2.hashCode());
    }
    
    @Test
    void testSerializable() {
        MigrationKey key = new MigrationKey("Message", 1, 2);
        assertTrue(key instanceof java.io.Serializable);
    }
}
