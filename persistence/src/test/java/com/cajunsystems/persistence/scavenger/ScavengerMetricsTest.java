package com.cajunsystems.persistence.scavenger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ScavengerMetrics.
 */
class ScavengerMetricsTest {
    
    private ScavengerMetrics metrics;
    
    @BeforeEach
    void setUp() {
        metrics = new ScavengerMetrics();
    }
    
    @Test
    void testInitialState() {
        assertEquals(0, metrics.getTotalScans());
        assertEquals(0, metrics.getTotalActorsProcessed());
        assertEquals(0, metrics.getTotalSnapshotsDeleted());
        assertEquals(0, metrics.getTotalJournalsDeleted());
        assertEquals(0, metrics.getTotalBytesReclaimed());
        assertEquals(0, metrics.getLastScanDurationMs());
        assertEquals(0, metrics.getAverageScanDurationMs());
        assertEquals(0, metrics.getLastScanTimestamp());
    }
    
    @Test
    void testRecordScanDuration() {
        long beforeTimestamp = System.currentTimeMillis();
        
        metrics.recordScanDuration(1000);
        
        assertEquals(1, metrics.getTotalScans());
        assertEquals(1000, metrics.getLastScanDurationMs());
        assertEquals(1000, metrics.getAverageScanDurationMs());
        assertTrue(metrics.getLastScanTimestamp() >= beforeTimestamp);
    }
    
    @Test
    void testRecordMultipleScans() {
        metrics.recordScanDuration(1000);
        metrics.recordScanDuration(2000);
        metrics.recordScanDuration(3000);
        
        assertEquals(3, metrics.getTotalScans());
        assertEquals(3000, metrics.getLastScanDurationMs());
        assertEquals(2000, metrics.getAverageScanDurationMs()); // (1000 + 2000 + 3000) / 3
    }
    
    @Test
    void testRecordActorProcessed() {
        metrics.recordActorProcessed(2, 5);
        
        assertEquals(1, metrics.getTotalActorsProcessed());
        assertEquals(2, metrics.getTotalSnapshotsDeleted());
        assertEquals(5, metrics.getTotalJournalsDeleted());
    }
    
    @Test
    void testRecordMultipleActors() {
        metrics.recordActorProcessed(2, 5);
        metrics.recordActorProcessed(3, 10);
        metrics.recordActorProcessed(1, 3);
        
        assertEquals(3, metrics.getTotalActorsProcessed());
        assertEquals(6, metrics.getTotalSnapshotsDeleted()); // 2 + 3 + 1
        assertEquals(18, metrics.getTotalJournalsDeleted()); // 5 + 10 + 3
    }
    
    @Test
    void testRecordBytesReclaimed() {
        metrics.recordBytesReclaimed(1024);
        
        assertEquals(1024, metrics.getTotalBytesReclaimed());
    }
    
    @Test
    void testRecordMultipleBytesReclaimed() {
        metrics.recordBytesReclaimed(1024);
        metrics.recordBytesReclaimed(2048);
        metrics.recordBytesReclaimed(512);
        
        assertEquals(3584, metrics.getTotalBytesReclaimed()); // 1024 + 2048 + 512
    }
    
    @Test
    void testCombinedMetrics() {
        // Simulate a complete scavenge run
        metrics.recordScanDuration(5000);
        metrics.recordActorProcessed(2, 10);
        metrics.recordActorProcessed(1, 5);
        metrics.recordActorProcessed(0, 3);
        metrics.recordBytesReclaimed(1024 * 1024); // 1 MB
        
        assertEquals(1, metrics.getTotalScans());
        assertEquals(3, metrics.getTotalActorsProcessed());
        assertEquals(3, metrics.getTotalSnapshotsDeleted()); // 2 + 1 + 0
        assertEquals(18, metrics.getTotalJournalsDeleted()); // 10 + 5 + 3
        assertEquals(1024 * 1024, metrics.getTotalBytesReclaimed());
        assertEquals(5000, metrics.getLastScanDurationMs());
    }
    
    @Test
    void testReset() {
        // Record some metrics
        metrics.recordScanDuration(1000);
        metrics.recordActorProcessed(5, 10);
        metrics.recordBytesReclaimed(2048);
        
        // Verify metrics are recorded
        assertTrue(metrics.getTotalScans() > 0);
        assertTrue(metrics.getTotalActorsProcessed() > 0);
        
        // Reset
        metrics.reset();
        
        // Verify all metrics are zero
        assertEquals(0, metrics.getTotalScans());
        assertEquals(0, metrics.getTotalActorsProcessed());
        assertEquals(0, metrics.getTotalSnapshotsDeleted());
        assertEquals(0, metrics.getTotalJournalsDeleted());
        assertEquals(0, metrics.getTotalBytesReclaimed());
        assertEquals(0, metrics.getLastScanDurationMs());
        assertEquals(0, metrics.getAverageScanDurationMs());
        assertEquals(0, metrics.getLastScanTimestamp());
    }
    
    @Test
    void testAverageScanDurationWithZeroScans() {
        assertEquals(0, metrics.getAverageScanDurationMs());
    }
    
    @Test
    void testToString() {
        metrics.recordScanDuration(1000);
        metrics.recordActorProcessed(2, 5);
        metrics.recordBytesReclaimed(1024);
        
        String str = metrics.toString();
        assertTrue(str.contains("totalScans=1"));
        assertTrue(str.contains("totalActorsProcessed=1"));
        assertTrue(str.contains("totalSnapshotsDeleted=2"));
        assertTrue(str.contains("totalJournalsDeleted=5"));
        assertTrue(str.contains("totalBytesReclaimed=1024"));
    }
    
    @Test
    void testThreadSafety() throws InterruptedException {
        // Simple thread safety test
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                metrics.recordActorProcessed(1, 1);
            }
        });
        
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                metrics.recordActorProcessed(1, 1);
            }
        });
        
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        
        assertEquals(200, metrics.getTotalActorsProcessed());
        assertEquals(200, metrics.getTotalSnapshotsDeleted());
        assertEquals(200, metrics.getTotalJournalsDeleted());
    }
}
