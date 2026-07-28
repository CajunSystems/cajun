package com.cajunsystems.persistence;

import com.cajunsystems.persistence.filesystem.FileMessageJournal;
import com.cajunsystems.persistence.redis.RedisPersistenceProvider;
import com.cajunsystems.serialization.KryoSerializationProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("performance")
class PersistenceBenchmarkTest {

    private static final int N = 500;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("cajun-bench-");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach(p -> p.toFile().delete());
    }

    @Test
    void benchmark_file_journal_append() throws Exception {
        String actorId = "bench-" + UUID.randomUUID();
        FileMessageJournal<String> journal = new FileMessageJournal<>(
            tempDir.resolve("journal"), KryoSerializationProvider.INSTANCE);

        long start = System.nanoTime();
        for (int i = 0; i < N; i++) {
            journal.append(actorId, "message-" + i).get();
        }
        long elapsed = System.nanoTime() - start;

        double throughput = N * 1_000_000_000.0 / elapsed;
        System.out.printf("[BENCH] File journal append: %d messages in %.1f ms (%.0f msg/s)%n",
            N, elapsed / 1_000_000.0, throughput);

        List<JournalEntry<String>> entries = journal.readFrom(actorId, 1).get();
        assertEquals(N, entries.size(), "All messages written");
        journal.close();
    }

    @Test
    void benchmark_file_journal_recovery() throws Exception {
        String actorId = "bench-" + UUID.randomUUID();
        FileMessageJournal<String> journal = new FileMessageJournal<>(
            tempDir.resolve("journal-recovery"), KryoSerializationProvider.INSTANCE);

        for (int i = 0; i < N; i++) {
            journal.append(actorId, "message-" + i).get();
        }

        long start = System.nanoTime();
        List<JournalEntry<String>> entries = journal.readFrom(actorId, 1).get();
        long elapsed = System.nanoTime() - start;

        System.out.printf("[BENCH] File journal recovery: %d messages in %.1f ms%n",
            entries.size(), elapsed / 1_000_000.0);
        assertEquals(N, entries.size());
        journal.close();
    }

    @Test
    @Tag("requires-redis")
    void benchmark_redis_journal_append() throws Exception {
        String actorId = "bench-" + UUID.randomUUID();
        RedisPersistenceProvider provider = new RedisPersistenceProvider(
            "redis://localhost:6379", "cajun-bench", KryoSerializationProvider.INSTANCE);
        MessageJournal<String> journal = provider.createMessageJournal(actorId);

        try {
            long start = System.nanoTime();
            for (int i = 0; i < N; i++) {
                journal.append(actorId, "message-" + i).get();
            }
            long elapsed = System.nanoTime() - start;

            double throughput = N * 1_000_000_000.0 / elapsed;
            System.out.printf("[BENCH] Redis journal append: %d messages in %.1f ms (%.0f msg/s)%n",
                N, elapsed / 1_000_000.0, throughput);

            List<JournalEntry<String>> entries = journal.readFrom(actorId, 1).get();
            assertEquals(N, entries.size(), "All messages written to Redis");
        } finally {
            try { journal.truncateBefore(actorId, Long.MAX_VALUE).get(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
            journal.close();
            provider.close();
        }
    }

    @Test
    @Tag("requires-redis")
    void benchmark_redis_journal_recovery() throws Exception {
        String actorId = "bench-" + UUID.randomUUID();
        RedisPersistenceProvider provider = new RedisPersistenceProvider(
            "redis://localhost:6379", "cajun-bench", KryoSerializationProvider.INSTANCE);
        MessageJournal<String> journal = provider.createMessageJournal(actorId);

        try {
            for (int i = 0; i < N; i++) {
                journal.append(actorId, "message-" + i).get();
            }

            long start = System.nanoTime();
            List<JournalEntry<String>> entries = journal.readFrom(actorId, 1).get();
            long elapsed = System.nanoTime() - start;

            System.out.printf("[BENCH] Redis journal recovery: %d messages in %.1f ms%n",
                entries.size(), elapsed / 1_000_000.0);
            assertEquals(N, entries.size());
        } finally {
            try { journal.truncateBefore(actorId, Long.MAX_VALUE).get(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
            journal.close();
            provider.close();
        }
    }
}
