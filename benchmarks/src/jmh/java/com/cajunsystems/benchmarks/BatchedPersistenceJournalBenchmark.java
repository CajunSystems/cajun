package com.cajunsystems.benchmarks;

import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.impl.FileSystemPersistenceProvider;
import com.cajunsystems.persistence.lmdb.LmdbPersistenceProvider;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks comparing batched journal append throughput across persistence backends.
 *
 * This focuses on the BatchedMessageJournal API, which is the recommended way
 * to use LMDB efficiently.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class BatchedPersistenceJournalBenchmark {

    @Param({"filesystem", "lmdb"})
    public String backend;

    @Param({"5000"})
    public int batchSize;

    private Path tempDir;
    private BatchedMessageJournal<SmallMsg> journal;
    private FileSystemPersistenceProvider fsProvider;
    private LmdbPersistenceProvider lmdbProvider;
    private final String actorId = "bench-batched-actor";
    private List<SmallMsg> batch;

    /**
     * Small serializable message payload for journaling.
     */
    public record SmallMsg(int value) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    @Setup
    public void setup() throws IOException {
        tempDir = Files.createTempDirectory("cajun-batched-persistence-bench-");

        switch (backend) {
            case "filesystem" -> {
                fsProvider = new FileSystemPersistenceProvider(tempDir.toString());
                journal = fsProvider.createBatchedMessageJournal(actorId, batchSize, 10); // 10ms delay
            }
            case "lmdb" -> {
                long mapSize = 256L * 1024 * 1024; // 256MB
                lmdbProvider = new LmdbPersistenceProvider(tempDir, mapSize);
                // Use the native LMDB batched journal for better performance
                journal = lmdbProvider.createBatchedMessageJournalSerializable(actorId, batchSize, 10); // 10ms delay
            }
            default -> throw new IllegalArgumentException("Unknown backend: " + backend);
        }

        // Pre-build the batch so we don't measure allocation
        batch = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            batch.add(new SmallMsg(i));
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
     * Append a batch of messages to the journal.
     */
    @Benchmark
    @OperationsPerInvocation(1000)
    public void appendBatch() {
        // One batch append per invocation; JMH will normalize per-op
        journal.appendBatch(actorId, batch).join();
    }
}
