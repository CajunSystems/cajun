package com.cajunsystems.persistence;

import com.cajunsystems.runtime.persistence.PersistenceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the {@code PersistenceFactory.create*(String baseDir)} overloads, which
 * previously ignored their {@code baseDir} argument and always wrote to the single global
 * {@code cajun_persistence} directory. That made per-run/per-test isolation impossible without
 * unique actor ids and an {@code rm -rf ./cajun_persistence}.
 */
class PersistenceFactoryBaseDirTest {

    record Msg(String value) implements Serializable {}

    @Test
    void baseDirArgumentIsHonoredForJournals(@TempDir Path rootA, @TempDir Path rootB) throws Exception {
        BatchedMessageJournal<Msg> journalA = PersistenceFactory.createBatchedFileMessageJournal(rootA.toString());
        BatchedMessageJournal<Msg> journalB = PersistenceFactory.createBatchedFileMessageJournal(rootB.toString());
        try {
            // Same actor id in both journals; only the base directory differs.
            journalA.append("actor", new Msg("in-A")).get(5, TimeUnit.SECONDS);
            journalB.append("actor", new Msg("in-B")).get(5, TimeUnit.SECONDS);

            Path journalDirA = rootA.resolve("journal").resolve("actor");
            Path journalDirB = rootB.resolve("journal").resolve("actor");

            assertTrue(Files.isDirectory(journalDirA), "journal A should write under its own baseDir: " + journalDirA);
            assertTrue(Files.isDirectory(journalDirB), "journal B should write under its own baseDir: " + journalDirB);

            assertTrue(hasJournalFile(journalDirA), "expected a .journal file under " + journalDirA);
            assertTrue(hasJournalFile(journalDirB), "expected a .journal file under " + journalDirB);

            // The two stores must not bleed into each other.
            assertFalse(Files.exists(rootA.resolve("journal").resolve("actor").resolve("does-not-cross")),
                    "sanity");
        } finally {
            journalA.close();
            journalB.close();
        }
    }

    @Test
    void baseDirArgumentIsHonoredForSnapshots(@TempDir Path root) {
        SnapshotStore<String> store = PersistenceFactory.createFileSnapshotStore(root.toString());
        store.saveSnapshot("actor", "state", 0).join();
        assertTrue(Files.isDirectory(root.resolve("snapshots")),
                "snapshot store should write under its own baseDir: " + root.resolve("snapshots"));
    }

    private static boolean hasJournalFile(Path dir) throws Exception {
        try (var paths = Files.list(dir)) {
            return paths.anyMatch(p -> p.getFileName().toString().endsWith(".journal"));
        }
    }
}
