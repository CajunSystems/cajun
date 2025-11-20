package com.cajunsystems.persistence.filesystem;

import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.MessageJournal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class JournalCleanupTest {

    private static class RecordingJournal implements MessageJournal<String> {
        long lastTruncateActorCutoff = -1;
        String lastActorId;

        @Override
        public CompletableFuture<Long> append(String actorId, String message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<List<JournalEntry<String>>> readFrom(String actorId, long fromSequenceNumber) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> truncateBefore(String actorId, long upToSequenceNumber) {
            this.lastActorId = actorId;
            this.lastTruncateActorCutoff = upToSequenceNumber;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Long> getHighestSequenceNumber(String actorId) {
            return CompletableFuture.completedFuture(-1L);
        }

        @Override
        public void close() {
        }
    }

    @Test
    void cleanupOnSnapshot_noSnapshotDoesNothing() {
        RecordingJournal journal = new RecordingJournal();

        JournalCleanup.cleanupOnSnapshot(journal, "actor-1", -1, 100).join();

        assertNull(journal.lastActorId);
        assertEquals(-1, journal.lastTruncateActorCutoff);
    }

    @Test
    void cleanupOnSnapshot_negativeRetainIsClampedToZero() {
        RecordingJournal journal = new RecordingJournal();

        JournalCleanup.cleanupOnSnapshot(journal, "actor-1", 50, -10).join();

        assertEquals("actor-1", journal.lastActorId);
        assertEquals(50, journal.lastTruncateActorCutoff);
    }

    @Test
    void cleanupOnSnapshot_withRetentionComputesCorrectCutoff() {
        RecordingJournal journal = new RecordingJournal();

        JournalCleanup.cleanupOnSnapshot(journal, "actor-1", 1000, 100).join();

        assertEquals("actor-1", journal.lastActorId);
        assertEquals(900, journal.lastTruncateActorCutoff);
    }

    @Test
    void cleanupOnSnapshot_cutoffLessOrEqualZeroIsNoOp() {
        RecordingJournal journal = new RecordingJournal();

        JournalCleanup.cleanupOnSnapshot(journal, "actor-1", 50, 100).join();

        assertNull(journal.lastActorId);
        assertEquals(-1, journal.lastTruncateActorCutoff);
    }
}
