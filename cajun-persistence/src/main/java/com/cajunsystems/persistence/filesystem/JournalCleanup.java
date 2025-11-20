package com.cajunsystems.persistence.filesystem;

import com.cajunsystems.persistence.MessageJournal;

import java.util.concurrent.CompletableFuture;

/**
 * Helper utilities for cleaning up journals based on snapshots or retention policies.
 */
public final class JournalCleanup {

    private JournalCleanup() {
    }

    /**
     * Synchronous-style helper to be called immediately after {@code ctx.saveSnapshot()}.
     *
     * <p>Typical usage in a stateful handler:</p>
     * <pre>
     *     ctx.saveSnapshot(newState);
     *     JournalCleanup.cleanupOnSnapshot(journal, actorId, snapshotSeq, 100).join();
     * </pre>
     *
     * @param journal              journal implementation (filesystem, LMDB, etc.)
     * @param actorId              actor identifier
     * @param snapshotSequence     sequence number associated with the snapshot
     * @param retainMessagesBehind how many messages before the snapshot to retain
     * @param <M>                  message type
     * @return CompletableFuture completing when truncation is done
     */
    public static <M> CompletableFuture<Void> cleanupOnSnapshot(
            MessageJournal<M> journal,
            String actorId,
            long snapshotSequence,
            long retainMessagesBehind
    ) {
        if (snapshotSequence < 0) {
            // Nothing to clean up yet
            return CompletableFuture.completedFuture(null);
        }
        if (retainMessagesBehind < 0) {
            retainMessagesBehind = 0;
        }
        long cutoff = snapshotSequence - retainMessagesBehind;
        if (cutoff <= 0) {
            // Keeping all messages
            return CompletableFuture.completedFuture(null);
        }
        return journal.truncateBefore(actorId, cutoff);
    }
}
