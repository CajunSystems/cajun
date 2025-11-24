package com.cajunsystems.persistence.filesystem;

import com.cajunsystems.persistence.TruncationCapableJournal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that verifies filesystem journals implement the truncation
 * capability and that truncateBefore physically removes old files.
 */
class FilesystemTruncationIntegrationTest {

    private Path tempDir;

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @Test
    void fileMessageJournalIsTruncationCapableAndDeletesOldFiles() throws Exception {
        tempDir = Files.createTempDirectory("cajun-fs-truncation-test");

        FileMessageJournal<String> journal = new FileMessageJournal<>(tempDir);
        String actorId = "actor-1";

        assertTrue(journal instanceof TruncationCapableJournal,
                "FileMessageJournal should be truncation capable");

        // Append a few messages
        for (int i = 0; i < 20; i++) {
            journal.append(actorId, "msg-" + i).join();
        }

        Path actorDir = tempDir.resolve(actorId);
        long initialCount = Files.list(actorDir)
                .filter(p -> p.getFileName().toString().endsWith(".journal"))
                .count();

        assertTrue(initialCount >= 20, "Expected at least 20 journal files, got " + initialCount);

        // Truncate before sequence 10
        journal.truncateBefore(actorId, 10).join();

        long remaining = Files.list(actorDir)
                .filter(p -> p.getFileName().toString().endsWith(".journal"))
                .count();

        assertTrue(remaining < initialCount, "Expected some files to be deleted by truncation");
    }
}
