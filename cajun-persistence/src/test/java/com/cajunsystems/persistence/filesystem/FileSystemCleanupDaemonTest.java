package com.cajunsystems.persistence.filesystem;

import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.MessageJournal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class FileSystemCleanupDaemonTest {

    private static class StubJournal implements MessageJournal<String> {
        long highestSeq = -1;
        final List<Long> truncateCalls = new ArrayList<>();

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
            truncateCalls.add(upToSequenceNumber);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Long> getHighestSequenceNumber(String actorId) {
            return CompletableFuture.completedFuture(highestSeq);
        }

        @Override
        public void close() {
        }
    }

    @AfterEach
    void resetDaemon() {
        FileSystemCleanupDaemon daemon = FileSystemCleanupDaemon.getInstance();
        daemon.close();
    }

    @Test
    void runCleanupOnce_noJournalsIsNoOp() {
        FileSystemCleanupDaemon daemon = FileSystemCleanupDaemon.getInstance();

        assertDoesNotThrow(() -> daemon.runCleanupOnce().join());
    }

    @Test
    void runCleanupOnce_truncatesBasedOnHighestSequence() {
        FileSystemCleanupDaemon daemon = FileSystemCleanupDaemon.getInstance();
        StubJournal journal = new StubJournal();
        journal.highestSeq = 10_000;

        daemon.setRetainLastMessagesPerActor(1000);
        daemon.registerJournal("actor-1", journal);

        daemon.runCleanupOnce().join();

        assertEquals(1, journal.truncateCalls.size());
        assertEquals(9000L, journal.truncateCalls.getFirst());
    }

    @Test
    void runCleanupOnce_doesNothingForNegativeHighestSeq() {
        FileSystemCleanupDaemon daemon = FileSystemCleanupDaemon.getInstance();
        StubJournal journal = new StubJournal();
        journal.highestSeq = -1;

        daemon.setRetainLastMessagesPerActor(1000);
        daemon.registerJournal("actor-1", journal);

        daemon.runCleanupOnce().join();

        assertTrue(journal.truncateCalls.isEmpty());
    }

    @Test
    void runCleanupOnce_usesPerActorRetentionWhenProvided() {
        FileSystemCleanupDaemon daemon = FileSystemCleanupDaemon.getInstance();

        StubJournal journal1 = new StubJournal();
        StubJournal journal2 = new StubJournal();

        journal1.highestSeq = 10_000;
        journal2.highestSeq = 20_000;

        // Set a global default that would keep 1_000 messages
        daemon.setRetainLastMessagesPerActor(1_000);

        // Actor-1 uses custom retention of 100 messages
        daemon.registerJournal("actor-1", journal1, 100);
        // Actor-2 uses global default
        daemon.registerJournal("actor-2", journal2);

        daemon.runCleanupOnce().join();

        // Actor-1: cutoff = 10_000 - 100 = 9_900
        assertEquals(1, journal1.truncateCalls.size());
        assertEquals(9_900L, journal1.truncateCalls.getFirst());

        // Actor-2: cutoff = 20_000 - 1_000 = 19_000
        assertEquals(1, journal2.truncateCalls.size());
        assertEquals(19_000L, journal2.truncateCalls.getFirst());
    }

    @Test
    void runCleanupOnce_doesNothingWhenCutoffNonPositive() {
        FileSystemCleanupDaemon daemon = FileSystemCleanupDaemon.getInstance();
        StubJournal journal = new StubJournal();
        journal.highestSeq = 500;

        daemon.setRetainLastMessagesPerActor(1000); // cutoff will be negative
        daemon.registerJournal("actor-1", journal);

        daemon.runCleanupOnce().join();

        assertTrue(journal.truncateCalls.isEmpty());
    }
}
