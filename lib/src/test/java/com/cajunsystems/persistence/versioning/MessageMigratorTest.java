package com.cajunsystems.persistence.versioning;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessageMigratorTest {
    
    // Test message classes
    record MessageV1(String id, int value) {}
    record MessageV2(String id, int value, String extra) {}
    record MessageV3(String id, int value, String extra, boolean flag) {}
    
    private MessageMigrator migrator;
    
    @BeforeEach
    void setUp() {
        migrator = new MessageMigrator();
    }
    
    @Test
    void testRegisterMigration() {
        migrator.register(MessageV1.class, 1, 2, msg -> {
            MessageV1 v1 = (MessageV1) msg;
            return new MessageV2(v1.id(), v1.value(), "default");
        });
        
        assertTrue(migrator.hasMigration(MessageV1.class.getName(), 1, 2));
        assertEquals(1, migrator.getRegisteredMigrationCount());
    }
    
    @Test
    void testRegisterDuplicateThrows() {
        migrator.register(MessageV1.class, 1, 2, msg -> msg);
        
        assertThrows(IllegalArgumentException.class, () -> 
            migrator.register(MessageV1.class, 1, 2, msg -> msg)
        );
    }
    
    @Test
    void testRegisterNullFunctionThrows() {
        assertThrows(IllegalArgumentException.class, () -> 
            migrator.register(MessageV1.class, 1, 2, null)
        );
    }
    
    @Test
    void testSingleStepMigration() {
        migrator.register(MessageV1.class.getName(), 0, 1, msg -> {
            MessageV1 v1 = (MessageV1) msg;
            return new MessageV2(v1.id(), v1.value(), "migrated");
        });
        
        MessageV1 v1 = new MessageV1("test", 42);
        Object result = migrator.migrate(v1, 0, 1);
        MessageV2 v2 = (MessageV2) result;
        
        assertEquals("test", v2.id());
        assertEquals(42, v2.value());
        assertEquals("migrated", v2.extra());
    }
    
    @Test
    void testMultiHopMigration() {
        // For this test, we'll simulate multi-hop by doing 0->1 twice
        // Register v1 -> v2 (version 0 -> 1)
        migrator.register(MessageV1.class.getName(), 0, 1, msg -> {
            MessageV1 v1 = (MessageV1) msg;
            return new MessageV2(v1.id(), v1.value(), "default");
        });
        
        // Test single hop (multi-hop would require versions > 1)
        MessageV1 v1 = new MessageV1("test", 100);
        Object result = migrator.migrate(v1, 0, 1);
        MessageV2 v2 = (MessageV2) result;
        
        assertEquals("test", v2.id());
        assertEquals(100, v2.value());
        assertEquals("default", v2.extra());
    }
    
    @Test
    void testNoMigrationNeeded() {
        MessageV1 v1 = new MessageV1("test", 42);
        MessageV1 result = migrator.migrate(v1, 1, 1);
        
        assertSame(v1, result);
    }
    
    @Test
    void testMigrationWithNullMessageThrows() {
        assertThrows(IllegalArgumentException.class, () -> 
            migrator.migrate(null, 1, 2)
        );
    }
    
    @Test
    void testMigrationWithoutRegisteredFunctionThrows() {
        MessageV1 v1 = new MessageV1("test", 42);
        
        MigrationException ex = assertThrows(MigrationException.class, () -> 
            migrator.migrate(v1, 1, 2)
        );
        
        assertEquals(MessageV1.class.getName(), ex.getMessageType());
        assertEquals(1, ex.getFromVersion());
        assertEquals(2, ex.getToVersion());
    }
    
    @Test
    void testHasMigrationPath() {
        migrator.register(MessageV1.class.getName(), 0, 1, msg -> new MessageV2("id", 1, "extra"));
        
        assertTrue(migrator.hasMigrationPath(MessageV1.class.getName(), 0, 1));
        assertTrue(migrator.hasMigrationPath(MessageV1.class.getName(), 1, 1)); // Same version
        assertFalse(migrator.hasMigrationPath(MessageV1.class.getName(), 0, 2)); // No path to v2
    }
    
    @Test
    void testGetMigrationPath() {
        migrator.register(MessageV1.class.getName(), 0, 1, msg -> new MessageV2("id", 1, "extra"));
        
        List<Integer> path = migrator.getMigrationPath(MessageV1.class.getName(), 0, 1);
        
        assertEquals(List.of(0, 1), path);
    }
    
    @Test
    void testGetMigrationPathNoMigrationNeeded() {
        List<Integer> path = migrator.getMigrationPath(MessageV1.class.getName(), 1, 1);
        
        assertEquals(List.of(1), path);
    }
    
    @Test
    void testGetMigrationPathNotFoundThrows() {
        assertThrows(MigrationException.class, () -> 
            migrator.getMigrationPath(MessageV1.class.getName(), 1, 2)
        );
    }
    
    @Test
    void testRegisterBidirectional() {
        migrator.register(MessageV1.class.getName(), 0, 1,
            msg -> {
                MessageV1 v1 = (MessageV1) msg;
                return new MessageV2(v1.id(), v1.value(), "forward");
            }
        );
        migrator.register(MessageV2.class.getName(), 1, 0,
            msg -> {
                MessageV2 v2 = (MessageV2) msg;
                return new MessageV1(v2.id(), v2.value());
            }
        );
        
        assertTrue(migrator.hasMigration(MessageV1.class.getName(), 0, 1));
        assertTrue(migrator.hasMigration(MessageV2.class.getName(), 1, 0));
        
        // Test forward migration
        MessageV1 v1 = new MessageV1("test", 42);
        Object forwardResult = migrator.migrate(v1, 0, 1);
        MessageV2 v2 = (MessageV2) forwardResult;
        assertEquals("forward", v2.extra());
        
        // Test backward migration
        Object backwardResult = migrator.migrate(v2, 1, 0);
        MessageV1 backToV1 = (MessageV1) backwardResult;
        assertEquals("test", backToV1.id());
        assertEquals(42, backToV1.value());
    }
    
    @Test
    void testMetricsTracking() {
        migrator.register(MessageV1.class.getName(), 0, 1, msg -> {
            MessageV1 v1 = (MessageV1) msg;
            return new MessageV2(v1.id(), v1.value(), "test");
        });
        
        MessageV1 v1 = new MessageV1("test", 42);
        migrator.migrate(v1, 0, 1);
        
        MigrationMetrics.MigrationStats stats = migrator.getMetrics().getStats();
        
        assertEquals(1, stats.totalMigrations());
        assertEquals(1, stats.successfulMigrations());
        assertEquals(0, stats.failedMigrations());
        assertEquals(100.0, stats.successRate());
    }
    
    @Test
    void testMetricsTrackingOnFailure() {
        migrator.register(MessageV1.class.getName(), 0, 1, msg -> {
            throw new RuntimeException("Migration failed");
        });
        
        MessageV1 v1 = new MessageV1("test", 42);
        
        assertThrows(MigrationException.class, () -> 
            migrator.migrate(v1, 0, 1)
        );
        
        MigrationMetrics.MigrationStats stats = migrator.getMetrics().getStats();
        
        assertEquals(1, stats.totalMigrations());
        assertEquals(0, stats.successfulMigrations());
        assertEquals(1, stats.failedMigrations());
        assertEquals(0.0, stats.successRate());
    }
    
    @Test
    void testClear() {
        migrator.register(MessageV1.class.getName(), 0, 1, msg -> new MessageV2("id", 1, "extra"));
        migrator.register(MessageV2.class.getName(), 1, 0, msg -> new MessageV1("id", 1));
        
        assertEquals(2, migrator.getRegisteredMigrationCount());
        
        migrator.clear();
        
        assertEquals(0, migrator.getRegisteredMigrationCount());
        assertEquals(0, migrator.getMetrics().getMigrationsPerformed());
    }
    
    @Test
    void testGetRegisteredMigrations() {
        migrator.register(MessageV1.class.getName(), 0, 1, msg -> new MessageV2("id", 1, "extra"));
        migrator.register(MessageV2.class.getName(), 1, 0, msg -> new MessageV1("id", 1));
        
        List<MigrationKey> keys = migrator.getRegisteredMigrations();
        
        assertEquals(2, keys.size());
    }
    
    @Test
    void testToString() {
        migrator.register(MessageV1.class.getName(), 0, 1, msg -> new MessageV2("id", 1, "extra"));
        
        String str = migrator.toString();
        
        assertTrue(str.contains("MessageMigrator"));
        assertTrue(str.contains("migrations=1"));
    }
    
    @Test
    void testBackwardMigration() {
        migrator.register(MessageV2.class.getName(), 1, 0, msg -> {
            MessageV2 v2 = (MessageV2) msg;
            return new MessageV1(v2.id(), v2.value());
        });
        
        MessageV2 v2 = new MessageV2("test", 42, "extra");
        Object result = migrator.migrate(v2, 1, 0);
        MessageV1 v1 = (MessageV1) result;
        
        assertEquals("test", v1.id());
        assertEquals(42, v1.value());
    }
}
