package com.cajunsystems.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class PersistenceMetricsTest {

    @AfterEach
    void tearDown() {
        // Clean up registry entries created during tests
        PersistenceMetricsRegistry.unregister("a");
        PersistenceMetricsRegistry.unregister("x");
    }

    @Test
    void testJournalAppendRecorded() {
        PersistenceMetrics metrics = new PersistenceMetrics("actor-1");

        metrics.recordJournalAppend(100L);

        assertEquals(1, metrics.getJournalAppendCount());
        assertEquals(100L, metrics.getAverageJournalAppendLatencyNs());
    }

    @Test
    void testSnapshotSaveRecorded() {
        PersistenceMetrics metrics = new PersistenceMetrics("actor-2");

        metrics.recordSnapshotSave(200L);

        assertEquals(1, metrics.getSnapshotSaveCount());
        assertEquals(200L, metrics.getAverageSnapshotSaveLatencyNs());
    }

    @Test
    void testErrorCounters() {
        PersistenceMetrics metrics = new PersistenceMetrics("actor-3");

        metrics.incrementJournalAppendErrors();
        metrics.incrementJournalAppendErrors();

        assertEquals(2, metrics.getJournalAppendErrors());
    }

    @Test
    void testRegistryGetOrCreateReturnsSameInstance() {
        PersistenceMetrics first = PersistenceMetricsRegistry.getOrCreate("a");
        PersistenceMetrics second = PersistenceMetricsRegistry.getOrCreate("a");

        assertSame(first, second);
    }

    @Test
    void testRegistryUnregister() {
        PersistenceMetricsRegistry.getOrCreate("x");
        PersistenceMetricsRegistry.unregister("x");

        Optional<PersistenceMetrics> result = PersistenceMetricsRegistry.get("x");
        assertTrue(result.isEmpty());
    }
}
