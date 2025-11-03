package com.cajunsystems.persistence.versioning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceVersionTest {
    
    @Test
    void testConstants() {
        assertEquals(1, PersistenceVersion.DEFAULT_VERSION);
        assertEquals(1, PersistenceVersion.CURRENT_VERSION);
        assertEquals(1, PersistenceVersion.MIN_SUPPORTED_VERSION);
        assertEquals(0, PersistenceVersion.UNVERSIONED);
    }
    
    @Test
    void testValidateVersion() {
        // Valid versions should not throw
        assertDoesNotThrow(() -> PersistenceVersion.validateVersion(0));
        assertDoesNotThrow(() -> PersistenceVersion.validateVersion(1));
        assertDoesNotThrow(() -> PersistenceVersion.validateVersion(100));
        
        // Negative versions should throw
        assertThrows(IllegalArgumentException.class, () -> 
            PersistenceVersion.validateVersion(-1)
        );
        assertThrows(IllegalArgumentException.class, () -> 
            PersistenceVersion.validateVersion(-100)
        );
    }
    
    @Test
    void testIsSupported() {
        // UNVERSIONED is supported
        assertTrue(PersistenceVersion.isSupported(PersistenceVersion.UNVERSIONED));
        
        // Current version is supported
        assertTrue(PersistenceVersion.isSupported(PersistenceVersion.CURRENT_VERSION));
        
        // MIN_SUPPORTED_VERSION is supported
        assertTrue(PersistenceVersion.isSupported(PersistenceVersion.MIN_SUPPORTED_VERSION));
        
        // Versions above current are not supported
        assertFalse(PersistenceVersion.isSupported(PersistenceVersion.CURRENT_VERSION + 1));
        
        // Versions below min are not supported (except UNVERSIONED)
        if (PersistenceVersion.MIN_SUPPORTED_VERSION > 1) {
            assertFalse(PersistenceVersion.isSupported(PersistenceVersion.MIN_SUPPORTED_VERSION - 1));
        }
    }
    
    @Test
    void testIsCompatible() {
        // Same version is compatible
        assertTrue(PersistenceVersion.isCompatible(1, 1));
        
        // UNVERSIONED to any supported version is compatible
        assertTrue(PersistenceVersion.isCompatible(0, 1));
        assertTrue(PersistenceVersion.isCompatible(1, 0));
        
        // Unsupported versions are not compatible
        assertFalse(PersistenceVersion.isCompatible(1, 2)); // 2 is above CURRENT_VERSION
        assertFalse(PersistenceVersion.isCompatible(2, 1)); // 2 is above CURRENT_VERSION
        
        // Invalid versions throw exception
        assertThrows(IllegalArgumentException.class, () ->
            PersistenceVersion.isCompatible(-1, 1)
        );
    }
    
    @Test
    void testNeedsMigration() {
        // Version less than current needs migration
        if (PersistenceVersion.CURRENT_VERSION > 1) {
            assertTrue(PersistenceVersion.needsMigration(PersistenceVersion.CURRENT_VERSION - 1));
        }
        
        // Current version doesn't need migration
        assertFalse(PersistenceVersion.needsMigration(PersistenceVersion.CURRENT_VERSION));
        
        // UNVERSIONED needs migration
        assertTrue(PersistenceVersion.needsMigration(PersistenceVersion.UNVERSIONED));
        
        // Invalid version throws exception
        assertThrows(IllegalArgumentException.class, () ->
            PersistenceVersion.needsMigration(-1)
        );
    }
    
    @Test
    void testGetVersionRange() {
        // Single version range
        int[] range1 = PersistenceVersion.getVersionRange(1, 1);
        assertArrayEquals(new int[]{1}, range1);
        
        // Multi-version range
        int[] range2 = PersistenceVersion.getVersionRange(1, 3);
        assertArrayEquals(new int[]{1, 2, 3}, range2);
        
        // Range including UNVERSIONED
        int[] range3 = PersistenceVersion.getVersionRange(0, 2);
        assertArrayEquals(new int[]{0, 1, 2}, range3);
        
        // Invalid range (from > to) throws exception
        assertThrows(IllegalArgumentException.class, () ->
            PersistenceVersion.getVersionRange(3, 1)
        );
        
        // Invalid version throws exception
        assertThrows(IllegalArgumentException.class, () ->
            PersistenceVersion.getVersionRange(-1, 1)
        );
    }
    
    @Test
    void testGetMigrationSteps() {
        // No migration needed
        assertEquals(0, PersistenceVersion.getMigrationSteps(1, 1));
        
        // From UNVERSIONED to current
        assertEquals(1, PersistenceVersion.getMigrationSteps(0, 1));
        
        // Backward migration (rollback)
        assertEquals(1, PersistenceVersion.getMigrationSteps(1, 0));
        
        // Unsupported versions throw exception
        assertThrows(IllegalArgumentException.class, () ->
            PersistenceVersion.getMigrationSteps(1, 2) // 2 is above CURRENT_VERSION
        );
    }
    
    @Test
    void testFormatVersion() {
        assertEquals("unversioned", PersistenceVersion.formatVersion(PersistenceVersion.UNVERSIONED));
        assertEquals("v1", PersistenceVersion.formatVersion(1));
        assertEquals("v2", PersistenceVersion.formatVersion(2));
        assertEquals("v100", PersistenceVersion.formatVersion(100));
    }
    
    @Test
    void testIsCurrent() {
        assertTrue(PersistenceVersion.isCurrent(PersistenceVersion.CURRENT_VERSION));
        assertFalse(PersistenceVersion.isCurrent(PersistenceVersion.CURRENT_VERSION - 1));
        assertFalse(PersistenceVersion.isCurrent(PersistenceVersion.CURRENT_VERSION + 1));
        assertFalse(PersistenceVersion.isCurrent(PersistenceVersion.UNVERSIONED));
    }
    
    @Test
    void testIsUnversioned() {
        assertTrue(PersistenceVersion.isUnversioned(PersistenceVersion.UNVERSIONED));
        assertFalse(PersistenceVersion.isUnversioned(1));
        assertFalse(PersistenceVersion.isUnversioned(2));
    }
    
    @Test
    void testGetNextVersion() {
        assertEquals(1, PersistenceVersion.getNextVersion(0));
        assertEquals(2, PersistenceVersion.getNextVersion(1));
        assertEquals(3, PersistenceVersion.getNextVersion(2));
        
        // Invalid version throws exception
        assertThrows(IllegalArgumentException.class, () ->
            PersistenceVersion.getNextVersion(-1)
        );
    }
    
    @Test
    void testGetPreviousVersion() {
        assertEquals(0, PersistenceVersion.getPreviousVersion(1));
        assertEquals(1, PersistenceVersion.getPreviousVersion(2));
        assertEquals(2, PersistenceVersion.getPreviousVersion(3));
        
        // Cannot get previous of UNVERSIONED
        assertThrows(IllegalArgumentException.class, () ->
            PersistenceVersion.getPreviousVersion(PersistenceVersion.UNVERSIONED)
        );
        
        // Invalid version throws exception
        assertThrows(IllegalArgumentException.class, () ->
            PersistenceVersion.getPreviousVersion(-1)
        );
    }
    
    @Test
    void testUtilityClassCannotBeInstantiated() {
        // Verify that the constructor throws AssertionError
        try {
            var constructor = PersistenceVersion.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            constructor.newInstance();
            fail("Expected AssertionError to be thrown");
        } catch (Exception e) {
            // Should get InvocationTargetException wrapping AssertionError
            assertTrue(e.getCause() instanceof AssertionError);
        }
    }
}
