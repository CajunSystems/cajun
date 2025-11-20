package com.cajunsystems.benchmarks;

import com.cajunsystems.persistence.MessageJournal;
import com.cajunsystems.persistence.impl.FileSystemPersistenceProvider;
import com.cajunsystems.persistence.lmdb.LmdbPersistenceProvider;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmarks comparing different persistence backends at the journal level.
 *
 * This measures raw append throughput for small messages using the async
 * MessageJournal API for filesystem and LMDB backends.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class PersistenceJournalBenchmark {

    @Param({"filesystem", "lmdb"})
    public String backend;

    private Path tempDir;
    private MessageJournal<SmallMsg> journal;
    private FileSystemPersistenceProvider fsProvider;
    private LmdbPersistenceProvider lmdbProvider;
    private final String actorId = "bench-actor";

    /**
     * Small serializable message payload for journaling.
     */
    public record SmallMsg(int value) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    @Setup
    public void setup() throws IOException {
        tempDir = Files.createTempDirectory("cajun-persistence-bench-");

        switch (backend) {
            case "filesystem" -> {
                fsProvider = new FileSystemPersistenceProvider(tempDir.toString());
                journal = fsProvider.createMessageJournal(actorId);
            }
            case "lmdb" -> {
                // Use a modest map size suitable for benchmarks
                long mapSize = 256L * 1024 * 1024; // 256MB
                lmdbProvider = new LmdbPersistenceProvider(tempDir, mapSize);
                journal = lmdbProvider.createMessageJournal(actorId);
            }
            default -> throw new IllegalArgumentException("Unknown backend: " + backend);
        }
    }

    @TearDown
    public void tearDown() throws Exception {
        try {
            if (journal != null) {
                journal.close();
            }
            if (lmdbProvider != null) {
                lmdbProvider.close();
            }
        } finally {
            if (tempDir != null) {
                try (var stream = Files.walk(tempDir)) {
                    stream.sorted((a, b) -> b.compareTo(a))
                          .forEach(path -> {
                              try {
                                  Files.deleteIfExists(path);
                              } catch (IOException ignored) {
                              }
                          });
                }
            }
        }
    }

    /**
     * Append a burst of messages to the journal.
     */
    @Benchmark
    @OperationsPerInvocation(1000)
    public void appendBurst() {
        for (int i = 0; i < 1000; i++) {
            // Use join() so we measure the actual completion time of async append
            journal.append(actorId, new SmallMsg(i)).join();
        }
    }
}
